package se.dimage.codexparser;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class CodexParserApp {
    private final Display display;
    private final Shell shell;
    private final DesktopDialogs dialogs;
    private final List<Path> roots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private Table table;
    private Text search;
    private Text preview;
    private Combo mode;
    private Combo timezone;
    private Button refresh;
    private Button addFolder;
    private Button export;
    private Button cancel;
    private Button warnings;
    private Label status;
    private Label selection;
    private Label sources;
    private Label searchStatus;
    private Label previewCaption;
    private ProgressBar progress;
    private List<Conversation> conversations = List.of();
    private List<String> scanWarnings = List.of();
    private List<String> searchWarnings = List.of();
    private Set<Path> contentMatches = Set.of();
    private Operation searchOperation;
    private Operation scanOperation;
    private Operation previewOperation;
    private Operation exportOperation;
    private Path focusedSource;
    private int sortColumn = -1;
    private boolean sortAscending = true;
    private boolean closeWhenFinished;
    private final Runnable previewTimer = this::loadPreview;
    private final Runnable searchTimer = this::searchContents;

    static void main() {
        Display display;
        try {
            Display.setAppName("Codex Parser");
            display = new Display();
        } catch (SWTError | UnsatisfiedLinkError error) {
            System.err.println("Codex Parser could not open the native desktop. Check the display and SWT platform libraries.\n" + error.getMessage());
            return;
        }
        try {
            var app = new CodexParserApp(display, ConversationDiscovery.defaultRoots(), new DesktopDialogs.Native());
            app.open();
            while (!app.shell.isDisposed()) {
                if (!display.readAndDispatch()) display.sleep();
            }
        } finally {
            display.dispose();
        }
    }

    CodexParserApp(Display display, List<Path> initialRoots, DesktopDialogs dialogs) {
        this.display = display;
        this.dialogs = dialogs;
        roots = new ArrayList<>(initialRoots);
        shell = new Shell(display);
        shell.setText("Codex Parser — Conversation Exporter");
        shell.setMinimumSize(950, 600);
        var bounds = display.getPrimaryMonitor().getClientArea();
        shell.setSize(Math.min(1350, bounds.width), Math.min(850, bounds.height));
        shell.setLocation(bounds.x + Math.max(0, (bounds.width - shell.getSize().x) / 2),
                bounds.y + Math.max(0, (bounds.height - shell.getSize().y) / 2));
        buildInterface();
        wireActions();
        updateSources();
        updateActions();
        shell.addListener(SWT.Close, event -> {
            stopReading();
            if (exportOperation != null) {
                event.doit = false;
                closeWhenFinished = true;
                exportOperation.cancelled.set(true);
                status.setText("Finishing cancellation before closing…");
                updateActions();
            }
        });
        shell.addListener(SWT.Dispose, _ -> {
            stopReading();
            if (exportOperation != null) exportOperation.cancelled.set(true);
            workers.shutdown();
        });
    }

    void open() {
        shell.open();
        refreshConversations();
    }

    Shell shell() {
        return shell;
    }

    private static Composite row(Composite parent, int columns) {
        var result = new Composite(parent, SWT.NONE);
        var layout = new GridLayout(columns, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 8;
        result.setLayout(layout);
        result.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return result;
    }

    private static Label label(Composite parent, String text) {
        var label = new Label(parent, SWT.NONE);
        label.setText(text);
        return label;
    }

    private static Button button(Composite parent, String text) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(text);
        return button;
    }

    private void buildInterface() {
        var layout = new GridLayout();
        layout.marginWidth = 20;
        layout.marginHeight = 16;
        layout.verticalSpacing = 12;
        shell.setLayout(layout);
        label(shell, "Codex conversations");
        label(shell, "Browse your local sessions. Export a readable Markdown transcript.");
        var sourceRow = row(shell, 3);
        sources = label(sourceRow, "");
        sources.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        addFolder = button(sourceRow, "Add log folder…");
        refresh = button(sourceRow, "Refresh");

        var split = new SashForm(shell, SWT.HORIZONTAL);
        split.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        var browse = new Composite(split, SWT.NONE);
        browse.setLayout(new GridLayout());
        var filter = row(browse, 2);
        label(filter, "Search");
        search = new Text(filter, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setData("name", "search");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.setToolTipText("Search titles, projects, session IDs, paths, and user/assistant messages (including code)");
        searchStatus = label(browse, " ");
        searchStatus.setData("name", "searchStatus");
        searchStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        table = new Table(browse, SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] columns = {"Conversation", "Started", "Project", "Location"};
        int[] widths = {300, 220, 160, 90};
        for (int i = 0; i < columns.length; i++) {
            var column = new TableColumn(table, SWT.NONE);
            column.setText(columns[i]);
            column.setWidth(widths[i]);
            final int index = i;
            column.addListener(SWT.Selection, _ -> {
                sortAscending = sortColumn != index || !sortAscending;
                sortColumn = index;
                table.setSortColumn(column);
                table.setSortDirection(sortAscending ? SWT.UP : SWT.DOWN);
                applyFilter();
            });
        }
        label(browse, "Ctrl / Cmd + click to select several; Shift + click to select a range.");
        var previewPanel = new Composite(split, SWT.NONE);
        previewPanel.setLayout(new GridLayout());
        previewCaption = label(previewPanel, "MARKDOWN PREVIEW");
        previewCaption.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        preview = new Text(previewPanel, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        preview.setData("name", "preview");
        preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String family = switch (SWT.getPlatform()) {
            case "win32" -> "Consolas";
            case "cocoa" -> "Menlo";
            default -> "Monospace";
        };
        var font = new Font(display, new FontData(family, preview.getFont().getFontData()[0].getHeight(), SWT.NORMAL));
        preview.setFont(font);
        preview.addListener(SWT.Dispose, _ -> font.dispose());
        preview.setText("Select a conversation to preview its Markdown export.");
        split.setWeights(55, 45);

        var options = row(shell, 5);
        label(options, "Include");
        mode = new Combo(options, SWT.DROP_DOWN | SWT.READ_ONLY);
        mode.setItems(Arrays.stream(ExportOptions.Mode.values()).map(Object::toString).toArray(String[]::new));
        mode.select(0);
        mode.setToolTipText("Everything also includes instructions, tool activity, metadata, and original JSON records");
        label(options, "Timestamps");
        timezone = new Combo(options, SWT.DROP_DOWN | SWT.READ_ONLY);
        timezone.setItems("Local (" + ZoneId.systemDefault() + ")", "UTC");
        timezone.select(0);
        export = button(options, "Export selected…");
        export.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        var statusRow = row(shell, 5);
        status = label(statusRow, "Ready");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        warnings = button(statusRow, "Warnings");
        selection = label(statusRow, "0 selected");
        progress = new ProgressBar(statusRow, SWT.INDETERMINATE);
        var progressData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        progressData.widthHint = 85;
        progress.setLayoutData(progressData);
        cancel = button(statusRow, "Cancel");
    }

    private void wireActions() {
        refresh.addListener(SWT.Selection, _ -> refreshConversations());
        addFolder.addListener(SWT.Selection, _ -> {
            Path folder = dialogs.chooseDirectory(shell, "Choose a Codex home or a folder with .jsonl or .jsonl.zst conversations");
            if (folder != null) {
                folder = folder.toAbsolutePath().normalize();
                if (!roots.contains(folder)) roots.add(folder);
                updateSources();
                refreshConversations();
            }
        });
        search.addModifyListener(_ -> filter());
        table.addListener(SWT.Selection, event -> {
            if (event.item instanceof TableItem item) focusedSource = ((Conversation) item.getData()).source();
            updateActions();
            schedulePreview();
        });
        table.addListener(SWT.MouseMove, event -> {
            TableItem item = table.getItem(new org.eclipse.swt.graphics.Point(event.x, event.y));
            table.setToolTipText(item == null ? null : ((Conversation) item.getData()).source().toString());
        });
        mode.addListener(SWT.Selection, _ -> schedulePreview());
        timezone.addListener(SWT.Selection, _ -> applyFilter());
        export.addListener(SWT.Selection, _ -> chooseExport());
        cancel.addListener(SWT.Selection, _ -> {
            if (scanOperation != null) {
                scanOperation.cancelled.set(true);
                status.setText("Cancelling scan…");
            }
            if (exportOperation != null) {
                exportOperation.cancelled.set(true);
                status.setText("Cancelling export…");
            }
            cancel.setEnabled(false);
        });
        warnings.addListener(SWT.Selection, _ -> {
            var messages = new ArrayList<>(scanWarnings);
            messages.addAll(searchWarnings);
            dialogs.showText(shell, "Log warnings", String.join("\n", messages));
        });
    }

    private void filter() {
        cancelContentSearch();
        contentMatches = Set.of();
        searchWarnings = List.of();
        updateWarnings();
        applyFilter();
        if (ConversationSearch.normalizeQuery(search.getText()).isEmpty()) {
            searchStatus.setText(" ");
        } else if (scanOperation != null) {
            searchStatus.setText("Waiting for conversations…");
        } else {
            searchStatus.setText("Searching chat contents…");
            display.timerExec(300, searchTimer);
        }
    }

    private void applyFilter() {
        Set<Path> selected = selectedConversations().stream().map(Conversation::source).collect(Collectors.toSet());
        String query = ConversationSearch.normalizeQuery(search.getText());
        var visible = new ArrayList<>(conversations.stream()
                .filter(c -> ConversationSearch.matchesMetadata(c, query) || contentMatches.contains(c.source())).toList());
        if (sortColumn >= 0) {
            // Sort by displayed cell values, including formatted timestamps.
            Comparator<Conversation> comparator = Comparator.comparing(c -> cells(c)[sortColumn]);
            visible.sort(sortAscending ? comparator : comparator.reversed());
        }
        table.setRedraw(false);
        try {
            table.removeAll();
            for (Conversation conversation : visible) {
                var item = new TableItem(table, SWT.NONE);
                item.setData(conversation);
                item.setText(cells(conversation));
                if (selected.contains(conversation.source())) table.select(table.indexOf(item));
            }
        } finally {
            table.setRedraw(true);
        }
        updateActions();
        schedulePreview();
    }

    private String[] cells(Conversation conversation) {
        return new String[]{conversation.title(), MarkdownExporter.timestamp(conversation.startedAt(), options().zone()),
                conversation.project().isBlank() ? "Unknown" : conversation.project(),
                conversation.archived() ? "Archived" : "Sessions"};
    }

    private void cancelContentSearch() {
        display.timerExec(-1, searchTimer);
        if (searchOperation != null) searchOperation.cancelled.set(true);
        searchOperation = null;
    }

    private void searchContents() {
        String query = ConversationSearch.normalizeQuery(search.getText());
        if (query.isEmpty() || scanOperation != null || closeWhenFinished) return;
        var snapshot = List.copyOf(conversations);
        var operation = new Operation();
        searchOperation = operation;
        submit(() -> new ConversationSearch().search(snapshot, query, operation.cancelled::get), result -> {
            if (searchOperation != operation || operation.cancelled.get()) return;
            searchOperation = null;
            contentMatches = result.matches();
            searchWarnings = result.warnings();
            applyFilter();
            searchStatus.setText("Search complete — " + table.getItemCount() + " conversation(s)"
                    + (searchWarnings.isEmpty() ? "" : "; " + searchWarnings.size() + " warning(s)"));
            updateWarnings();
        }, error -> {
            if (searchOperation != operation || operation.cancelled.get()) return;
            searchOperation = null;
            searchWarnings = List.of("Content search failed: " + error.getMessage());
            searchStatus.setText("Content search failed; showing metadata matches.");
            updateWarnings();
        });
    }

    private ExportOptions options() {
        return new ExportOptions(ExportOptions.Mode.values()[mode.getSelectionIndex()],
                timezone.getSelectionIndex() == 1 ? ZoneId.of("UTC") : ZoneId.systemDefault());
    }

    private List<Conversation> selectedConversations() {
        return Arrays.stream(table.getSelection()).map(item -> (Conversation) item.getData()).toList();
    }

    private void updateActions() {
        boolean busy = scanOperation != null || exportOperation != null;
        refresh.setEnabled(!busy && !closeWhenFinished);
        addFolder.setEnabled(!busy && !closeWhenFinished);
        export.setEnabled(!busy && !closeWhenFinished && table.getSelectionCount() > 0);
        visible(cancel, busy);
        cancel.setEnabled(busy && !closeWhenFinished);
        visible(progress, busy);
        selection.setText(table.getItemCount() + " shown · " + table.getSelectionCount() + " selected");
        shell.layout(true, true);
    }

    private static void visible(Control control, boolean visible) {
        control.setVisible(visible);
        if (control.getLayoutData() == null) control.setLayoutData(new GridData());
        ((GridData) control.getLayoutData()).exclude = !visible;
    }

    private void updateWarnings() {
        int count = scanWarnings.size() + searchWarnings.size();
        warnings.setText("Warnings (" + count + ")");
        visible(warnings, count > 0);
        shell.layout(true, true);
    }

    private void updateSources() {
        sources.setText(roots.size() + " log location" + (roots.size() == 1 ? "" : "s"));
        sources.setToolTipText(roots.toString());
        updateWarnings();
    }

    private void refreshConversations() {
        if (scanOperation != null || exportOperation != null || closeWhenFinished) return;
        cancelContentSearch();
        if (!ConversationSearch.normalizeQuery(search.getText()).isEmpty()) searchStatus.setText("Waiting for conversations…");
        status.setText("Finding conversations…");
        var snapshot = List.copyOf(roots);
        var operation = new Operation();
        scanOperation = operation;
        updateActions();
        submit(() -> new ConversationDiscovery().scan(snapshot, operation.cancelled::get,
                text -> post(() -> {
                    if (scanOperation == operation && !operation.cancelled.get()) status.setText(text);
                })), result -> finishScan(operation, result, null), error -> finishScan(operation, null, error));
    }

    private void finishScan(Operation operation, ConversationDiscovery.ScanResult result, Exception error) {
        if (scanOperation != operation) return;
        scanOperation = null;
        if (operation.cancelled.get() || error instanceof CancellationException) {
            status.setText("Scan cancelled.");
        } else if (error != null) {
            status.setText("Scan failed.");
        } else {
            conversations = result.conversations();
            scanWarnings = result.warnings();
            status.setText(conversations.isEmpty() ? "No conversations found. Add a log folder to search elsewhere."
                    : "Found " + conversations.size() + " conversations. Select one to preview.");
        }
        updateActions();
        if (!closeWhenFinished) filter();
        if (error != null && !(error instanceof CancellationException) && !operation.cancelled.get())
            dialogs.showError(shell, "Could not scan logs", error);
    }

    private void schedulePreview() {
        if (previewOperation != null) previewOperation.cancelled.set(true);
        previewOperation = null;
        display.timerExec(-1, previewTimer);
        if (!closeWhenFinished) display.timerExec(150, previewTimer);
    }

    private void loadPreview() {
        var selected = selectedConversations();
        previewCaption.setText("MARKDOWN PREVIEW");
        if (selected.isEmpty()) {
            preview.setText("Select a conversation to preview its Markdown export.");
            return;
        }
        Conversation conversation = selected.stream().filter(c -> c.source().equals(focusedSource)).findFirst().orElse(selected.getFirst());
        ExportOptions currentOptions = options();
        preview.setText("Loading Markdown preview…");
        var operation = new Operation();
        previewOperation = operation;
        submit(() -> new MarkdownExporter().preview(conversation, currentOptions, operation.cancelled::get), result -> {
            if (previewOperation != operation || operation.cancelled.get()) return;
            previewOperation = null;
            preview.setText(result.text());
            preview.setSelection(0);
            previewCaption.setText(result.truncated() ? "MARKDOWN PREVIEW · TRUNCATED — EXPORT INCLUDES ALL CONTENT" : "MARKDOWN PREVIEW");
            previewCaption.getParent().layout();
        }, error -> {
            if (previewOperation != operation || operation.cancelled.get()) return;
            previewOperation = null;
            if (!(error instanceof CancellationException)) preview.setText("Could not preview this conversation:\n" + error.getMessage());
        });
    }

    private void chooseExport() {
        var selected = selectedConversations();
        if (selected.isEmpty() || scanOperation != null || exportOperation != null || closeWhenFinished) return;
        ExportOptions currentOptions = options();
        boolean single = selected.size() == 1;
        Path destination = single
                ? dialogs.chooseMarkdownFile(shell, ExportService.filename(selected.getFirst(), currentOptions))
                : dialogs.chooseDirectory(shell, "Choose a folder for " + selected.size() + " Markdown files");
        if (destination == null || shell.isDisposed() || closeWhenFinished) return;
        var operation = new Operation();
        exportOperation = operation;
        updateActions();
        Consumer<String> progress = text -> post(() -> {
            if (exportOperation == operation && !operation.cancelled.get()) status.setText(text);
        });
        submit(() -> {
            var service = new ExportService();
            return single ? service.exportSingle(selected.getFirst(), destination, currentOptions, operation.cancelled::get, progress)
                    : service.exportBatch(selected, destination, currentOptions, operation.cancelled::get, progress);
        }, result -> {
            exportOperation = null;
            status.setText((result.cancelled() ? "Export cancelled. " : "Export complete. ")
                    + result.exported().size() + " saved, " + result.failures().size() + " failed.");
            updateActions();
            if (closeWhenFinished) shell.dispose();
            else dialogs.showText(shell, "Export report", report(result));
        }, error -> {
            exportOperation = null;
            status.setText("Export failed.");
            updateActions();
            if (closeWhenFinished) shell.dispose();
            else dialogs.showError(shell, "Could not export", error);
        });
    }

    private void stopReading() {
        display.timerExec(-1, previewTimer);
        cancelContentSearch();
        if (previewOperation != null) previewOperation.cancelled.set(true);
        previewOperation = null;
        if (scanOperation != null) scanOperation.cancelled.set(true);
    }

    private <T> void submit(Callable<T> work, Consumer<T> completed, Consumer<Exception> failed) {
        workers.submit(() -> {
            try {
                T result = work.call();
                post(() -> completed.accept(result));
            } catch (Exception error) {
                post(() -> failed.accept(error));
            }
        });
    }

    private void post(Runnable callback) {
        try {
            display.asyncExec(() -> {
                if (!shell.isDisposed()) callback.run();
            });
        } catch (SWTException error) {
            if (error.code != SWT.ERROR_DEVICE_DISPOSED) throw error;
        }
    }

    private static final class Operation {
        final AtomicBoolean cancelled = new AtomicBoolean();
    }

    private static String report(ExportService.BatchResult result) {
        var report = new StringBuilder(result.cancelled() ? "Export cancelled. Completed files are listed below.\n\n" : "Export complete.\n\n");
        report.append(result.exported().size()).append(" file(s) saved; ").append(result.failures().size()).append(" failure(s).\n\n");
        for (var saved : result.exported()) {
            report.append(saved.path()).append("\n  ").append(saved.details().entries()).append(" entries");
            if (saved.details().malformedLines() > 0)
                report.append("; ").append(saved.details().malformedLines()).append(" malformed log lines");
            if (saved.details().unknownTimestamps() > 0)
                report.append("; ").append(saved.details().unknownTimestamps()).append(" unknown timestamps");
            report.append("\n\n");
        }
        for (var failure : result.failures())
            report.append("FAILED: ").append(failure.source()).append("\n  ").append(failure.message()).append("\n\n");
        return report.toString();
    }

}
