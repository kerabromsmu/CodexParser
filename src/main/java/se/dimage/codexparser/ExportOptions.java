package se.dimage.codexparser;

import java.time.ZoneId;
import java.util.Objects;

public record ExportOptions(Mode mode, ZoneId zone) {
    public ExportOptions {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(zone);
    }

    public enum Mode {
        CONVERSATION("Conversation only"), EVERYTHING("Everything");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
