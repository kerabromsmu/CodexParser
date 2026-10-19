package se.dimage.codexparser;

import java.nio.file.Path;
import java.time.Instant;

public record Conversation(Path source, String id, String title, Instant startedAt,
                           String project, boolean archived) {
}
