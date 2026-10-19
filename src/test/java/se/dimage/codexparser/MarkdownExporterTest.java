package se.dimage.codexparser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

class MarkdownExporterTest {
    @TempDir
    Path temporary;
    private final MarkdownExporter exporter = new MarkdownExporter();
    private final ExportOptions chat = new ExportOptions(ExportOptions.Mode.CONVERSATION, ZoneId.of("UTC"));
    private final ExportOptions everything = new ExportOptions(ExportOptions.Mode.EVERYTHING, ZoneId.of("UTC"));

    private String render(ExportOptions options, String... records) throws Exception {
        Path source = file(temporary, "log.jsonl", records);
        var writer = new StringWriter();
        exporter.render(conversation(source), options, writer, () -> false);
        return writer.toString();
    }

    @Test
    void chatPreservesMarkdownRolesAndTimestampsWhileExcludingTechnicalRecords() throws Exception {
        String text = "**Hello**\n\n```java\nint count = 1;\n```";
        String markdown = render(chat, metadata("one"), message("developer", "internal instruction"),
                message("user", "Please help"), message("assistant", text),
                record("response_item", java.util.Map.of("type", "function_call", "name", "shell", "arguments", "ls")));
        assertTrue(markdown.contains("2026-10-19 10:20:30.123 +00:00 — User"));
        assertTrue(markdown.contains("— Assistant"));
        assertTrue(markdown.contains(text));
        assertFalse(markdown.contains("internal instruction"));
        assertFalse(markdown.contains("function_call"));
        assertFalse(markdown.contains("Original JSON record"));
    }

    @Test
    void ignoresDuplicateRepresentationsButRetainsRepeatedRealMessages() throws Exception {
        String markdown = render(chat, completed("user", "Repeated prompt"),
                message("user", "Repeated prompt", "one"), message("user", "Repeated prompt", "two"),
                message("assistant", "An answer"), completed("assistant", "An answer"));
        assertEquals(2, occurrences(markdown, "Repeated prompt"));
        assertEquals(1, occurrences(markdown, "An answer"));
    }

    @Test
    void usesCompletedEventsAsFallbackPerRole() throws Exception {
        String markdown = render(chat, completed("user", "Fallback prompt"), message("assistant", "Canonical answer"));
        assertTrue(markdown.contains("Fallback prompt"));
        assertTrue(markdown.contains("Canonical answer"));
    }

    @Test
    void supportsLegacyMessageEventsAsLastFallback() throws Exception {
        String markdown = render(chat,
                record("event_msg", java.util.Map.of("type", "user_message", "message", "Legacy prompt")),
                record("event_msg", java.util.Map.of("type", "agent_message", "message", "Legacy answer")));
        assertTrue(markdown.contains("Legacy prompt"));
        assertTrue(markdown.contains("Legacy answer"));
    }

    @Test
    void filtersStandaloneScaffoldingButPreservesPromptFollowingAnEnvelope() throws Exception {
        String markdown = render(chat,
                message("user", "<environment_context>machine info</environment_context>"),
                message("user", "# AGENTS.md instructions for example\n<INSTRUCTIONS>rules</INSTRUCTIONS>"),
                message("user", "<environment_context>machine info</environment_context>\nPlease implement this"));
        assertFalse(markdown.contains("AGENTS.md"));
        assertEquals(1, occurrences(markdown, "machine info"));
        assertTrue(markdown.contains("Please implement this"));
    }

    @Test
    void everythingPreservesEveryOriginalRecordAndUnknownFields() throws Exception {
        String[] records = {metadata("one"), message("developer", "Instruction"),
                message("user", "Prompt"), completed("user", "Prompt"),
                record("response_item", java.util.Map.of("type", "custom_tool_call", "name", "shell", "input", "echo hi")),
                record("response_item", java.util.Map.of("type", "custom_tool_call_output", "output", "hi")),
                record("future_record", java.util.Map.of("new_field", "preserve me"))};
        String markdown = render(everything, records);
        assertEquals(records.length, occurrences(markdown, "<summary>Original JSON record</summary>"));
        for (String original : records) assertTrue(markdown.contains(original));
        assertTrue(markdown.contains("— Developer"));
        assertTrue(markdown.contains("**input**\n\n```text\necho hi"));
        assertTrue(markdown.contains("**output**\n\n```text\nhi"));
        assertTrue(markdown.contains("future_record"));
    }

    @Test
    void malformedRecordsWarnInChatAndArePreservedInEverything() throws Exception {
        String markdown = render(chat, metadata("one"), "{not valid", message("user", "Still works"));
        assertTrue(markdown.contains("Still works"));
        assertTrue(markdown.contains("1 malformed or blank log line(s) skipped"));
        assertFalse(markdown.contains("{not valid"));
        markdown = render(everything, "{not valid", "null", "[]", "{\"type\":\"x\"} trailing", "");
        assertTrue(markdown.contains("{not valid"));
        assertTrue(markdown.contains("5 malformed or blank log line(s) preserved"));
    }

