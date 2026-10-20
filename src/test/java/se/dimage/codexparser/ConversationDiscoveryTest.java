/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

class ConversationDiscoveryTest {
    @TempDir
    Path temporary;
    private final ConversationDiscovery discovery = new ConversationDiscovery();

    @Test
    void discoversNestedAndArchivedLogsUsesLatestIndexTitleAndDeduplicatesOverlappingRoots() throws Exception {
        Path home = temporary.resolve("codex");
        Path sessions = home.resolve("sessions/2026/10/19");
        Path first = file(sessions, "rollout-first.jsonl", metadata("one"), message("user", "First prompt"));
        file(home.resolve("archived_sessions"), "rollout-second.jsonl", metadata("two"), message("user", "Archived prompt"));
        Files.writeString(home.resolve("session_index.jsonl"), """
                {"id":"one","thread_name":"Old title"}
                {"id":"one","thread_name":"Renamed conversation"}
                """);
        file(home, "history.jsonl", "{\"display\":\"not a conversation\"}");
        var result = discovery.scan(List.of(home, sessions, first), () -> false, ignored -> {
        });
        assertEquals(2, result.conversations().size());
        assertTrue(result.warnings().isEmpty());
        var indexed = result.conversations().stream().filter(c -> c.id().equals("one")).findFirst().orElseThrow();
        assertEquals("Renamed conversation", indexed.title());
        assertEquals(first.toRealPath(), indexed.source());
        assertEquals("/projects/example", indexed.project());
        var archived = result.conversations().stream().filter(Conversation::archived).findFirst().orElseThrow();
        assertEquals("Archived prompt", archived.title());
    }

    @Test
    void supportsCustomDirectoryMissingRootsAndFallbackTitle() throws Exception {
        Path path = file(temporary, "custom.jsonl", message("user", "A useful prompt"));
        var result = discovery.scan(List.of(temporary.resolve("missing"), temporary), () -> false, ignored -> {
        });
        assertEquals(1, result.conversations().size());
        assertEquals("custom", result.conversations().getFirst().id());
        assertEquals("A useful prompt", result.conversations().getFirst().title());
        assertEquals(path.toRealPath(), result.conversations().getFirst().source());
    }

    @Test
    void ignoresScaffoldingForTitleAndPrefersCanonicalOverDuplicateEvent() throws Exception {
        file(temporary, "log.jsonl", metadata("one"), message("user", "<environment_context>machine info</environment_context>"),
                completed("user", "Event version"), message("user", "Canonical prompt"));
        var result = discovery.scan(List.of(temporary), () -> false, ignored -> {
        });
        assertEquals("Canonical prompt", result.conversations().getFirst().title());
    }

    @Test
    void metadataOnlyConversationsUseIdAndMalformedFilesDoNotBlockOtherLogs() throws Exception {
        file(temporary, "good.jsonl", metadata("empty-session"), "{broken");
        file(temporary, "bad.jsonl", "[not JSON");
        var result = discovery.scan(List.of(temporary), () -> false, ignored -> {
        });
        assertEquals("empty-session", result.conversations().getFirst().title());
        assertEquals(2, result.warnings().size());
    }

    @Test
    void defaultsIncludeConfiguredHomeAndUserHome() {
        assertEquals(List.of(Path.of("/custom"), Path.of("/example/.codex")),
                ConversationDiscovery.defaultRoots(Map.of("CODEX_HOME", "/custom"), Path.of("/example")));
        assertEquals(List.of(Path.of("/example/.codex")),
                ConversationDiscovery.defaultRoots(Map.of(), Path.of("/example")));
    }

    @Test
    void cancellationStopsScanning() {
        assertThrows(CancellationException.class,
                () -> discovery.scan(List.of(temporary), () -> true, ignored -> {
                }));
    }

    @Test
    void sortsNewestFirstWithUnknownTimestampsLast() throws Exception {
        file(temporary, "old.jsonl", metadata("old").replace(TIME, "2025-01-01T00:00:00Z"));
        file(temporary, "new.jsonl", metadata("new"));
        file(temporary, "unknown.jsonl", metadata("unknown").replace(TIME, "invalid"));
        var result = discovery.scan(List.of(temporary), () -> false, ignored -> {
        });
        assertEquals(List.of("new", "old", "unknown"), result.conversations().stream().map(Conversation::id).toList());
    }

    @Test
    void discoversCompressedJulyThroughSeptemberAlongsideOctoberAndArchives() throws Exception {
        Path home = temporary.resolve("codex");
        Path july = compressedFile(home.resolve("sessions/2026/07/17"), "july.jsonl.zst",
                metadata("july").replace(TIME, "2026-07-17T18:41:47Z"), message("user", "July prompt"));
        compressedFile(home.resolve("sessions/2026/08/04"), "august.jsonl.zst",
                metadata("august").replace(TIME, "2026-08-04T20:32:52Z"), message("user", "August prompt"));
        compressedFile(home.resolve("sessions/2026/09/01"), "september.jsonl.zst",
                metadata("september").replace(TIME, "2026-09-01T10:00:00Z"), message("user", "September prompt"));
        file(home.resolve("sessions/2026/10/19"), "october.jsonl", metadata("october"), message("user", "October prompt"));
        compressedFile(home.resolve("archived_sessions"), "archived.jsonl.zst", metadata("archived"), message("user", "Archived prompt"));
        Files.writeString(home.resolve("session_index.jsonl"), "{\"id\":\"july\",\"thread_name\":\"July conversation\"}\n");

        var result = discovery.scan(List.of(home, july.getParent(), july), () -> false, ignored -> { });
        assertEquals(5, result.conversations().size());
        assertTrue(result.warnings().isEmpty());
        var oldest = result.conversations().getLast();
        assertEquals("july", oldest.id());
        assertEquals("July conversation", oldest.title());
        assertEquals(java.time.Instant.parse("2026-07-17T18:41:47Z"), oldest.startedAt());
        assertEquals("/projects/example", oldest.project());
        assertEquals(july.toRealPath(), oldest.source());
        var archived = result.conversations().stream().filter(Conversation::archived).findFirst().orElseThrow();
        assertEquals("archived", archived.id());
    }

    @Test
    void compressedCustomLogsUsePromptTitleAndRemoveBothFilenameExtensions() throws Exception {
        Path source = compressedFile(temporary, "old-session.jsonl.zst", message("user", "An older prompt"));
        var result = discovery.scan(List.of(temporary), () -> false, ignored -> { });
        assertEquals(1, result.conversations().size());
        assertEquals("old-session", result.conversations().getFirst().id());
        assertEquals("An older prompt", result.conversations().getFirst().title());
        assertEquals(source.toRealPath(), result.conversations().getFirst().source());
    }

    @Test
    void corruptAndTruncatedCompressedLogsWarnWithoutBlockingValidLogs() throws Exception {
        Files.writeString(temporary.resolve("corrupt.jsonl.zst"), "not a Zstandard stream");
        Path truncated = compressedFile(temporary, "truncated.jsonl.zst", metadata("truncated"), message("user", "Prompt"));
        byte[] bytes = Files.readAllBytes(truncated);
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        compressedFile(temporary, "valid.jsonl.zst", metadata("valid"), message("user", "Valid conversation"));

        var result = discovery.scan(List.of(temporary), () -> false, ignored -> { });
        assertEquals(1, result.conversations().size());
        assertEquals("valid", result.conversations().getFirst().id());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("corrupt.jsonl.zst")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("truncated.jsonl.zst")));
    }
}
