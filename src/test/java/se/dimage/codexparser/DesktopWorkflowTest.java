package se.dimage.codexparser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

/**
 * Uses real Swing windows and file dialogs with synthetic logs, including the asynchronous export path.
 */
@Tag("desktop")
class DesktopWorkflowTest {
    @TempDir
    Path temporary;
    private CodexParserApp app;

    @AfterEach
    void closeWindows() throws Exception {
        onEdt(() -> {
            for (Window window : Window.getWindows()) window.dispose();
            return null;
        });
    }

    @Test
    void browseFilterPreviewTimezoneAndExportUsingDesktopDialogs() throws Exception {
        Path logs = temporary.resolve("logs");
        compressedFile(logs, "alpha.jsonl.zst", metadata("alpha"), message("user", "Alpha conversation"), message("assistant", "Alpha answer: foxtrot-body-only"));
        file(logs, "beta.jsonl", metadata("beta"), message("user", "Beta conversation"), message("assistant", "Beta answer: lima-body-only"));
        Path output = Files.createDirectories(temporary.resolve("exports"));
        app = onEdt(() -> {
            var window = new CodexParserApp(List.of(logs));
            window.setVisible(true);
            button(window, "Refresh").doClick();
            return window;
        });
        JTable table = onEdt(() -> requireComponent(app, JTable.class, _ -> true));
        JTextArea preview = onEdt(() -> requireComponent(app, JTextArea.class, c -> "preview".equals(c.getName())));
        JTextField search = onEdt(() -> requireComponent(app, JTextField.class, c -> "search".equals(c.getName())));
        JLabel searchStatus = onEdt(() -> requireComponent(app, JLabel.class, c -> "searchStatus".equals(c.getName())));
        await(() -> table.getRowCount() == 2 && button(app, "Refresh").isEnabled());
        assertFalse(onEdt(() -> button(app, "Export selected…").isEnabled()));

        onEdt(() -> {
            search.setText("Alpha");
            return null;
        });
        await(() -> table.getRowCount() == 1);
        onEdt(() -> {
            table.setRowSelectionInterval(0, 0);
            return null;
        });
        await(() -> preview.getText().contains("Alpha answer"));
        assertFalse(onEdt(() -> preview.getText().contains("Original JSON record")));
        assertTrue(onEdt(() -> button(app, "Export selected…").isEnabled()));

        onEdt(() -> { search.setText("foxtrot-body-only"); return null; });
        await(() -> searchStatus.getText().startsWith("Search complete") && table.getRowCount() == 1);
        assertEquals("Alpha conversation", onEdt(() -> table.getValueAt(0, 0)));
        onEdt(() -> { table.setRowSelectionInterval(0, 0); return null; });
        await(() -> preview.getText().contains("foxtrot-body-only"));

        onEdt(() -> {
            search.setText("lima-body-only");
            search.setText("absent-body-marker");
            search.setText("foxtrot-body-only");
            return null;
        });
        await(() -> searchStatus.getText().startsWith("Search complete") && table.getRowCount() == 1);
        assertEquals("Alpha conversation", onEdt(() -> table.getValueAt(0, 0)));
        onEdt(() -> { table.setRowSelectionInterval(0, 0); return null; });

        onEdt(() -> {
            JComboBox<?> mode = requireComponent(app, JComboBox.class,
                    c -> c.getItemCount() == 2 && c.getItemAt(0) == ExportOptions.Mode.CONVERSATION);
            mode.setSelectedIndex(1);
            JComboBox<?> timezone = requireComponent(app, JComboBox.class,
                    c -> c.getItemCount() == 2 && c.getItemAt(1).equals("UTC"));
            timezone.setSelectedIndex(1);
            return null;
        });
        await(() -> preview.getText().contains("Original JSON record") && preview.getText().contains("+00:00"));
        assertEquals(1, onEdt(table::getSelectedRowCount));

        onEdt(() -> {
            search.setText("");
            table.setRowSelectionInterval(0, 1);
            return null;
        });
        await(() -> preview.getText().contains("Beta answer"));
        // Save a reviewable screenshot of the synthetic-data UI, never personal conversation content.
        var screenshot = onEdt(() -> {
            var image = new BufferedImage(app.getWidth(), app.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                app.paint(graphics);
            } finally {
                graphics.dispose();
            }
            return image;
        });
        Files.createDirectories(Path.of("build"));
        ImageIO.write(screenshot, "png", Path.of("build/desktop-smoke.png").toFile());

        SwingUtilities.invokeLater(() -> button(app, "Export selected…").doClick());
        await(() -> showingChooser() != null);
        onEdt(() -> {
            var chooser = showingChooser();
            assertNotNull(chooser);
            assertEquals(JFileChooser.DIRECTORIES_ONLY, chooser.getFileSelectionMode());
            chooser.setSelectedFile(output.toFile());
            chooser.approveSelection();
            return null;
        });
        await(() -> reportDialog() != null);
        onEdt(() -> {
            Dialog dialog = requireReportDialog();
            JTextArea report = requireComponent(dialog, JTextArea.class, _ -> true);
            assertTrue(report.getText().contains("2 file(s) saved; 0 failure(s)"));
            dialog.dispose();
            return null;
        });
        try (var paths = Files.list(output)) {
            List<Path> exported = paths.toList();
            assertEquals(2, exported.size());
            for (Path path : exported) {
                String content = Files.readString(path);
                assertTrue(content.contains("Original JSON record"));
                assertTrue(content.contains("**Timezone:** UTC"));
            }
        }

        onEdt(() -> { search.setText("lima-body-only"); return null; });
        await(() -> searchStatus.getText().startsWith("Search complete") && table.getRowCount() == 1);
        assertEquals("Beta conversation", onEdt(() -> table.getValueAt(0, 0)));
        onEdt(() -> { table.setRowSelectionInterval(0, 0); return null; });
        await(() -> preview.getText().contains("lima-body-only"));
        SwingUtilities.invokeLater(() -> button(app, "Export selected…").doClick());
        await(() -> showingChooser() != null);
        onEdt(() -> {
            var chooser = showingChooser();
            assertNotNull(chooser);
            assertEquals(JFileChooser.FILES_ONLY, chooser.getFileSelectionMode());
            chooser.setSelectedFile(output.resolve("single-export").toFile());
            chooser.approveSelection();
            return null;
        });
        await(() -> reportDialog() != null);
        assertTrue(Files.exists(output.resolve("single-export.md")));
        assertTrue(Files.readString(output.resolve("single-export.md")).contains("lima-body-only"));
        assertFalse(Files.readString(output.resolve("single-export.md")).contains("foxtrot-body-only"));
        onEdt(() -> {
            Dialog dialog = requireReportDialog();
            dialog.dispose();
            return null;
        });
        await(() -> button(app, "Refresh").isEnabled());

        onEdt(() -> { search.setText("fresh-after-refresh"); return null; });
        await(() -> searchStatus.getText().startsWith("Search complete") && table.getRowCount() == 0);
        compressedFile(logs, "alpha.jsonl.zst", metadata("alpha"), message("user", "Alpha conversation"),
                message("assistant", "Alpha answer: foxtrot-body-only; fresh-after-refresh"));
        onEdt(() -> { button(app, "Refresh").doClick(); return null; });
        await(() -> button(app, "Refresh").isEnabled() && searchStatus.getText().startsWith("Search complete") && table.getRowCount() == 1);
        assertEquals("Alpha conversation", onEdt(() -> table.getValueAt(0, 0)));
        onEdt(() -> {
            search.setText("");
            assertEquals(2, table.getRowCount());
            assertTrue(searchStatus.getText().isBlank());
            return null;
        });

        var conversationsBeforeCancel = onEdt(() -> List.of(table.getValueAt(0, 0), table.getValueAt(1, 0)));
        onEdt(() -> {
            button(app, "Refresh").doClick(0);
            button(app, "Cancel").doClick(0);
            return null;
        });
        await(() -> button(app, "Refresh").isEnabled() && !button(app, "Cancel").isVisible());
        assertNotNull(onEdt(() -> find(app, JLabel.class, label -> "Scan cancelled.".equals(label.getText()))));
        assertEquals(2, onEdt(table::getRowCount));
        assertEquals(conversationsBeforeCancel,
                onEdt(() -> List.of(table.getValueAt(0, 0), table.getValueAt(1, 0))));
        assertTrue(onEdt(() -> button(app, "Refresh").isEnabled()));
    }

