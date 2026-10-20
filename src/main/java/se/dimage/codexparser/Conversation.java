/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import java.nio.file.Path;
import java.time.Instant;

public record Conversation(Path source, String id, String title, Instant startedAt,
                           String project, boolean archived) {
}
