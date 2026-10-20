/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class MarkdownExporter {
    public static final int PREVIEW_LIMIT = 200_000;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx");
    private final RolloutReader reader = new RolloutReader();

    public record RenderResult(long entries, long malformedLines, long unknownTimestamps) {
    }

    public record Preview(String text, boolean truncated) {
    }

    public RenderResult render(Conversation conversation, ExportOptions options, Writer output,
                               BooleanSupplier cancelled) throws IOException {
        long snapshot = Files.size(conversation.source());
        Map<String, Integer> priorities = new HashMap<>();
        if (options.mode() == ExportOptions.Mode.CONVERSATION) {
            reader.read(conversation.source(), snapshot, cancelled, record -> {
                var message = LogContent.message(record.data());
                if (LogContent.isConversation(message)) {
                    priorities.merge(message.role(), message.priority(), Math::min);
                }
            });
        }
        output.write("# " + heading(conversation.title()) + "\n\n");
        metadata(output, "Session", conversation.id());
        metadata(output, "Started", timestamp(conversation.startedAt(), options.zone()));
        metadata(output, "Project", conversation.project().isBlank() ? "Unknown" : conversation.project());
        metadata(output, "Source", conversation.source().toString());
        metadata(output, "Export mode", options.mode().toString());
        metadata(output, "Timezone", options.zone().getId());
        output.write("\n---\n\n");
        long[] counts = new long[3];
        reader.read(conversation.source(), snapshot, cancelled, record -> {
            if (record.error() != null) {
                counts[1]++;
                if (options.mode() == ExportOptions.Mode.EVERYTHING) {
                    output.write("## Unknown timestamp — Malformed record (line " + record.lineNumber() + ")\n\n");
                    output.write(record.error() + ". Original line:\n\n");
                    fence(output, record.raw(), "text");
                    counts[0]++;
                }
                return;
            }
            var message = LogContent.message(record.data());
            if (options.mode() == ExportOptions.Mode.CONVERSATION) {
                if (!LogContent.isConversation(message)
                        || message.priority() != priorities.getOrDefault(message.role(), -1)) return;
                output.write("## " + timestamp(record.timestamp(), options.zone()) + " — " + speaker(message) + "\n\n");
                output.write(message.text() + "\n\n");
            } else {
                output.write("## " + timestamp(record.timestamp(), options.zone()) + " — "
                        + label(record.data(), message) + " (line " + record.lineNumber() + ")\n\n");
                readable(output, record.data(), message);
                output.write("<details>\n<summary>Original JSON record</summary>\n\n");
                // Keep the exact original line, including encrypted and unrecognized fields.
                fence(output, record.raw(), "json");
                output.write("</details>\n\n");
            }
            counts[0]++;
            if (record.timestamp() == null) counts[2]++;
        });
        if (counts[0] == 0) output.write("No conversation messages were found for this export mode.\n\n");
        if (counts[1] > 0) output.write("> Warning: " + counts[1] + " malformed or blank log line(s) "
                + (options.mode() == ExportOptions.Mode.EVERYTHING ? "preserved above." : "skipped; use Everything to preserve them.") + "\n\n");
        if (counts[2] > 0) output.write("> Warning: " + counts[2] + " entry timestamp(s) were missing or invalid.\n\n");
        RolloutReader.checkCancelled(cancelled);
        return new RenderResult(counts[0], counts[1], counts[2]);
    }

    public Preview preview(Conversation conversation, ExportOptions options, BooleanSupplier cancelled)
            throws IOException {
        var output = new PreviewWriter(PREVIEW_LIMIT);
        try {
            render(conversation, options, output, cancelled);
            return new Preview(output.toString(), false);
        } catch (PreviewFull e) {
            return new Preview(output + "\n\n[Preview limited to " + PREVIEW_LIMIT
                    + " characters. Export includes the complete transcript.]\n", true);
        }
    }

    public static String timestamp(Instant time, ZoneId zone) {
        return time == null ? "Unknown timestamp" : TIMESTAMP.format(time.atZone(zone));
    }

    private static String speaker(LogContent.Message message) {
        String role = message.role().isBlank() ? "Unknown speaker" : message.role();
        String label = role.substring(0, 1).toUpperCase(Locale.ROOT) + role.substring(1);
        if (message.phase().equals("commentary")) label += " (progress)";
        return heading(label);
    }

    private static String label(JsonObject data, LogContent.Message message) {
        if (message != null) return speaker(message);
        var payload = LogContent.object(data, "payload");
        String outer = LogContent.string(data, "type");
        String inner = LogContent.string(payload, "type");
        String name = LogContent.string(payload, "name");
        if (inner.equals("item_completed")) inner = LogContent.string(LogContent.object(payload, "item"), "type");
        return heading((outer.isBlank() ? "Unknown record" : outer)
                + (inner.isBlank() ? "" : " / " + inner) + (name.isBlank() ? "" : " — " + name));
    }

    private static void readable(Writer output, JsonObject data, LogContent.Message message) throws IOException {
        if (message != null) {
            output.write(message.text() + "\n\n");
            return;
        }
        JsonObject payload = LogContent.object(data, "payload");
        String type = LogContent.string(payload, "type");
        if (type.equals("item_completed")) payload = LogContent.object(payload, "item");
        for (String field : new String[]{"command", "arguments", "input", "output", "aggregated_output",
                "stdout", "stderr", "text", "message", "result"}) {
            String text = LogContent.value(payload, field);
            if (!text.isBlank()) {
                output.write("**" + field + "**\n\n");
                fence(output, text, "text");
            }
        }
    }

    private static void metadata(Writer output, String label, String value) throws IOException {
        output.write("- **" + label + ":** " + escape(value.replaceAll("\\s+", " ")) + "\n");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*")
                .replace("_", "\\_").replace("[", "\\[").replace("]", "\\]");
    }

    private static String heading(String value) {
        return escape(value.replaceAll("\\s+", " ").strip()).replace("#", "\\#");
    }

    static void fence(Writer output, String text, String language) throws IOException {
        int maxRun = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            maxRun = Math.max(maxRun, run);
        }
        String marker = "`".repeat(Math.max(3, maxRun + 1));
        output.write(marker + language + "\n");
        output.write(text);
        output.write("\n" + marker + "\n\n");
    }

    private static final class PreviewFull extends IOException {
    }

    private static final class PreviewWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;

        PreviewWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char @NotNull [] chars, int offset, int length) throws IOException {
            int take = Math.min(length, limit - text.length());
            text.append(chars, offset, take);
            if (take < length) throw new PreviewFull();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
