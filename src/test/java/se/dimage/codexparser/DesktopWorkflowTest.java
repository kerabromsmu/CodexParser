package se.dimage.codexparser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Each scenario owns the SWT display on a fresh JVM's first thread, including on Cocoa. */
@Tag("desktop")
class DesktopWorkflowTest {
    @TempDir
    Path temporary;

    @Test
    void browseSearchSortPreviewTimezoneAndExport() throws Exception {
        runScenario("workflow");
    }

    @Test
    void cancelExportsAndCloseWithPendingCallbacks() throws Exception {
        runScenario("cancellation");
    }

    @Test
    void reportAndWarningDialogsUseSwtControls() throws Exception {
        runScenario("reports");
    }

    private void runScenario(String scenario) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(List.of(System.getProperty("desktop.jvmArgs").split(" ")));
        command.addAll(List.of("-cp", System.getProperty("desktop.classpath"),
                SwtDesktopScenario.class.getName(), scenario, temporary.toString()));
        Path log = temporary.resolve(scenario + ".log");
        try (Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()) {
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "SWT scenario timed out:\n" + read(log));
                assertEquals(0, process.exitValue(), () -> "SWT scenario failed:\n" + read(log));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception error) {
            return error.toString();
        }
    }
}
