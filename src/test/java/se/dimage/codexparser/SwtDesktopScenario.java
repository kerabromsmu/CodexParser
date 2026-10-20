/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static se.dimage.codexparser.Fixtures.*;

/** Runs on the process's main thread rather than Gradle/JUnit's worker thread. */
public final class SwtDesktopScenario {
    private final Display display;
    private final Path temporary;
    private final StubDialogs dialogs = new StubDialogs();
    private Shell shell;
    private Table table;
    private Text search;
    private Text preview;
    private Label searchStatus;

    private SwtDesktopScenario(Display display, Path temporary) {
        this.display = display;
        this.temporary = temporary;
    }

    static void main(String[] args) throws Exception {
        Display.setAppName("Codex Parser test");
        var display = new Display();
        try {
            var scenario = new SwtDesktopScenario(display, Path.of(args[1]));
            switch (args[0]) {
                case "workflow" -> scenario.workflow();
                case "cancellation" -> scenario.cancellation();
                case "reports" -> scenario.reports();
                case "smoke" -> scenario.smoke();
                default -> throw new IllegalArgumentException("Unknown scenario: " + args[0]);
            }
        } finally {
            display.dispose();
        }
    }

    private Path createLogs() throws Exception {
        Path logs = temporary.resolve("logs");
        compressedFile(logs, "alpha.jsonl.zst", metadata("alpha"), message("user", "Alpha conversation"),
                message("assistant", "Alpha answer: foxtrot-body-only"));
        file(logs, "beta.jsonl", metadata("beta"), message("user", "Beta conversation"),
                message("assistant", "Beta answer: lima-body-only"));
        return logs;
    }

    private void open(Path logs) {
        var app = new CodexParserApp(display, List.of(logs), dialogs);
        shell = app.shell();
        app.open();
        table = require(shell, Table.class, _ -> true);
        search = require(shell, Text.class, c -> "search".equals(c.getData("name")));
        preview = require(shell, Text.class, c -> "preview".equals(c.getData("name")));
        searchStatus = require(shell, Label.class, c -> "searchStatus".equals(c.getData("name")));
        await(() -> table.getItemCount() == 2 && button("Refresh").isEnabled());
    }

