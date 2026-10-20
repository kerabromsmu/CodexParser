/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Reads a fixed byte snapshot, so a conversation being appended to cannot extend an export.
 */
public final class RolloutReader {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public record LogRecord(long lineNumber, String raw, JsonObject data, String error) {
        public Instant timestamp() {
            return parseTime(LogContent.string(data, "timestamp"));
        }
    }

    @FunctionalInterface
    public interface RecordConsumer {
        void accept(LogRecord record) throws IOException;
    }

    public void read(Path path, long byteLimit, BooleanSupplier cancelled, RecordConsumer consumer)
            throws IOException {
        checkCancelled(cancelled);
        try (var input = new LimitedInputStream(Files.newInputStream(path), byteLimit);
             var decoded = decodedStream(path, input);
             var reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
            String line;
            long number = 0;
            while (true) {
                checkCancelled(cancelled);
                line = reader.readLine();
                if (line == null) break;
                checkCancelled(cancelled);
                number++;
                if (line.isBlank()) {
                    // Blank lines still belong to the original log in Everything mode.
                    consumer.accept(new LogRecord(number, line, null, "Blank log line"));
                    continue;
                }
                if (number == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                JsonObject data;
                try {
                    data = JSON.fromJson(line, JsonObject.class);
                    if (data == null) {
                        throw new JsonParseException("Expected a JSON object");
                    }
                } catch (JsonParseException | IllegalStateException e) {
                    consumer.accept(new LogRecord(number, line, null, "Invalid JSON object"));
                    continue;
                }
                consumer.accept(new LogRecord(number, line, data, null));
            }
        }
        checkCancelled(cancelled);
    }

    private static InputStream decodedStream(Path path, InputStream input) throws IOException {
        // The input is limited in compressed bytes before decoding, preserving snapshot semantics.
        return path.getFileName().toString().endsWith(".jsonl.zst")
                ? new ZstdInputStreamNoFinalizer(input).setContinuous(false) : input;
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    public static Instant parseTime(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int result = super.read();
            if (result >= 0) remaining--;
            return result;
        }

        @Override
        public int read(byte @NotNull [] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int result = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (result > 0) remaining -= result;
            return result;
        }
    }
}
