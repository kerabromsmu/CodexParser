package se.dimage.codexparser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CodexParserApp extends JFrame {
    private final List<Path> roots;
    private final ConversationTable model = new ConversationTable();
    private final JTable table = new JTable(model) {
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : model.rows.get(convertRowIndexToModel(row)).source().toString();
        }
    };
    private final TableRowSorter<ConversationTable> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField(24);
    private final JTextArea preview = new JTextArea();
    private final JComboBox<ExportOptions.Mode> mode = new JComboBox<>(ExportOptions.Mode.values());
    private final JComboBox<String> timezone = new JComboBox<>(new String[]{"Local (" + ZoneId.systemDefault() + ")", "UTC"});
    private final JButton refresh = new JButton("Refresh");
    private final JButton addFolder = new JButton("Add log folder…");
    private final JButton export = new JButton("Export selected…");
    private final JButton cancel = new JButton("Cancel");
    private final JButton warnings = new JButton("Scan warnings");
    private final JLabel status = new JLabel("Ready");
    private final JLabel selection = new JLabel("0 selected");
    private final JLabel sources = new JLabel();
    private final JLabel previewCaption = new JLabel("MARKDOWN PREVIEW");
    private final JProgressBar progress = new JProgressBar();
    private final Timer previewTimer;
    private List<String> scanWarnings = List.of();
    private SwingWorker<ConversationDiscovery.ScanResult, String> scanWorker;
    private AtomicBoolean scanCancelled;
    private SwingWorker<MarkdownExporter.Preview, Void> previewWorker;
    private SwingWorker<ExportService.BatchResult, String> exportWorker;
    private AtomicBoolean exportCancelled;
    private boolean closeWhenFinished;

    static void main() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Codex Parser needs a graphical desktop. Start it from a desktop session.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                for (var theme : UIManager.getInstalledLookAndFeels()) {
                    if (theme.getName().equals("Nimbus")) {
                        UIManager.setLookAndFeel(theme.getClassName());
                        break;
                    }
                }
                UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            } catch (Exception ignored) {
                // Swing's default look and feel remains usable.
            }
            var app = new CodexParserApp();
            app.setVisible(true);
            app.refreshConversations();
        });
    }

    public CodexParserApp() {
        this(ConversationDiscovery.defaultRoots());
    }

    CodexParserApp(List<Path> initialRoots) {
        super("Codex Parser — Conversation Exporter");
        roots = new ArrayList<>(initialRoots);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(950, 600));
        setSize(1350, 850);
        setLocationRelativeTo(null);
        previewTimer = new Timer(150, _ -> loadPreview());
        previewTimer.setRepeats(false);
        buildInterface();
        wireActions();
        updateSources();
        updateActions();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                previewTimer.stop();
                if (previewWorker != null) previewWorker.cancel(true);
                if (scanWorker != null) scanCancelled.set(true);
                if (exportWorker != null) {
                    closeWhenFinished = true;
                    exportCancelled.set(true);
                    status.setText("Finishing cancellation before closing…");
                } else dispose();
            }
        });
    }

    private void buildInterface() {
        var content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 16, 24));
        setContentPane(content);

        var heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        var title = new JLabel("Codex conversations");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        var subtitle = new JLabel("Browse your local sessions. Export a readable Markdown transcript.");
        subtitle.setForeground(new Color(90, 100, 110));
        heading.add(title);
        heading.add(Box.createVerticalStrut(7));
        heading.add(subtitle);
        heading.add(Box.createVerticalStrut(14));

        var sourceRow = new JPanel(new BorderLayout(10, 0));
        var sourceActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        sourceActions.add(addFolder);
        sourceActions.add(refresh);
        sourceRow.add(sources, BorderLayout.CENTER);
        sourceRow.add(sourceActions, BorderLayout.EAST);
        sourceRow.setAlignmentX(LEFT_ALIGNMENT);
        heading.add(sourceRow);
        content.add(heading, BorderLayout.NORTH);

        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(34);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(160);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);

        var browse = new JPanel(new BorderLayout(0, 10));
        var filter = new JPanel(new BorderLayout(8, 0));
        filter.add(new JLabel("Search"), BorderLayout.WEST);
        search.setToolTipText("Filter by conversation title, project, session ID, or source path");
        search.setName("search");
        filter.add(search, BorderLayout.CENTER);
        browse.add(filter, BorderLayout.NORTH);
        browse.add(new JScrollPane(table), BorderLayout.CENTER);
        var listHelp = new JLabel("Ctrl / Cmd + click to select several; Shift + click to select a range.");
        listHelp.setFont(listHelp.getFont().deriveFont(11f));
        browse.add(listHelp, BorderLayout.SOUTH);

        preview.setEditable(false);
        preview.setName("preview");
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setMargin(new java.awt.Insets(14, 14, 14, 14));
        preview.setText("Select a conversation to preview its Markdown export.");
        var previewPanel = new JPanel(new BorderLayout(0, 10));
        previewCaption.setFont(previewCaption.getFont().deriveFont(Font.BOLD, 11f));
        previewPanel.add(previewCaption, BorderLayout.NORTH);
        previewPanel.add(new JScrollPane(preview), BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browse, previewPanel);
        split.setResizeWeight(0.55);
        split.setDividerLocation(690);
        split.setBorder(BorderFactory.createEmptyBorder());
        browse.setMinimumSize(new Dimension(350, 100));
        previewPanel.setMinimumSize(new Dimension(300, 100));
        content.add(split, BorderLayout.CENTER);

        var footer = new JPanel(new BorderLayout(0, 12));
        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(new JLabel("Include"));
        options.add(mode);
        mode.setToolTipText("Everything also includes instructions, tool activity, metadata, and original JSON records");
        options.add(Box.createHorizontalStrut(8));
        options.add(new JLabel("Timestamps"));
        options.add(timezone);
        var exportRow = new JPanel(new BorderLayout(8, 0));
        exportRow.add(options, BorderLayout.CENTER);
        exportRow.add(export, BorderLayout.EAST);
        export.setBackground(new Color(30, 112, 100));
        export.setForeground(Color.WHITE);
        footer.add(exportRow, BorderLayout.NORTH);

        var statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.add(status, BorderLayout.CENTER);
        var indicators = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        progress.setPreferredSize(new Dimension(85, 12));
        progress.setIndeterminate(true);
        indicators.add(warnings);
        indicators.add(selection);
        indicators.add(progress);
        indicators.add(cancel);
        statusRow.add(indicators, BorderLayout.EAST);
        footer.add(statusRow, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        warnings.setVisible(false);
    }

    private void wireActions() {
        refresh.addActionListener(_ -> refreshConversations());
        addFolder.addActionListener(_ -> {
            var chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a Codex home or a folder with .jsonl or .jsonl.zst conversations");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                Path folder = chooser.getSelectedFile().toPath().toAbsolutePath();
                if (!roots.contains(folder)) roots.add(folder);
                updateSources();
                refreshConversations();
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActions();
                previewTimer.restart();
            }
        });
        mode.addActionListener(_ -> previewTimer.restart());
        timezone.addActionListener(_ -> {
            model.zone = options().zone();
            // Preserve selection while updating timestamp cells and any active date sort.
            var selected = selectedConversations();
            model.fireTableDataChanged();
            for (Conversation conversation : selected) {
                int index = model.rows.indexOf(conversation);
                int view = index < 0 ? -1 : table.convertRowIndexToView(index);
                if (view >= 0) table.addRowSelectionInterval(view, view);
            }
            previewTimer.restart();
        });
        export.addActionListener(_ -> chooseExport());
        cancel.addActionListener(_ -> {
            if (scanWorker != null) {
                scanCancelled.set(true);
                status.setText("Cancelling scan…");
                cancel.setEnabled(false);
            }
            if (exportWorker != null) {
                exportCancelled.set(true);
                status.setText("Cancelling export…");
                cancel.setEnabled(false);
            }
        });
        warnings.addActionListener(_ -> showText("Scan warnings", String.join("\n", scanWarnings)));
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ConversationTable, ? extends Integer> entry) {
                Conversation row = model.rows.get(entry.getIdentifier());
                return (row.title() + " " + row.project() + " " + row.id() + " " + row.source())
                        .toLowerCase(Locale.ROOT).contains(query);
            }
        });
        updateActions();
        previewTimer.restart();
    }

    private ExportOptions options() {
        return new ExportOptions((ExportOptions.Mode) mode.getSelectedItem(),
                timezone.getSelectedIndex() == 1 ? ZoneId.of("UTC") : ZoneId.systemDefault());
    }

    private List<Conversation> selectedConversations() {
        var selected = new ArrayList<Conversation>();
        for (int view : table.getSelectedRows()) selected.add(model.rows.get(table.convertRowIndexToModel(view)));
        return List.copyOf(selected);
    }

    private void updateActions() {
        boolean busy = scanWorker != null || exportWorker != null;
        refresh.setEnabled(!busy);
        addFolder.setEnabled(!busy);
        export.setEnabled(!busy && table.getSelectedRowCount() > 0);
        cancel.setVisible(busy);
        cancel.setEnabled(busy);
        progress.setVisible(busy);
        selection.setText(table.getRowCount() + " shown · " + table.getSelectedRowCount() + " selected");
    }

    private void updateSources() {
        sources.setText(roots.size() + " log location" + (roots.size() == 1 ? "" : "s"));
        sources.setToolTipText(roots.toString());
    }

    private void refreshConversations() {
        if (scanWorker != null || exportWorker != null) return;
        status.setText("Finding conversations…");
        var scanRoots = List.copyOf(roots);
        // Keep queued workers runnable so cancellation before startup still reaches done().
        scanCancelled = new AtomicBoolean();
        AtomicBoolean cancellation = scanCancelled;
        scanWorker = new SwingWorker<>() {
            @Override
            protected ConversationDiscovery.ScanResult doInBackground() {
                try {
                    return new ConversationDiscovery().scan(scanRoots, cancellation::get, this::publish);
                } catch (CancellationException e) {
                    return new ConversationDiscovery.ScanResult(List.of(), List.of());
                }
            }

            @Override
            protected void process(List<String> chunks) {
                if (scanWorker == this && !cancellation.get()) status.setText(chunks.getLast());
            }

            @Override
            protected void done() {
                try {
                    var result = get();
                    if (cancellation.get()) {
                        status.setText("Scan cancelled.");
                        return;
                    }
                    model.rows = result.conversations();
                    model.fireTableDataChanged();
                    scanWarnings = result.warnings();
                    warnings.setText("Scan warnings (" + scanWarnings.size() + ")");
                    warnings.setVisible(!scanWarnings.isEmpty());
                    status.setText(model.rows.isEmpty() ? "No conversations found. Add a log folder to search elsewhere."
                            : "Found " + model.rows.size() + " conversations. Select one to preview.");
                } catch (CancellationException e) {
                    status.setText("Scan cancelled.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    status.setText("Scan failed.");
                    showError("Could not scan logs", e.getCause());
                } finally {
                    scanWorker = null;
                    updateActions();
                }
            }
        };
        updateActions();
        scanWorker.execute();
    }

    private void loadPreview() {
        if (previewWorker != null) previewWorker.cancel(true);
        var selected = selectedConversations();
        if (selected.isEmpty()) {
            previewWorker = null;
            previewCaption.setText("MARKDOWN PREVIEW");
            preview.setText("Select a conversation to preview its Markdown export.");
            return;
        }
        int lead = table.getSelectionModel().getLeadSelectionIndex();
        Conversation conversation = lead >= 0 && lead < table.getRowCount() && table.isRowSelected(lead)
                ? model.rows.get(table.convertRowIndexToModel(lead)) : selected.getFirst();
        ExportOptions currentOptions = options();
        preview.setText("Loading Markdown preview…");
        previewCaption.setText("MARKDOWN PREVIEW");
        previewWorker = new SwingWorker<>() {
            @Override
            protected MarkdownExporter.Preview doInBackground() throws Exception {
                return new MarkdownExporter().preview(conversation, currentOptions, this::isCancelled);
            }

            @Override
            protected void done() {
                if (previewWorker != this) return;
                try {
                    var result = get();
                    preview.setText(result.text());
                    preview.setCaretPosition(0);
                    previewCaption.setText(result.truncated() ? "MARKDOWN PREVIEW · TRUNCATED — EXPORT INCLUDES ALL CONTENT"
                            : "MARKDOWN PREVIEW");
                } catch (CancellationException ignored) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    preview.setText("Could not preview this conversation:\n" + e.getCause().getMessage());
                }
            }
        };
        previewWorker.execute();
    }

    private void chooseExport() {
        List<Conversation> selected = selectedConversations();
        if (selected.isEmpty()) return;
        ExportOptions currentOptions = options();
        var chooser = new JFileChooser();
        boolean single = selected.size() == 1;
        chooser.setDialogTitle(single ? "Export conversation as Markdown" : "Choose a folder for " + selected.size() + " Markdown files");
        if (single) {
            chooser.setFileFilter(new FileNameExtensionFilter("Markdown (*.md)", "md"));
            chooser.setSelectedFile(new java.io.File(ExportService.filename(selected.getFirst(), currentOptions)));
        } else chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        exportCancelled = new AtomicBoolean();
        AtomicBoolean cancellation = exportCancelled;
        exportWorker = new SwingWorker<>() {
            @Override
            protected ExportService.BatchResult doInBackground() {
                var service = new ExportService();
                return single ? service.exportSingle(selected.getFirst(), destination, currentOptions,
                        cancellation::get, this::publish)
                        : service.exportBatch(selected, destination, currentOptions, cancellation::get, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                if (exportWorker == this && !cancellation.get()) status.setText(chunks.getLast());
            }

            @Override
            protected void done() {
                try {
                    var result = get();
                    status.setText((result.cancelled() ? "Export cancelled. " : "Export complete. ")
                            + result.exported().size() + " saved, " + result.failures().size() + " failed.");
                    if (!closeWhenFinished) showText("Export report", report(result));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    status.setText("Export failed.");
                    if (!closeWhenFinished) showError("Could not export", e.getCause());
                } finally {
                    exportWorker = null;
                    updateActions();
                    if (closeWhenFinished) dispose();
                }
            }
        };
        updateActions();
        exportWorker.execute();
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

    private void showText(String title, String text) {
        var area = new JTextArea(text, 18, 80);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        var scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(780, 400));
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String title, Throwable error) {
        JOptionPane.showMessageDialog(this, error.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static final class ConversationTable extends AbstractTableModel {
        private List<Conversation> rows = List.of();
        private ZoneId zone = ZoneId.systemDefault();
        private static final String[] COLUMNS = {"Conversation", "Started", "Project", "Location"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Conversation conversation = rows.get(row);
            return switch (column) {
                case 0 -> conversation.title();
                case 1 -> MarkdownExporter.timestamp(conversation.startedAt(), zone);
                case 2 -> conversation.project().isBlank() ? "Unknown" : conversation.project();
                case 3 -> conversation.archived() ? "Archived" : "Sessions";
                default -> "";
            };
        }
    }
}