    private void workflow() throws Exception {
        Path logs = createLogs();
        Path output = Files.createDirectories(temporary.resolve("exports"));
        open(logs);
        assertFalse(button("Export selected…").isEnabled());
        assertTrue((table.getStyle() & SWT.MULTI) != 0);
        assertTrue((preview.getStyle() & SWT.READ_ONLY) != 0);

        search.setText("Alpha");
        assertEquals(1, table.getItemCount());
        select(0);
        await(() -> preview.getText().contains("Alpha answer"));
        assertFalse(preview.getText().contains("Original JSON record"));
        search.setText("foxtrot-body-only");
        searched(1);
        assertEquals("Alpha conversation", table.getItem(0).getText());
        select(0);
        await(() -> preview.getText().contains("foxtrot-body-only"));
        search.setText("lima-body-only");
        search.setText("absent-body-marker");
        search.setText("foxtrot-body-only");
        searched(1);
        pumpFor(Duration.ofMillis(400));
        assertEquals("Alpha conversation", table.getItem(0).getText());
        select(0);

        Combo mode = require(shell, Combo.class, c -> c.getItem(0).equals("Conversation only"));
        mode.select(1);
        mode.notifyListeners(SWT.Selection, new Event());
        Combo timezone = require(shell, Combo.class, c -> c.getItem(1).equals("UTC"));
        timezone.select(1);
        timezone.notifyListeners(SWT.Selection, new Event());
        await(() -> preview.getText().contains("Original JSON record") && preview.getText().contains("+00:00"));
        assertEquals(1, table.getSelectionCount());
        assertEquals("Alpha conversation", table.getSelection()[0].getText());

        search.setText("");
        assertEquals(2, table.getItemCount());
        table.getColumn(0).notifyListeners(SWT.Selection, new Event());
        assertEquals("Alpha conversation", table.getItem(0).getText());
        table.getColumn(0).notifyListeners(SWT.Selection, new Event());
        assertEquals("Beta conversation", table.getItem(0).getText());
        assertEquals("Alpha conversation", table.getSelection()[0].getText());
        select(0, 1);
        await(() -> preview.getText().contains("Beta answer"));
        screenshot();

        // Dismissal does not start an export or change the selection.
        int reportsBefore = dialogs.reports.size();
        click("Export selected…");
        pumpFor(Duration.ofMillis(150));
        assertEquals(reportsBefore, dialogs.reports.size());
        assertEquals(2, table.getSelectionCount());
        assertTrue(button("Refresh").isEnabled());
        dialogs.directory = output;
        click("Export selected…");
        await(() -> dialogs.reports.size() == reportsBefore + 1);
        assertTrue(dialogs.lastReport().contains("2 file(s) saved; 0 failure(s)"));
        assertTrue(dialogs.directoryTitle.contains("2 Markdown files"));
        try (var files = Files.list(output)) {
            var exports = files.toList();
            assertEquals(2, exports.size());
            for (Path path : exports) {
                String content = Files.readString(path);
                assertTrue(content.contains("Original JSON record"));
                assertTrue(content.contains("**Timezone:** UTC"));
            }
        }

        search.setText("lima-body-only");
        searched(1);
        select(0);
        await(() -> preview.getText().contains("lima-body-only"));
        click("Export selected…");
        assertEquals(reportsBefore + 1, dialogs.reports.size());
        assertTrue(dialogs.suggestedFilename.endsWith(".md"));
        Path requested = output.resolve("single-export");
        Files.writeString(output.resolve("single-export.md"), "existing file");
        dialogs.file = requested;
        click("Export selected…");
        await(() -> dialogs.reports.size() == reportsBefore + 2);
        assertEquals("existing file", Files.readString(output.resolve("single-export.md")));
        String single = Files.readString(output.resolve("single-export (2).md"));
        assertTrue(single.contains("lima-body-only"));
        assertFalse(single.contains("foxtrot-body-only"));

        // Failed exports still yield a report and restore the action buttons.
        Files.delete(logs.resolve("beta.jsonl"));
        dialogs.file = output.resolve("missing.md");
        click("Export selected…");
        await(() -> dialogs.reports.size() == reportsBefore + 3);
        assertTrue(dialogs.lastReport().contains("0 file(s) saved; 1 failure(s)"));
        assertTrue(button("Refresh").isEnabled());
        file(logs, "beta.jsonl", metadata("beta"), message("user", "Beta conversation"), message("assistant", "Beta answer: lima-body-only"));

        search.setText("fresh-after-refresh");
        searched(0);
        compressedFile(logs, "alpha.jsonl.zst", metadata("alpha"), message("user", "Alpha conversation"),
                message("assistant", "Alpha answer: foxtrot-body-only; fresh-after-refresh"));
        click("Refresh");
        searched(1);
        assertEquals("Alpha conversation", table.getItem(0).getText());
        search.setText("");
        assertEquals(2, table.getItemCount());
        assertTrue(searchStatus.getText().isBlank());

        List<String> beforeCancel = titles();
        click("Refresh");
        click("Cancel");
        await(() -> button("Refresh").isEnabled() && !button("Cancel").isVisible());
        assertEquals(beforeCancel, titles());
        assertNotNull(require(shell, Label.class, label -> label.getText().equals("Scan cancelled.")));

        // Malformed logs remain browsable, and warnings are exposed through the UI.
        Files.writeString(logs.resolve("beta.jsonl"), "{broken-json\n", StandardOpenOption.APPEND);
        click("Refresh");
        await(() -> button("Refresh").isEnabled() && find(shell, Button.class,
                b -> b.getText().startsWith("Warnings (") && b.isVisible()) != null);
        var warning = require(shell, Button.class, b -> b.getText().startsWith("Warnings ("));
        warning.notifyListeners(SWT.Selection, new Event());
        assertEquals("Log warnings", dialogs.lastTitle);
        assertTrue(dialogs.lastReport().contains("beta.jsonl"));

        click("Add log folder…");
        assertTrue(button("Refresh").isEnabled());
        Path extra = temporary.resolve("extra");
        file(extra, "gamma.jsonl", metadata("gamma"), message("user", "Gamma conversation"));
        dialogs.directory = extra;
        click("Add log folder…");
        await(() -> button("Refresh").isEnabled() && table.getItemCount() == 3);
        assertNotNull(require(shell, Label.class, l -> l.getText().equals("2 log locations")));
        dialogs.directory = extra;
        click("Add log folder…");
        await(() -> button("Refresh").isEnabled() && table.getItemCount() == 3);
        assertNotNull(require(shell, Label.class, l -> l.getText().equals("2 log locations")));
        assertTrue(dialogs.errors.isEmpty(), dialogs.errors.toString());
        shell.close();
        assertTrue(shell.isDisposed());
    }

    private void cancellation() throws Exception {
        Path logs = createLogs();
        open(logs);
        // A large transcript keeps the real exporter busy long enough to cancel after
        // temporary output creation, exercising cleanup rather than just queued work.
        String largeMessage = message("assistant", "large-marker " + "abcdefghijklmno ".repeat(1_000_000));
        file(logs, "beta.jsonl", metadata("beta"), message("user", "Beta conversation"), largeMessage);
        Path output = Files.createDirectories(temporary.resolve("exports"));
        search.setText("Beta");
        select(0);
        dialogs.file = output.resolve("cancelled.md");
        click("Export selected…");
        await(() -> hasTemporary(output));
        click("Cancel");
        await(() -> !dialogs.reports.isEmpty());
        assertTrue(dialogs.lastReport().startsWith("Export cancelled."), dialogs.lastReport());
        assertFalse(hasTemporary(output));
        assertFalse(Files.exists(output.resolve("cancelled.md")));
        assertTrue(button("Refresh").isEnabled());

        dialogs.file = output.resolve("closed.md");
        click("Export selected…");
        await(() -> hasTemporary(output));
        int reports = dialogs.reports.size();
        shell.close();
        assertFalse(shell.isDisposed(), "Closing waits for the export's cleanup callback");
        await(shell::isDisposed);
        assertFalse(hasTemporary(output));
        assertFalse(Files.exists(output.resolve("closed.md")));
        assertEquals(reports, dialogs.reports.size(), "No completion dialog while closing");

        // Closing while debounced searches/previews or callbacks are pending is safe.
        for (int i = 0; i < 3; i++) {
            open(logs);
            select(0);
            search.setText("large-marker");
            pumpFor(Duration.ofMillis(320));
            shell.close();
            assertTrue(shell.isDisposed());
            pumpFor(Duration.ofMillis(200));
        }
        assertTrue(dialogs.errors.isEmpty(), dialogs.errors.toString());
    }

