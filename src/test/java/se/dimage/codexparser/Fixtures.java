package se.dimage.codexparser;

import com.google.gson.Gson;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class Fixtures {
    static final String TIME = "2026-10-19T10:20:30.123Z";
    private static final Gson JSON = new Gson();

    private Fixtures() {
    }

    static String record(String type, Object payload) {
        return JSON.toJson(Map.of("timestamp", TIME, "type", type, "payload", payload));
    }

    static String metadata(String id) {
        return record("session_meta", Map.of("id", id, "timestamp", TIME, "cwd", "/projects/example"));
    }

    static String message(String role, String text) {
        return message(role, text, "id-" + role + "-" + text.hashCode());
    }

    static String message(String role, String text, String id) {
        return record("response_item", Map.of("type", "message", "role", role, "id", id,
                "content", List.of(Map.of("type", role.equals("assistant") ? "output_text" : "input_text", "text", text))));
    }

    static String completed(String role, String text) {
        return record("event_msg", Map.of("type", "item_completed", "item", Map.of(
                "type", role.equals("user") ? "UserMessage" : "AgentMessage", "id", "completed-" + role,
                "content", List.of(Map.of("type", "Text", "text", text)))));
    }

    static Path file(Path directory, String filename, String... records) throws IOException {
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(filename), String.join("\n", records) + "\n");
    }

    static Path compressedFile(Path directory, String filename, String... records) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve(filename);
        try (var output = new ZstdOutputStreamNoFinalizer(Files.newOutputStream(path))) {
            output.write((String.join("\n", records) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    static Conversation conversation(Path file) {
        return new Conversation(file, "session-1", "Example conversation", Instant.parse(TIME), "/projects/example", false);
    }
}
