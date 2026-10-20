/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

class RolloutReaderTest {
    @TempDir
    Path temporary;

    @Test
    void snapshotExcludesAppendedRecords() throws Exception {
        Path file = file(temporary, "log.jsonl", message("user", "First"));
        long size = Files.size(file);
        Files.writeString(file, message("assistant", "Appended") + "\n", java.nio.file.StandardOpenOption.APPEND);
        var records = new ArrayList<RolloutReader.LogRecord>();
        new RolloutReader().read(file, size, () -> false, records::add);
        assertEquals(1, records.size());
        assertTrue(records.getFirst().raw().contains("First"));
    }

    @Test
    void handlesByteOrderMarkAndPreservesLineNumbersThroughInvalidRecords() throws Exception {
        Path file = file(temporary, "log.jsonl", "\uFEFF" + metadata("one"), "broken", message("user", "Hej"));
        var records = new ArrayList<RolloutReader.LogRecord>();
        new RolloutReader().read(file, Files.size(file), () -> false, records::add);
        assertNull(records.getFirst().error());
        assertEquals(2, records.get(1).lineNumber());
        assertNotNull(records.get(1).error());
        assertEquals(3, records.getLast().lineNumber());
        assertNull(records.getLast().error());
    }

    @Test
    void plainAndCompressedSourcesProduceIdenticalRecordsIncludingMalformedLines() throws Exception {
        String[] lines = {metadata("old"), message("user", "Hej världen 🌍"), "{broken", "", message("assistant", "Answer")};
        Path plain = file(temporary, "plain.jsonl", lines);
        Path compressed = compressedFile(temporary, "compressed.jsonl.zst", lines);
        var reader = new RolloutReader();
        var plainRecords = new ArrayList<RolloutReader.LogRecord>();
        var compressedRecords = new ArrayList<RolloutReader.LogRecord>();
        reader.read(plain, Files.size(plain), () -> false, plainRecords::add);
        reader.read(compressed, Files.size(compressed), () -> false, compressedRecords::add);
        assertEquals(plainRecords, compressedRecords);
        assertEquals(lines.length, compressedRecords.size());
    }

    @Test
    void compressedSnapshotLimitsSourceBytesAndExcludesAppendedFrames() throws Exception {
        Path first = compressedFile(temporary, "log.jsonl.zst", message("user", "First frame"));
        long snapshot = Files.size(first);
        Path second = compressedFile(temporary, "second.jsonl.zst", message("assistant", "Second frame"));
        Files.write(first, Files.readAllBytes(second), java.nio.file.StandardOpenOption.APPEND);
        var records = new ArrayList<RolloutReader.LogRecord>();
        var reader = new RolloutReader();
        reader.read(first, snapshot, () -> false, records::add);
        assertEquals(1, records.size());
        assertTrue(records.getFirst().raw().contains("First frame"));
        records.clear();
        reader.read(first, Files.size(first), () -> false, records::add);
        assertEquals(2, records.size());
        assertTrue(records.getLast().raw().contains("Second frame"));
    }

    @Test
    void corruptAndTruncatedStreamsRaiseIoErrors() throws Exception {
        Path corrupt = Files.writeString(temporary.resolve("corrupt.jsonl.zst"), "not compressed");
        Path valid = compressedFile(temporary, "valid.jsonl.zst", message("user", "Prompt"));
        var reader = new RolloutReader();
        assertThrows(IOException.class, () -> reader.read(corrupt, Files.size(corrupt), () -> false, ignored -> { }));
        assertThrows(IOException.class, () -> reader.read(valid, Files.size(valid) - 1, () -> false, ignored -> { }));
    }

    @Test
    void cancellationStopsBetweenCompressedRecords() throws Exception {
        Path source = compressedFile(temporary, "log.jsonl.zst", metadata("one"), message("user", "Prompt"), message("assistant", "Answer"));
        var cancelled = new AtomicBoolean();
        var records = new ArrayList<RolloutReader.LogRecord>();
        assertThrows(CancellationException.class, () -> new RolloutReader().read(source, Files.size(source), cancelled::get, record -> {
            records.add(record);
            cancelled.set(true);
        }));
        assertEquals(1, records.size());
    }
}
