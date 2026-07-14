package cn.fj.loli.hexsupport;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.PrevNextDifferenceIterable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class HexUnifiedDiffViewer implements FrameDiffTool.DiffViewer, UiDataProvider {
    private static final int DEFAULT_BYTES_PER_ROW = 16;

    private final @Nullable Project project;
    private final ContentDiffRequest request;
    private final JPanel component = new NavigationDataPanel();
    private final JPanel center = new JPanel(new BorderLayout());
    private final JBLabel statusLabel = new JBLabel();
    private final List<AnAction> toolbarActions = new ArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final PrevNextDifferenceIterable differenceIterable = new PrevNextDifferenceIterable() {
        @Override public boolean canGoPrev() { return canNavigate(false); }
        @Override public boolean canGoNext() { return canNavigate(true); }
        @Override public void goPrev() { navigate(false); }
        @Override public void goNext() { navigate(true); }
    };
    private Future<?> loadingTask;
    private JTable table;
    private JScrollPane scrollPane;
    private HexUnifiedDiffTableModel model;
    private HexDiffAlignment alignment;
    private long selectedSourceOffset = -1;
    private int selectedContentIndex = -1;
    private int bytesPerRow = DEFAULT_BYTES_PER_ROW;
    private int activeChange = -1;
    private int currentContentIndex = 1;

    HexUnifiedDiffViewer(DiffContext context, ContentDiffRequest request) {
        this.project = context.getProject();
        this.request = request;
        center.add(messageLabel(HexEditorBundle.message("diff.loading")), BorderLayout.CENTER);
        component.add(center, BorderLayout.CENTER);
        statusLabel.setBorder(JBUI.Borders.empty(2, 8));
        toolbarActions.add(new BytesPerRowAction());
        installNavigationShortcuts();
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(EditorColorsManager.TOPIC, scheme -> refreshStyle());
    }

    @Override public @NotNull JComponent getComponent() { return component; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return table; }

    @Override
    public @NotNull FrameDiffTool.ToolbarComponents init() {
        startLoading();
        FrameDiffTool.ToolbarComponents result = new FrameDiffTool.ToolbarComponents();
        result.toolbarActions = toolbarActions;
        result.popupActions = List.of();
        result.statusPanel = statusLabel;
        return result;
    }

    private void startLoading() {
        List<DiffContent> contents = request.getContents();
        loadingTask = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                byte[] left = HexDiffViewer.readContent(contents.get(0));
                byte[] right = HexDiffViewer.readContent(contents.get(1));
                HexDiffAlignment result = HexDiffAlignment.build(left, right);
                ApplicationManager.getApplication().invokeLater(
                        () -> installDiff(left, right, result), ModalityState.any());
            } catch (Throwable error) {
                ApplicationManager.getApplication().invokeLater(() -> showError(error), ModalityState.any());
            }
        });
    }

    private void installDiff(byte[] left, byte[] right, HexDiffAlignment alignment) {
        if (disposed.get()) return;
        this.alignment = alignment;
        model = new HexUnifiedDiffTableModel(left, right, alignment, bytesPerRow);
        table = createTable(model);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row < 0) return;
                HexDiffAlignment.Kind kind = model.kindAt(row);
                if (kind == HexDiffAlignment.Kind.REMOVED || column == model.oldOffsetColumn()) currentContentIndex = 0;
                else if (kind == HexDiffAlignment.Kind.ADDED || column == model.newOffsetColumn()) currentContentIndex = 1;
                if (column >= 0 && model.isByteColumn(column)) {
                    int sourceOffset = model.sourceOffsetAt(row, column, currentContentIndex == 0);
                    if (sourceOffset >= 0) {
                        selectedSourceOffset = sourceOffset;
                        selectedContentIndex = currentContentIndex;
                        int changeIndex = model.changeIndexAt(row);
                        if (changeIndex >= 0) {
                            activeChange = changeIndex;
                            table.repaint();
                            updateStatus();
                        }
                    }
                }
            }
        });
        scrollPane = new JScrollPane(table);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getViewport().setBackground(editorBackground());

        JBLabel title = new JBLabel(contentTitle(0) + "  →  " + contentTitle(1), SwingConstants.CENTER);
        title.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0), JBUI.Borders.empty(5, 8)));
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(table.getTableHeader(), BorderLayout.CENTER);
        scrollPane.setColumnHeaderView(header);

        center.removeAll();
        center.add(scrollPane, BorderLayout.CENTER);
        center.revalidate();
        center.repaint();
        updateStatus();
    }

    private JTable createTable(HexUnifiedDiffTableModel model) {
        JTable result = new JTable(model);
        result.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        result.setCellSelectionEnabled(false);
        result.setRowSelectionAllowed(false);
        result.setColumnSelectionAllowed(false);
        result.setFont(HexEditorStyle.font());
        result.setRowHeight(rowHeight(result));
        result.setShowGrid(false);
        result.setIntercellSpacing(JBUI.emptySize());
        result.setFillsViewportHeight(true);
        result.setBackground(editorBackground());
        result.setForeground(editorForeground());
        result.getTableHeader().setReorderingAllowed(false);
        result.getTableHeader().setBackground(editorBackground());
        result.getTableHeader().setForeground(HexEditorStyle.lineNumberForeground());
        result.getTableHeader().setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
        result.setDefaultRenderer(Object.class, new UnifiedCellRenderer());
        applyColumnWidths(result, model);
        return result;
    }

    private Navigatable currentNavigatable() {
        List<DiffContent> contents = request.getContents();
        if (contents.isEmpty()) return null;
        int preferred = Math.min(currentContentIndex, contents.size() - 1);
        long preferredOffset = sourceOffset(preferred);
        Navigatable navigatable = preferredOffset >= 0 || activeChange < 0
                ? HexDiffNavigatable.forContent(project, contents.get(preferred), preferredOffset) : null;
        if (navigatable != null) return navigatable;
        for (int i = 0; i < contents.size(); i++) {
            long offset = sourceOffset(i);
            if (offset < 0 && activeChange >= 0) continue;
            navigatable = HexDiffNavigatable.forContent(project, contents.get(i), offset);
            if (navigatable != null) {
                currentContentIndex = i;
                return navigatable;
            }
        }
        return null;
    }

    private long sourceOffset(int contentIndex) {
        if (selectedContentIndex == contentIndex && selectedSourceOffset >= 0) return selectedSourceOffset;
        return model == null || activeChange < 0 ? -1
                : model.sourceOffsetForChange(activeChange, contentIndex == 0);
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        Navigatable navigatable = currentNavigatable();
        if (project != null) sink.set(CommonDataKeys.PROJECT, project);
        sink.set(DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE, differenceIterable);
        if (navigatable != null) {
            sink.set(DiffDataKeys.NAVIGATABLE, navigatable);
            sink.set(DiffDataKeys.NAVIGATABLE_ARRAY, new Navigatable[]{navigatable});
            sink.set(DiffDataKeys.CURRENT_CONTENT, request.getContents().get(currentContentIndex));
        }
    }

    private final class NavigationDataPanel extends JPanel implements UiDataProvider {
        private NavigationDataPanel() { super(new BorderLayout()); }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            HexUnifiedDiffViewer.this.uiDataSnapshot(sink);
        }
    }

    private void applyColumnWidths(JTable table, HexUnifiedDiffTableModel model) {
        int offsetWidth = table.getFontMetrics(table.getFont()).stringWidth("0000000000000000") + JBUI.scale(18);
        int byteWidth = Math.max(JBUI.scale(28), table.getFontMetrics(table.getFont()).stringWidth("00") + JBUI.scale(16));
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            if (model.isOffsetColumn(i)) {
                column.setMinWidth(offsetWidth);
                column.setMaxWidth(offsetWidth);
                column.setPreferredWidth(offsetWidth);
            } else if (model.isByteColumn(i)) {
                column.setMinWidth(byteWidth);
                column.setMaxWidth(byteWidth);
                column.setPreferredWidth(byteWidth);
            } else {
                column.setMinWidth(JBUI.scale(80));
                column.setPreferredWidth(Math.max(JBUI.scale(80),
                        table.getFontMetrics(table.getFont()).charWidth('M') * model.getBytesPerRow() + JBUI.scale(18)));
            }
            column.setHeaderRenderer(new UnifiedHeaderRenderer());
        }
    }

    private void setBytesPerRow(int value) {
        bytesPerRow = value;
        if (model == null) return;
        model.setBytesPerRow(value);
        applyColumnWidths(table, model);
        if (activeChange >= 0 && activeChange < model.changeRows().size()) scrollToRow(model.changeRows().get(activeChange));
    }

    private void navigate(boolean next) {
        if (!canNavigate(next)) return;
        int count = model.changeRows().size();
        activeChange = activeChange < 0 ? (next ? 0 : count - 1)
                : activeChange + (next ? 1 : -1);
        selectedSourceOffset = -1;
        selectedContentIndex = -1;
        scrollToRow(model.changeRows().get(activeChange));
        table.repaint();
        updateStatus();
    }

    private boolean canNavigate(boolean next) {
        if (model == null || model.changeRows().isEmpty()) return false;
        return activeChange < 0 || (next
                ? activeChange < model.changeRows().size() - 1
                : activeChange > 0);
    }

    private void scrollToRow(int row) {
        table.scrollRectToVisible(table.getCellRect(row, Math.min(2, table.getColumnCount() - 1), true));
    }

    private void installNavigationShortcuts() {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "hexUnifiedNext");
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, KeyEvent.SHIFT_DOWN_MASK), "hexUnifiedPrevious");
        component.getActionMap().put("hexUnifiedNext", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { navigate(true); }
        });
        component.getActionMap().put("hexUnifiedPrevious", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { navigate(false); }
        });
    }

    private void updateStatus() {
        if (alignment == null) { statusLabel.setText(""); return; }
        int count = alignment.changes().size();
        String text = count == 0 ? HexEditorBundle.message("diff.identical")
                : activeChange < 0 ? HexEditorBundle.message("diff.differences.total", count)
                : HexEditorBundle.message("diff.differences", activeChange + 1, count);
        if (!alignment.isExact()) text += "  " + HexEditorBundle.message("diff.coarse");
        statusLabel.setText(text);
    }

    private void showError(Throwable error) {
        if (disposed.get()) return;
        center.removeAll();
        center.add(messageLabel(HexEditorBundle.message("diff.load.failed", rootMessage(error))), BorderLayout.CENTER);
        center.revalidate();
        center.repaint();
        statusLabel.setText("");
    }

    private String contentTitle(int index) {
        List<String> titles = request.getContentTitles();
        if (index < titles.size() && titles.get(index) != null && !titles.get(index).isBlank()) return titles.get(index);
        return HexEditorBundle.message(index == 0 ? "diff.left" : "diff.right");
    }

    private void refreshStyle() {
        if (table == null) return;
        table.setFont(HexEditorStyle.font());
        table.setRowHeight(rowHeight(table));
        table.setBackground(editorBackground());
        table.setForeground(editorForeground());
        table.getTableHeader().setBackground(editorBackground());
        table.getTableHeader().setForeground(HexEditorStyle.lineNumberForeground());
        if (scrollPane != null) scrollPane.getViewport().setBackground(editorBackground());
        component.repaint();
    }

    @Override
    public void dispose() {
        disposed.set(true);
        if (loadingTask != null) loadingTask.cancel(true);
    }

    private final class BytesPerRowAction extends AnAction implements CustomComponentAction {
        private BytesPerRowAction() { super(HexEditorBundle.message("action.bytesPerRow.text")); }
        @Override public void actionPerformed(@NotNull AnActionEvent event) { }
        @Override
        public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            panel.setOpaque(false);
            panel.add(new JBLabel(HexEditorBundle.message("label.bytesPerRow")));
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(bytesPerRow, 4, 256, 4));
            spinner.addChangeListener(event -> setBytesPerRow((Integer) spinner.getValue()));
            panel.add(spinner);
            return panel;
        }
    }

    private final class UnifiedCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setFont(HexEditorStyle.font());
            boolean offset = model.isOffsetColumn(column);
            setHorizontalAlignment(offset ? SwingConstants.RIGHT : SwingConstants.CENTER);
            setBorder(offset ? JBUI.Borders.emptyRight(8) : JBUI.Borders.empty(0, 2));
            setBackground(offset ? HexEditorStyle.gutterBackground() : editorBackground());
            setForeground(offset ? HexEditorStyle.lineNumberForeground() : editorForeground());
            setToolTipText(null);
            HexDiffAlignment.Kind kind = model.kindAt(row);
            if (!offset && kind != HexDiffAlignment.Kind.EQUAL) {
                setBackground(HexEditorStyle.diffBackground(kind));
                setForeground(HexEditorStyle.diffForeground(kind));
                if (model.changeIndexAt(row) == activeChange) {
                    setBackground(HexEditorStyle.selectionBackground());
                    setForeground(HexEditorStyle.selectionForeground());
                }
            }
            if (model.isByteColumn(column)) {
                int sourceOffset = model.sourceOffsetAt(row, column);
                if (sourceOffset >= 0) setToolTipText(String.format("0x%016X", (long) sourceOffset));
            }
            return this;
        }
    }

    private static final class UnifiedHeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            HexUnifiedDiffTableModel model = (HexUnifiedDiffTableModel) table.getModel();
            Font uiFont = UIManager.getFont("TableHeader.font");
            setFont(uiFont == null ? table.getTableHeader().getFont() : uiFont);
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBackground(model.isOffsetColumn(column) ? HexEditorStyle.gutterBackground() : editorBackground());
            setForeground(model.isOffsetColumn(column) ? HexEditorStyle.lineNumberForeground() : editorForeground());
            setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
            return this;
        }
    }

    private static JComponent messageLabel(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(editorForeground());
        return label;
    }

    private static int rowHeight(JTable table) {
        return Math.max(JBUI.scale(18), Math.round(table.getFontMetrics(HexEditorStyle.font()).getHeight()
                * HexEditorStyle.lineSpacing()));
    }

    private static Color editorBackground() { return HexEditorStyle.editorBackground(); }
    private static Color editorForeground() { return HexEditorStyle.editorForeground(); }
    private static Color borderColor() { return JBColor.border(); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
