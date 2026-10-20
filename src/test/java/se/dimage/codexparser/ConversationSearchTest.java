package se.dimage.codexparser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

class ConversationSearchTest {
    @TempDir
    Path temporary;
    private final ConversationSearch search = new ConversationSearch();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void searchesPlainAndCompressedMessagesWithoutChangingSource(boolean compressed) throws Exception {
        String[] records = {metadata("old"), message("user", "Body-only question"), message("assistant", "Body-only response")};
        Path source = compressed ? compressedFile(temporary, "old.jsonl.zst", records) : file(temporary, "old.jsonl", records);
        byte[] original = Files.readAllBytes(source);
        for (String query : List.of("question", "response")) {
            var result = search.search(List.of(conversation(source)), query, () -> false);
            assertEquals(Set.of(source), result.matches());
            assertTrue(result.warnings().isEmpty());
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"needleword", "NEEDLEWORD", "  NeedleWord  "})
    void matchingIsCaseInsensitiveAndTrimsQuery(String query) throws Exception {
        Path source = file(temporary, "log.jsonl", message("assistant", "A NeedleWord in an answer"));
        assertEquals(Set.of(source), search.search(List.of(conversation(source)), query, () -> false).matches());
    }

    @Test
    void metadataMatchesDoNotRequireOpeningTheSource() {
        Path missing = temporary.resolve("metadata-path.jsonl");
        var conversation = new Conversation(missing, "record-42", "Design overview", null, "/projects/example", false);
        for (String query : List.of("design", "record-42", "projects/example", "metadata-path")) {
            var result = search.search(List.of(conversation), query, () -> false);
            assertEquals(Set.of(missing), result.matches());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void emptyQueryReturnsEveryConversationWithoutReadingFiles() {
        Path first = temporary.resolve("missing-first.jsonl");
        Path second = temporary.resolve("missing-second.jsonl.zst");
        var result = search.search(List.of(conversation(first), conversation(second)), "  ", () -> false);
        assertEquals(Set.of(first, second), result.matches());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void punctuationAndCodeAreLiteralSubstrings() throws Exception {
        Path source = file(temporary, "log.jsonl", message("assistant", "```java\nSystem.out.println(\"A+B\");\n```\n[a-z].*"));
        for (String query : List.of("System.out.println(\"A+B\");", "[a-z].*")) {
            assertEquals(Set.of(source), search.search(List.of(conversation(source)), query, () -> false).matches());
        }
        assertTrue(search.search(List.of(conversation(source)), "^System.*", () -> false).matches().isEmpty());
    }

    @Test
    void searchesProgressMessages() throws Exception {
        String progress = record("response_item", Map.of("type", "message", "role", "assistant", "phase", "commentary",
                "content", List.of(Map.of("type", "output_text", "text", "Working on the progressmarker"))));
        Path source = file(temporary, "log.jsonl", progress);
        assertEquals(Set.of(source), search.search(List.of(conversation(source)), "progressmarker", () -> false).matches());
    }

    @Test
    void excludesToolsInstructionsReasoningEncryptedFieldsAndScaffolding() throws Exception {
        Path source = file(temporary, "log.jsonl", metadata("one"),
                message("system", "systemneedle"), message("developer", "developerneedle"),
                message("user", "<environment_context>scaffoldneedle</environment_context>"),
                message("user", "A regular prompt"),
                record("response_item", Map.of("type", "function_call", "arguments", "toolneedle")),
                record("response_item", Map.of("type", "function_call_output", "output", "toolneedle")),
                record("response_item", Map.of("type", "reasoning", "encrypted_content", "cipherneedle",
                        "summary", List.of(Map.of("type", "summary_text", "text", "reasoningneedle")))),
                record("future_record", Map.of("text", "unknownneedle")));
        for (String query : List.of("systemneedle", "developerneedle", "scaffoldneedle", "toolneedle", "cipherneedle", "reasoningneedle", "unknownneedle")) {
            assertTrue(search.search(List.of(conversation(source)), query, () -> false).matches().isEmpty(), query);
        }
    }

    @Test
    void searchesTextBeyondPreviewLimitInCompressedLogs() throws Exception {
        String body = "x".repeat(MarkdownExporter.PREVIEW_LIMIT + 1000) + " tailmarker";
        Path source = compressedFile(temporary, "large.jsonl.zst", message("assistant", body));
        var options = new ExportOptions(ExportOptions.Mode.CONVERSATION, java.time.ZoneId.of("UTC"));
        var preview = new MarkdownExporter().preview(conversation(source), options, () -> false);
        assertTrue(preview.truncated());
        assertFalse(preview.text().contains("tailmarker"));
        assertEquals(Set.of(source), search.search(List.of(conversation(source)), "tailmarker", () -> false).matches());
    }

    @Test
    void ignoresDiscardedMessageRepresentationsAsConversationExportDoes() throws Exception {
        Path source = file(temporary, "log.jsonl", completed("user", "duplicateonlymarker"),
                message("user", "Canonical prompt"),
                record("event_msg", Map.of("type", "agent_message", "message", "legacyonlymarker")),
                message("assistant", "Canonical answer"));
        for (String query : List.of("duplicateonlymarker", "legacyonlymarker")) {
            assertTrue(search.search(List.of(conversation(source)), query, () -> false).matches().isEmpty());
        }
    }

    @Test
    void searchesCompletedAndLegacyFallbackMessages() throws Exception {
        Path source = compressedFile(temporary, "log.jsonl.zst", completed("user", "fallbackquestion"),
                record("event_msg", Map.of("type", "agent_message", "message", "legacyanswer")));
        for (String query : List.of("fallbackquestion", "legacyanswer")) {
            assertEquals(Set.of(source), search.search(List.of(conversation(source)), query, () -> false).matches());
        }
    }

    @Test
    void missingAndCorruptSourcesDoNotBlockOtherConversations() throws Exception {
        Path missing = temporary.resolve("missing.jsonl");
        Path corrupt = Files.writeString(temporary.resolve("corrupt.jsonl.zst"), "not compressed");
        Path plain = file(temporary, "plain.jsonl", message("user", "targetmarker"), "{broken");
        Path compressed = compressedFile(temporary, "old.jsonl.zst", message("assistant", "targetmarker"));
        var result = search.search(List.of(conversation(missing), conversation(corrupt), conversation(plain), conversation(compressed)),
                "targetmarker", () -> false);
        assertEquals(Set.of(plain, compressed), result.matches());
        assertEquals(3, result.warnings().size());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("missing.jsonl")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("corrupt.jsonl.zst")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("1 malformed log line(s)")));
    }

    @Test
    void noMatchesReturnEmptyResults() throws Exception {
        Path source = file(temporary, "log.jsonl", message("user", "A regular prompt"));
        var result = search.search(List.of(conversation(source)), "unfindablemarker", () -> false);
        assertTrue(result.matches().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void cancellationInterruptsCompressedSearch() throws Exception {
        Path source = compressedFile(temporary, "log.jsonl.zst", metadata("one"), message("user", "Prompt"), message("assistant", "Answer"));
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> search.search(List.of(conversation(source)), "absentmarker",
                () -> checks.incrementAndGet() >= 5));
    }

    @Test
    void laterSearchSeesAppendedMessages() throws Exception {
        Path source = file(temporary, "log.jsonl", message("user", "Original prompt"));
        var conversations = List.of(conversation(source));
        assertTrue(search.search(conversations, "newmarker", () -> false).matches().isEmpty());
        Files.writeString(source, message("assistant", "A newmarker appeared") + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(Set.of(source), search.search(conversations, "newmarker", () -> false).matches());
    }
}