    private static @NotNull JButton button(Container root, String text) {
        return requireComponent(root, JButton.class, button -> text.equals(button.getText()));
    }

    private static @Nullable JFileChooser showingChooser() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                JFileChooser chooser = find(window, JFileChooser.class, ignored -> true);
                if (chooser != null) return chooser;
            }
        }
        return null;
    }

    private static @Nullable Dialog reportDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isShowing() && dialog.getTitle().equals("Export report"))
                return dialog;
        }
        return null;
    }

    private static @NotNull Dialog requireReportDialog() {
        return Objects.requireNonNull(reportDialog(), "Expected the export report dialog to be showing");
    }

    private static <T extends Component> @NotNull T requireComponent(Container root, Class<T> type, Predicate<T> predicate) {
        return Objects.requireNonNull(find(root, type, predicate), () -> "Expected component: " + type.getSimpleName());
    }

    private static <T extends Component> @Nullable T find(@Nullable Container root, Class<T> type, Predicate<T> predicate) {
        if (root == null) return null;
        if (type.isInstance(root) && predicate.test(type.cast(root))) return type.cast(root);
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && predicate.test(type.cast(component))) return type.cast(component);
            if (component instanceof Container child) {
                T result = find(child, type, predicate);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        var ready = new CompletableFuture<Void>();
        Timer poll = onEdt(() -> {
            var timer = new Timer(30, event -> {
                try {
                    if (condition.call()) {
                        ((Timer) event.getSource()).stop();
                        ready.complete(null);
                    }
                } catch (Exception | AssertionError error) {
                    ((Timer) event.getSource()).stop();
                    ready.completeExceptionally(error);
                }
            });
            timer.setInitialDelay(0);
            timer.start();
            return timer;
        });
        try {
            ready.get(15, TimeUnit.SECONDS);
            return;
        } catch (TimeoutException ignored) {
            // Collect the same UI-state diagnostics as other desktop workflow failures.
        } finally {
            onEdt(() -> {
                poll.stop();
                return null;
            });
        }
        String state = onEdt(() -> {
            var text = new StringBuilder();
            for (Window window : Window.getWindows()) {
                if (window.isShowing()) {
                    text.append(window.getClass().getSimpleName()).append(" ");
                    for (String label : List.of("Refresh", "Cancel", "Export selected…")) {
                        JButton button = find(window, JButton.class, component -> label.equals(component.getText()));
                        if (button != null) {
                            text.append(label).append("[enabled=").append(button.isEnabled())
                                    .append(", visible=").append(button.isVisible()).append("] ");
                        }
                    }
                }
            }
            return text.toString();
        });
        fail("Desktop workflow did not reach the expected state within 15 seconds. " + state);
    }
}
