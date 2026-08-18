package cn.fj.loli.hexsupport;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** IntelliJ-native result view for read-only Binary Template analysis. */
public final class HexStructureToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String title = HexEditorBundle.message("toolwindow.stripe.Binary_Structure");
        toolWindow.setTitle(title);
        toolWindow.setStripeTitle(title);
        StructurePanel panel = new StructurePanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    private static final class StructurePanel extends javax.swing.JPanel implements Disposable {
        private final Project project;
        private final StructureTableModel model = new StructureTableModel();
        private final JBTable table = new JBTable(model);
        private final JBLabel contextLabel = new JBLabel();
        private final JBLabel statusLabel = new JBLabel();
        private final AtomicInteger generation = new AtomicInteger();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final Timer autoRunTimer;
        private final PropertyChangeListener editorListener = this::editorPropertyChanged;
        private HexFileEditor currentEditor;
        private Path templatePath;

        private StructurePanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            String stored = HexSupportSettings.getInstance().binaryTemplatePath();
            templatePath = stored == null || stored.isBlank() ? null : Path.of(stored);

            add(createHeader(), BorderLayout.NORTH);
            configureTable();
            add(new JBScrollPane(table), BorderLayout.CENTER);
            statusLabel.setBorder(JBUI.Borders.empty(4, 8));
            add(statusLabel, BorderLayout.SOUTH);

            autoRunTimer = new Timer(600, event -> runTemplate());
            autoRunTimer.setRepeats(false);
            project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                    new FileEditorManagerListener() {
                        @Override
                        public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                            refreshFromSelection();
                        }
                    });
            refreshFromSelection();
        }

        private JComponent createHeader() {
            javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout());
            DefaultActionGroup group = new DefaultActionGroup();
            group.add(new DumbAwareAction(HexEditorBundle.message("analysis.action.import"),
                    HexEditorBundle.message("analysis.action.import.description"), AllIcons.General.OpenDisk) {
                @Override public void actionPerformed(@NotNull AnActionEvent event) { importTemplate(); }
            });
            group.add(new DumbAwareAction(HexEditorBundle.message("analysis.action.clear"),
                    HexEditorBundle.message("analysis.action.clear.description"), AllIcons.General.Remove) {
                @Override public void actionPerformed(@NotNull AnActionEvent event) { clearTemplate(); }
                @Override public void update(@NotNull AnActionEvent event) {
                    event.getPresentation().setEnabled(templatePath != null);
                }
            });
            group.add(new DumbAwareAction(HexEditorBundle.message("analysis.action.expandAll"), null, AllIcons.Actions.Expandall) {
                @Override public void actionPerformed(@NotNull AnActionEvent event) { model.expandAll(); }
            });
            group.add(new DumbAwareAction(HexEditorBundle.message("analysis.action.collapseAll"), null, AllIcons.Actions.Collapseall) {
                @Override public void actionPerformed(@NotNull AnActionEvent event) { model.collapseAll(); }
            });

            ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HexStructure.Toolbar", group, true);
            toolbar.setTargetComponent(this);
            panel.add(toolbar.getComponent(), BorderLayout.NORTH);
            contextLabel.setBorder(JBUI.Borders.empty(3, 8, 5, 8));
            panel.add(contextLabel, BorderLayout.SOUTH);
            return panel;
        }

        private void configureTable() {
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setShowGrid(false);
            table.setStriped(true);
            table.getEmptyText().setText(HexEditorBundle.message("analysis.empty"));
            table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(260));
            table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(220));
            table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(100));
            table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(80));
            table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(130));
            table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
            table.getColumnModel().getColumn(2).setCellRenderer(new MonospaceRenderer());
            table.getColumnModel().getColumn(3).setCellRenderer(new MonospaceRenderer());
            table.getSelectionModel().addListSelectionListener(event -> {
                if (event.getValueIsAdjusting() || currentEditor == null) return;
                StructureRow row = model.row(table.getSelectedRow());
                if (row != null && row.node().size() > 0) {
                    currentEditor.selectAnalysisRange(row.node().offset(), row.node().size());
                }
            });
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && table.columnAtPoint(event.getPoint()) == 0) {
                        model.toggle(table.rowAtPoint(event.getPoint()));
                    }
                }
            });
        }

        private void importTemplate() {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle(HexEditorBundle.message("analysis.import.title"))
                    .withDescription(HexEditorBundle.message("analysis.import.description"))
                    .withFileFilter(file -> "bt".equalsIgnoreCase(file.getExtension()));
            VirtualFile base = currentEditor == null ? null : currentEditor.getFile().getParent();
            VirtualFile selected = FileChooser.chooseFile(descriptor, project, base);
            if (selected == null) return;
            templatePath = Path.of(selected.getPath());
            HexSupportSettings.getInstance().setBinaryTemplatePath(templatePath.toString());
            updateContextLabel();
            runTemplate();
        }

        private void clearTemplate() {
            cancelRequested.set(true);
            generation.incrementAndGet();
            autoRunTimer.stop();
            templatePath = null;
            HexSupportSettings.getInstance().setBinaryTemplatePath("");
            model.setNodes(List.of());
            table.clearSelection();
            statusLabel.setToolTipText(null);
            if (currentEditor != null) {
                currentEditor.setTemplateHighlights(List.of());
                currentEditor.clearAnalysisSelection();
            }
            updateEmptyState();
        }

        private void runTemplate() {
            HexFileEditor editor = currentEditor;
            Path template = templatePath;
            if (editor == null || template == null || !Files.isRegularFile(template)) {
                updateEmptyState();
                return;
            }
            int request = generation.incrementAndGet();
            cancelRequested.set(false);
            statusLabel.setText(HexEditorBundle.message("analysis.status.running"));
            new Task.Backgroundable(project, HexEditorBundle.message("analysis.progress.title"), true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    BtResult result = new BtTemplateEngine().run(template, editor,
                            () -> indicator.isCanceled() || cancelRequested.get() || request != generation.get());
                    SwingUtilities.invokeLater(() -> applyResult(editor, request, result));
                }
            }.queue();
        }

        private void applyResult(HexFileEditor editor, int request, BtResult result) {
            if (request != generation.get() || editor != currentEditor) return;
            if (result.documentRevision() != editor.revision()) {
                scheduleAutoRun();
                return;
            }
            model.setNodes(result.nodes());
            List<HexFileEditor.TemplateHighlight> highlights = new ArrayList<>();
            collectHighlights(result.nodes(), highlights);
            editor.setTemplateHighlights(highlights);
            long errors = result.diagnostics().stream().filter(d -> d.severity() == BtDiagnostic.Severity.ERROR).count();
            long warnings = result.diagnostics().stream().filter(d -> d.severity() == BtDiagnostic.Severity.WARNING).count();
            if (errors > 0) {
                BtDiagnostic first = result.diagnostics().stream()
                        .filter(d -> d.severity() == BtDiagnostic.Severity.ERROR).findFirst().orElseThrow();
                statusLabel.setText(HexEditorBundle.message("analysis.status.failed", first.line(), first.column(), first.message()));
                statusLabel.setToolTipText(first.message());
            } else {
                statusLabel.setText(HexEditorBundle.message("analysis.status.complete", model.getRowCount(), warnings));
                statusLabel.setToolTipText(result.output().isEmpty() ? null : String.join("", result.output()));
            }
            selectNodeForOffset(editor.analysisSelectedOffset());
        }

        private static void collectHighlights(List<BtNode> nodes, List<HexFileEditor.TemplateHighlight> highlights) {
            for (BtNode node : nodes) {
                if (node.size() > 0 && node.backgroundColor() != null) {
                    highlights.add(new HexFileEditor.TemplateHighlight(node.offset(), node.size(), node.backgroundColor()));
                }
                collectHighlights(node.children(), highlights);
            }
        }

        private void editorPropertyChanged(PropertyChangeEvent event) {
            if (HexFileEditor.ANALYSIS_PROPERTY.equals(event.getPropertyName())) {
                scheduleAutoRun();
            } else if (HexFileEditor.BYTE_SELECTION_PROPERTY.equals(event.getPropertyName()) && templatePath != null) {
                selectNodeForOffset(currentEditor == null ? -1 : currentEditor.analysisSelectedOffset());
            }
        }

        private void scheduleAutoRun() {
            if (currentEditor != null && templatePath != null) {
                autoRunTimer.restart();
            }
        }

        private void refreshFromSelection() {
            FileEditor selected = FileEditorManager.getInstance(project).getSelectedEditor();
            if (selected instanceof HexFileEditor editor) attach(editor); else detach();
        }

        private void attach(HexFileEditor editor) {
            if (currentEditor == editor) {
                updateContextLabel();
                return;
            }
            detach();
            currentEditor = editor;
            currentEditor.addPropertyChangeListener(editorListener);
            updateContextLabel();
            scheduleAutoRun();
        }

        private void detach() {
            if (currentEditor != null) {
                currentEditor.removePropertyChangeListener(editorListener);
                currentEditor.setTemplateHighlights(List.of());
                currentEditor = null;
            }
            generation.incrementAndGet();
            autoRunTimer.stop();
            model.setNodes(List.of());
            updateEmptyState();
        }

        private void updateContextLabel() {
            String file = currentEditor == null ? HexEditorBundle.message("analysis.noHexEditor") : currentEditor.getFile().getName();
            String template = templatePath == null ? HexEditorBundle.message("analysis.noTemplate") : templatePath.getFileName().toString();
            contextLabel.setText(file + "  ·  " + template);
        }

        private void updateEmptyState() {
            updateContextLabel();
            if (currentEditor == null) statusLabel.setText(HexEditorBundle.message("analysis.noHexEditor"));
            else if (templatePath == null) statusLabel.setText(HexEditorBundle.message("analysis.noTemplate"));
            else if (!Files.isRegularFile(templatePath)) statusLabel.setText(HexEditorBundle.message("analysis.templateMissing", templatePath));
        }

        private void selectNodeForOffset(long offset) {
            if (templatePath == null) return;
            int row = model.deepestVisibleRow(offset);
            if (row < 0 || row >= table.getRowCount()) return;
            table.getSelectionModel().setSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }

        @Override public void dispose() {
            cancelRequested.set(true);
            detach();
        }

        private final class NameRenderer extends DefaultTableCellRenderer {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                      boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                StructureRow item = model.row(row);
                if (item == null) return this;
                setBorder(JBUI.Borders.emptyLeft(4 + item.depth() * 16));
                if (!item.node().children().isEmpty()) {
                    setIcon(model.isExpanded(item.node()) ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
                } else {
                    setIcon(null);
                }
                return this;
            }
        }

        private static final class MonospaceRenderer extends DefaultTableCellRenderer {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                      boolean focus, int row, int column) {
                Component component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setFont(new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize()));
                return component;
            }
        }
    }

    private record StructureRow(BtNode node, int depth) {}

    private static final class StructureTableModel extends AbstractTableModel {
        private final Set<BtNode> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<StructureRow> rows = new ArrayList<>();
        private List<BtNode> roots = List.of();

        void setNodes(List<BtNode> nodes) {
            roots = List.copyOf(nodes);
            expanded.clear();
            expanded.addAll(roots);
            rebuild();
        }

        StructureRow row(int index) { return index < 0 || index >= rows.size() ? null : rows.get(index); }
        boolean isExpanded(BtNode node) { return expanded.contains(node); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int column) {
            return HexEditorBundle.message(switch (column) {
                case 0 -> "analysis.column.name";
                case 1 -> "analysis.column.value";
                case 2 -> "analysis.column.offset";
                case 3 -> "analysis.column.size";
                default -> "analysis.column.type";
            });
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            BtNode node = rows.get(rowIndex).node();
            return switch (columnIndex) {
                case 0 -> node.name();
                case 1 -> node.value();
                case 2 -> String.format("0x%X", node.offset());
                case 3 -> String.format("0x%X", node.size());
                default -> node.type();
            };
        }

        void toggle(int row) {
            StructureRow item = row(row);
            if (item == null || item.node().children().isEmpty()) return;
            if (!expanded.remove(item.node())) expanded.add(item.node());
            rebuild();
        }
        void expandAll() { addRecursively(roots); rebuild(); }
        void collapseAll() { expanded.clear(); rebuild(); }
        int deepestVisibleRow(long offset) {
            int result = -1;
            int depth = -1;
            for (int i = 0; i < rows.size(); i++) {
                StructureRow row = rows.get(i);
                if (row.depth() >= depth && row.node().contains(offset)) {
                    result = i;
                    depth = row.depth();
                }
            }
            return result;
        }
        private void addRecursively(List<BtNode> nodes) {
            for (BtNode node : nodes) {
                if (!node.children().isEmpty()) expanded.add(node);
                addRecursively(node.children());
            }
        }
        private void rebuild() {
            rows.clear();
            append(roots, 0);
            fireTableDataChanged();
        }
        private void append(List<BtNode> nodes, int depth) {
            for (BtNode node : nodes) {
                rows.add(new StructureRow(node, depth));
                if (expanded.contains(node)) append(node.children(), depth + 1);
            }
        }
    }
}
