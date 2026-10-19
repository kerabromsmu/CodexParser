package se.dimage.codexparser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ExportService {
    private final MarkdownExporter exporter = new MarkdownExporter();

    public record Exported(Conversation conversation, Path path, MarkdownExporter.RenderResult details) {
    }

    public record Failure(Path source, String message) {
    }

    public record BatchResult(List<Exported> exported, List<Failure> failures, boolean cancelled) {
        public BatchResult {
            exported = List.copyOf(exported);
            failures = List.copyOf(failures);
        }
    }

    public BatchResult exportBatch(List<Conversation> conversations, Path directory, ExportOptions options,
                                   BooleanSupplier cancelled, Consumer<String> progress) {
        return export(conversations, directory, null, options, cancelled, progress);
    }

    public BatchResult exportSingle(Conversation conversation, Path requestedFile, ExportOptions options,
                                    BooleanSupplier cancelled, Consumer<String> progress) {
        Path absolute = requestedFile.toAbsolutePath();
        return export(List.of(conversation), absolute.getParent(), absolute.getFileName().toString(),
                options, cancelled, progress);
    }

    private BatchResult export(List<Conversation> conversations, Path directory, String singleFilename,
                               ExportOptions options, BooleanSupplier cancelled, Consumer<String> progress) {
        var completed = new ArrayList<Exported>();
        var failures = new ArrayList<Failure>();
        int index = 0;
        boolean wasCancelled = false;
        for (Conversation conversation : conversations) {
            Path temporary = null;
            try {
                RolloutReader.checkCancelled(cancelled);
                progress.accept("Exporting " + (++index) + " of " + conversations.size() + ": " + conversation.title());
                Files.createDirectories(directory);
                String filename = singleFilename == null ? filename(conversation, options) : markdownName(singleFilename);
                temporary = Files.createTempFile(directory, ".codex-export-", ".tmp");
                MarkdownExporter.RenderResult details;
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    details = exporter.render(conversation, options, writer, cancelled);
                }
                int suffix = 1;
                Path destination;
                while (true) {
                    RolloutReader.checkCancelled(cancelled);
                    destination = directory.resolve(withSuffix(filename, suffix++));
                    try {
                        // No REPLACE_EXISTING or ATOMIC_MOVE: both can replace existing files on some providers.
                        Files.move(temporary, destination);
                        temporary = null;
                        break;
                    } catch (FileAlreadyExistsException ignored) {
                        // Another export may have claimed this name after the save dialog opened.
                    }
                }
                completed.add(new Exported(conversation, destination.toAbsolutePath(), details));
            } catch (CancellationException e) {
                wasCancelled = true;
            } catch (IOException | RuntimeException e) {
                failures.add(new Failure(conversation.source(), e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        failures.add(new Failure(temporary, "Could not remove temporary file: " + e.getMessage()));
                    }
                }
            }
            if (wasCancelled) break;
        }
        return new BatchResult(completed, failures, wasCancelled);
    }

    public static String filename(Conversation conversation, ExportOptions options) {
        String date = conversation.startedAt() == null ? "unknown-date"
                : DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss").format(conversation.startedAt().atZone(options.zone()));
        return date + "_" + safePart(conversation.title(), 70) + "_" + safePart(conversation.id(), 40) + ".md";
    }

    private static String markdownName(String filename) {
        String stem = filename.toLowerCase(Locale.ROOT).endsWith(".md")
                ? filename.substring(0, filename.length() - 3) : filename;
        return safePart(stem, 160) + ".md";
    }

    private static String safePart(String value, int maxCodePoints) {
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "-")
                .replaceAll("\\s+", "-").replaceAll("-+", "-").replaceAll("^[. -]+|[. -]+$", "");
        // Keep filenames portable even when a title consists entirely of non-ASCII characters.
        int utf8Bytes = 0;
        int end = 0;
        int points = 0;
        while (end < result.length() && points < maxCodePoints) {
            int cp = result.codePointAt(end);
            int bytes = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (utf8Bytes + bytes > maxCodePoints) break;
            utf8Bytes += bytes;
            end += Character.charCount(cp);
            points++;
        }
        result = result.substring(0, end).replaceAll("[. -]+$", "");
        if (result.isBlank()) result = "conversation";
        if (result.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) result = "_" + result;
        return result;
    }

    private static String withSuffix(String filename, int suffix) {
        return suffix == 1 ? filename : filename.substring(0, filename.length() - 3) + " (" + suffix + ").md";
    }
}
