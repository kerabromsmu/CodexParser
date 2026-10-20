/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

class ExportServiceTest {
    @TempDir
    Path temporary;
    private final ExportService service = new ExportService();
    private final ExportOptions options = new ExportOptions(ExportOptions.Mode.CONVERSATION, ZoneId.of("UTC"));

    @Test
    void batchProducesSeparateUtf8FilesAndKeepsExistingExports() throws Exception {
        var first = conversation(file(temporary, "first.jsonl", message("user", "Hej världen 🌍")));
        var second = conversation(file(temporary, "second.jsonl", message("assistant", "Second conversation")));
        Path output = temporary.resolve("exports");
        Files.createDirectories(output);
        Path existing = output.resolve(ExportService.filename(first, options));
        Files.writeString(existing, "original");
        var result = service.exportBatch(List.of(first, second), output, options, () -> false, ignored -> {
        });
        assertFalse(result.cancelled());
        assertTrue(result.failures().isEmpty());
        assertEquals(2, result.exported().size());
        assertEquals("original", Files.readString(existing));
        assertTrue(Files.readString(result.exported().getFirst().path()).contains("Hej världen 🌍"));
        assertNotEquals(result.exported().getFirst().path(), result.exported().getLast().path());
        try (var files = Files.list(output)) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void failedSourceDoesNotBlockOtherConversations() throws Exception {
        var missing = conversation(temporary.resolve("missing.jsonl"));
        var valid = conversation(file(temporary, "good.jsonl", message("user", "Valid")));
        var result = service.exportBatch(List.of(missing, valid), temporary.resolve("out"), options, () -> false, ignored -> {
        });
        assertEquals(1, result.failures().size());
        assertEquals(1, result.exported().size());
        try (var files = Files.list(temporary.resolve("out"))) {
            assertTrue(files.allMatch(p -> p.toString().endsWith(".md")));
        }
    }

    @Test
    void cancellationDuringRenderingRemovesPartialFile() throws Exception {
        var valid = conversation(file(temporary, "large.jsonl", metadata("one"), message("user", "Prompt"), message("assistant", "Answer")));
        var calls = new AtomicInteger();
        var result = service.exportBatch(List.of(valid), temporary.resolve("out"), options,
                () -> calls.incrementAndGet() >= 5, ignored -> {
                });
        assertTrue(result.cancelled());
        assertTrue(result.exported().isEmpty());
        try (var files = Files.list(temporary.resolve("out"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cancellingBatchPreservesCompletedFilesAndReportsThem() throws Exception {
        var valid = conversation(file(temporary, "good.jsonl", message("user", "Hello")));
        Path output = temporary.resolve("out");
        var result = service.exportBatch(List.of(valid, valid), output, options,
                () -> Files.isDirectory(output) && hasMarkdown(output), ignored -> {
                });
        assertTrue(result.cancelled());
        assertEquals(1, result.exported().size());
        assertTrue(Files.exists(result.exported().getFirst().path()));
    }

    @Test
    void singleExportAddsExtensionAndSanitizesReservedNames() throws Exception {
        var valid = conversation(file(temporary, "good.jsonl", message("user", "Hello")));
        var result = service.exportSingle(valid, temporary.resolve("CON"), options, () -> false, ignored -> {
        });
        assertEquals("_CON.md", result.exported().getFirst().path().getFileName().toString());
    }

    @Test
    void filenamesArePortableAndBoundedForUnicodeTitles() {
        var unusual = new Conversation(temporary.resolve("log.jsonl"), "id:bad?*", "漢字🌍".repeat(100) + ":/\\?", null, "", false);
        String filename = ExportService.filename(unusual, options);
        assertTrue(filename.endsWith(".md"));
        assertTrue(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 200);
        assertFalse(filename.matches(".*[<>:\"/\\\\|?*].*"));
        assertTrue(filename.startsWith("unknown-date_"));
    }

    @Test
    void compressedFailureDoesNotBlockMixedBatchAndSourcesRemainUntouched() throws Exception {
        var corrupt = conversation(Files.writeString(temporary.resolve("corrupt.jsonl.zst"), "not compressed"));
        var compressed = conversation(compressedFile(temporary, "old.jsonl.zst", metadata("old"), message("user", "July prompt")));
        var plain = conversation(file(temporary, "recent.jsonl", message("assistant", "October answer")));
        byte[] original = Files.readAllBytes(compressed.source());
        Path output = temporary.resolve("mixed-output");
        var full = new ExportOptions(ExportOptions.Mode.EVERYTHING, ZoneId.of("UTC"));
        var result = service.exportBatch(List.of(corrupt, compressed, plain), output, full, () -> false, ignored -> { });
        assertEquals(1, result.failures().size());
        assertEquals(corrupt.source(), result.failures().getFirst().source());
        assertEquals(2, result.exported().size());
        String markdown = Files.readString(result.exported().getFirst().path());
        assertTrue(markdown.contains("July prompt"));
        assertTrue(markdown.contains("Original JSON record"));
        assertArrayEquals(original, Files.readAllBytes(compressed.source()));
        try (var paths = Files.list(output)) {
            assertEquals(2, paths.filter(path -> path.toString().endsWith(".md")).count());
        }
        try (var paths = Files.list(output)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    private static boolean hasMarkdown(Path directory) {
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path -> path.toString().endsWith(".md"));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