    @Test
    void attachmentsHavePlaceholdersWithoutEmbeddingBase64InChat() throws Exception {
        String markdown = render(chat, record("response_item", java.util.Map.of("type", "message", "role", "user",
                "content", java.util.List.of(java.util.Map.of("type", "input_text", "text", "Describe this"),
                        java.util.Map.of("type", "input_image", "image_url", "data:image/png;base64,PRIVATE")))));
        assertTrue(markdown.contains("Describe this"));
        assertTrue(markdown.contains("[Image attachment]"));
        assertFalse(markdown.contains("PRIVATE"));
    }

    @Test
    void timezoneIncludesMillisecondsOffsetAndDaylightSaving() {
        assertEquals("2026-10-19 12:20:30.123 +02:00", MarkdownExporter.timestamp(Instant.parse(TIME), ZoneId.of("Europe/Stockholm")));
        assertEquals("2026-12-19 11:20:30.123 +01:00", MarkdownExporter.timestamp(Instant.parse("2026-12-19T10:20:30.123Z"), ZoneId.of("Europe/Stockholm")));
    }

    @Test
    void missingAndInvalidTimestampsAreExplicit() throws Exception {
        String markdown = render(chat, message("user", "Hello").replace(TIME, "invalid"));
        assertTrue(markdown.contains("Unknown timestamp — User"));
        assertTrue(markdown.contains("1 entry timestamp(s) were missing or invalid"));
    }

    @Test
    void safeFencesContainBackticksAndHtmlTagsInToolOutput() throws Exception {
        String text = "```\n</details>\n````";
        String markdown = render(everything, record("response_item", java.util.Map.of("type", "function_call_output", "output", text)));
        assertTrue(markdown.contains("`````text\n" + text + "\n`````"));
    }

    @Test
    void previewIsBoundedAndFullExportIsNot() throws Exception {
        String large = "x".repeat(MarkdownExporter.PREVIEW_LIMIT + 2000) + "END OF MESSAGE";
        Path path = file(temporary, "large.jsonl", message("assistant", large));
        var preview = exporter.preview(conversation(path), chat, () -> false);
        assertTrue(preview.truncated());
        assertTrue(preview.text().length() < MarkdownExporter.PREVIEW_LIMIT + 200);
        assertFalse(preview.text().contains("END OF MESSAGE"));
        var writer = new StringWriter();
        exporter.render(conversation(path), chat, writer, () -> false);
        assertTrue(writer.toString().contains("END OF MESSAGE"));
    }

    @Test
    void cancellationStopsRendering() throws Exception {
        Path source = file(temporary, "log.jsonl", message("user", "Hello"));
        assertThrows(CancellationException.class,
                () -> exporter.render(conversation(source), chat, new StringWriter(), () -> true));
    }

    @Test
    void progressMessagesAreLabeled() throws Exception {
        String record = message("assistant", "Working").replace("\"role\":\"assistant\"", "\"phase\":\"commentary\",\"role\":\"assistant\"");
        assertTrue(render(chat, record).contains("Assistant (progress)"));
    }

    @ParameterizedTest
    @EnumSource(ExportOptions.Mode.class)
    void compressedLogsHaveMatchingTranscriptsAndWorkingPreviews(ExportOptions.Mode mode) throws Exception {
        String[] records = {metadata("july"), message("user", "Older prompt 🌍"), message("assistant", "**Older answer**"),
                record("response_item", java.util.Map.of("type", "function_call_output", "output", "Tool result")), "{broken"};
        Path plain = file(temporary, "plain.jsonl", records);
        Path compressed = compressedFile(temporary, "compressed.jsonl.zst", records);
        var options = new ExportOptions(mode, ZoneId.of("UTC"));
        var plainOutput = new StringWriter();
        var compressedOutput = new StringWriter();
        var plainResult = exporter.render(conversation(plain), options, plainOutput, () -> false);
        var compressedResult = exporter.render(conversation(compressed), options, compressedOutput, () -> false);
        assertEquals(plainResult, compressedResult);
        assertEquals(plainOutput.toString().split("\n---\n\n", 2)[1],
                compressedOutput.toString().split("\n---\n\n", 2)[1]);
        assertTrue(compressedOutput.toString().contains("compressed.jsonl.zst"));
        var preview = exporter.preview(conversation(compressed), options, () -> false);
        assertFalse(preview.truncated());
        assertEquals(compressedOutput.toString(), preview.text());
    }

    @Test
    void compressedPreviewRemainsBoundedWhenDecompressedTextIsLarge() throws Exception {
        String text = "x".repeat(MarkdownExporter.PREVIEW_LIMIT + 2000) + "END OF COMPRESSED MESSAGE";
        Path source = compressedFile(temporary, "large.jsonl.zst", message("assistant", text));
        assertTrue(java.nio.file.Files.size(source) < 2000);
        var preview = exporter.preview(conversation(source), chat, () -> false);
        assertTrue(preview.truncated());
        assertFalse(preview.text().contains("END OF COMPRESSED MESSAGE"));
        var full = new StringWriter();
        exporter.render(conversation(source), chat, full, () -> false);
        assertTrue(full.toString().contains("END OF COMPRESSED MESSAGE"));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