    private void reports() {
        Shell parent = new Shell(display);
        parent.open();
        var nativeDialogs = new DesktopDialogs.Native();
        display.timerExec(100, () -> {
            Shell dialog = java.util.Arrays.stream(display.getShells())
                    .filter(s -> s.getText().equals("Export report")).findFirst().orElseThrow();
            Text area = require(dialog, Text.class, _ -> true);
            assertEquals("Synthetic export report", area.getText());
            assertTrue((area.getStyle() & SWT.READ_ONLY) != 0);
            require(dialog, Button.class, b -> b.getText().equals("Close")).notifyListeners(SWT.Selection, new Event());
        });
        nativeDialogs.showText(parent, "Export report", "Synthetic export report");
        parent.dispose();
        assertEquals(0, display.getShells().length);
    }

    private void smoke() throws Exception {
        Path logs = createLogs();
        var nativeApp = new CodexParserApp(display, List.of(logs), new DesktopDialogs.Native());
        nativeApp.open();
        while (!nativeApp.shell().isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    private void select(int... rows) {
        table.setSelection(rows);
        var event = new Event();
        event.item = rows.length == 0 ? null : table.getItem(rows[0]);
        table.notifyListeners(SWT.Selection, event);
    }

    private List<String> titles() {
        return java.util.Arrays.stream(table.getItems()).map(TableItem::getText).toList();
    }

    private void searched(int count) {
        await(() -> searchStatus.getText().startsWith("Search complete") && table.getItemCount() == count);
    }

    private Button button(String text) {
        return require(shell, Button.class, b -> b.getText().equals(text));
    }

    private void click(String text) {
        Button button = button(text);
        assertTrue(button.isEnabled(), text + " must be enabled");
        button.notifyListeners(SWT.Selection, new Event());
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for SWT workflow");
            if (!display.readAndDispatch()) pause();
        }
    }

    private void pumpFor(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static boolean hasTemporary(Path output) {
        try (var files = Files.list(output)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(".codex-export-"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void screenshot() throws Exception {
        pumpFor(Duration.ofMillis(100));
        var bounds = shell.getBounds();
        var image = new Image(display, bounds.width, bounds.height);
        var gc = new GC(image);
        try {
            if (!shell.print(gc)) {
                var screen = new GC(display);
                try {
                    screen.copyArea(image, bounds.x, bounds.y);
                } finally {
                    screen.dispose();
                }
            }
            var loader = new ImageLoader();
            loader.data = new org.eclipse.swt.graphics.ImageData[]{image.getImageData()};
            Files.createDirectories(Path.of("build"));
            loader.save("build/desktop-smoke.png", SWT.IMAGE_PNG);
        } finally {
            gc.dispose();
            image.dispose();
        }
    }

    private static <T extends Control> T require(Composite root, Class<T> type, Predicate<T> predicate) {
        T result = find(root, type, predicate);
        assertNotNull(result, "Missing SWT control: " + type.getSimpleName());
        return result;
    }

    private static <T extends Control> T find(Composite root, Class<T> type, Predicate<T> predicate) {
        for (Control control : root.getChildren()) {
            if (type.isInstance(control) && predicate.test(type.cast(control))) return type.cast(control);
            if (control instanceof Composite child) {
                T result = find(child, type, predicate);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class StubDialogs implements DesktopDialogs {
        Path directory;
        Path file;
        String directoryTitle;
        String suggestedFilename;
        String lastTitle;
        final List<String> reports = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public Path chooseDirectory(Shell parent, String title) {
            directoryTitle = title;
            Path result = directory;
            directory = null;
            return result;
        }

        @Override
        public Path chooseMarkdownFile(Shell parent, String filename) {
            suggestedFilename = filename;
            Path result = file;
            file = null;
            return result;
        }

        @Override
        public void showText(Shell parent, String title, String text) {
            lastTitle = title;
            reports.add(text);
        }

        @Override
        public void showError(Shell parent, String title, Throwable error) {
            errors.add(error);
        }

        String lastReport() {
            return reports.getLast();
        }
    }
}
