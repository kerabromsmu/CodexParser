package se.dimage.codexparser;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ConversationDiscovery {
    private final RolloutReader reader = new RolloutReader();

    public record ScanResult(List<Conversation> conversations, List<String> warnings) {
        public ScanResult {
            conversations = List.copyOf(conversations);
            warnings = List.copyOf(warnings);
        }
    }

    public static List<Path> defaultRoots() {
        return defaultRoots(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static List<Path> defaultRoots(Map<String, String> environment, Path home) {
        var roots = new LinkedHashSet<Path>();
        String codexHome = environment.get("CODEX_HOME");
        if (codexHome != null && !codexHome.isBlank()) roots.add(Path.of(codexHome));
        roots.add(home.resolve(".codex"));
        return List.copyOf(roots);
    }

    public ScanResult scan(List<Path> roots, BooleanSupplier cancelled, Consumer<String> progress) {
        var conversations = new ArrayList<Conversation>();
        var warnings = new ArrayList<String>();
        Set<Path> visited = new HashSet<>();
        Map<Path, Map<String, String>> indexes = new HashMap<>();
        for (Path root : roots) {
            RolloutReader.checkCancelled(cancelled);
            if (!Files.exists(root)) continue;
            List<Path> scanRoots = Files.isDirectory(root.resolve("sessions"))
                    || Files.isDirectory(root.resolve("archived_sessions"))
                    ? List.of(root.resolve("sessions"), root.resolve("archived_sessions")) : List.of(root);
            for (Path scanRoot : scanRoots) {
                if (!Files.exists(scanRoot)) continue;
                try {
                    Files.walkFileTree(scanRoot.toRealPath(), new SimpleFileVisitor<>() {
                        @Override
                        public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                            RolloutReader.checkCancelled(cancelled);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                            RolloutReader.checkCancelled(cancelled);
                            String filename = file.getFileName().toString();
                            if (!filename.endsWith(".jsonl") && !filename.endsWith(".jsonl.zst")) {
                                return FileVisitResult.CONTINUE;
                            }
                            try {
                                Path real = file.toRealPath();
                                if (visited.add(real)) {
                                    progress.accept("Reading " + real.getFileName());
                                    Conversation conversation = inspect(real, indexes, cancelled, warnings);
                                    if (conversation != null) conversations.add(conversation);
                                }
                            } catch (IOException e) {
                                warn(warnings, file + ": " + e.getMessage());
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exception) {
                            warn(warnings, file + ": " + exception.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    warn(warnings, scanRoot + ": " + e.getMessage());
                }
            }
        }
        conversations.sort(Comparator.comparing(Conversation::startedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Conversation::title));
        return new ScanResult(conversations, warnings);
    }

    private Conversation inspect(Path file, Map<Path, Map<String, String>> indexes,
                                 BooleanSupplier cancelled, List<String> warnings) throws IOException {
        var summary = new Summary();
        reader.read(file, Files.size(file), cancelled, record -> {
            if (record.error() != null) {
                warn(warnings, file.getFileName() + ": line " + record.lineNumber() + ": " + record.error());
                return;
            }
            if (summary.started == null) summary.started = record.timestamp();
            var data = record.data();
            if (LogContent.string(data, "type").equals("session_meta")) {
                summary.recognized = true;
                var payload = LogContent.object(data, "payload");
                summary.id = LogContent.string(payload, "id");
                if (summary.id.isBlank()) summary.id = LogContent.string(payload, "session_id");
                summary.project = LogContent.string(payload, "cwd");
                Instant time = RolloutReader.parseTime(LogContent.string(payload, "timestamp"));
                if (time != null) summary.started = time;
            }
            var message = LogContent.message(data);
            if (message != null) {
                summary.recognized = true;
                if (message.role().equals("user") && LogContent.isConversation(message)
                        && summary.prompts[message.priority()] == null) {
                    summary.prompts[message.priority()] = message.text().replaceAll("\\s+", " ").strip();
                }
            }
        });
        if (!summary.recognized) return null;
        if (summary.id.isBlank()) summary.id = file.getFileName().toString().replaceFirst("\\.jsonl(?:\\.zst)?$", "");
        String title = "";
        for (Path ancestor = file.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            Path index = ancestor.resolve("session_index.jsonl");
            if (Files.isRegularFile(index)) {
                Map<String, String> names = indexes.get(index);
                if (names == null) {
                    names = readIndex(index, cancelled, warnings);
                    indexes.put(index, names);
                }
                title = names.getOrDefault(summary.id, "");
                break;
            }
        }
        if (title.isBlank()) {
            for (String prompt : summary.prompts) {
                if (prompt != null && !prompt.isBlank()) {
                    title = prompt.length() > 120 ? prompt.substring(0, 117) + "…" : prompt;
                    break;
                }
            }
        }
        if (title.isBlank()) title = summary.id;
        boolean archived = false;
        for (Path segment : file) {
            if (segment.toString().equals("archived_sessions")) {
                archived = true;
                break;
            }
        }
        return new Conversation(file, summary.id, title, summary.started, summary.project, archived);
    }

    private Map<String, String> readIndex(Path index, BooleanSupplier cancelled, List<String> warnings) {
        Map<String, String> names = new HashMap<>();
        try {
            reader.read(index, Files.size(index), cancelled, record -> {
                if (record.error() != null) return;
                String id = LogContent.string(record.data(), "id");
                String name = LogContent.string(record.data(), "thread_name");
                if (!id.isBlank() && !name.isBlank()) names.put(id, name);
            });
        } catch (IOException e) {
            warn(warnings, index + ": " + e.getMessage());
        }
        return names;
    }

    private static void warn(List<String> warnings, String warning) {
        if (warnings.size() < 100) warnings.add(warning);
        else if (warnings.size() == 100) warnings.add("Further scan warnings omitted.");
    }

    private static final class Summary {
        String id = "";
        String project = "";
        Instant started;
        boolean recognized;
        final String[] prompts = new String[3];
    }
}
