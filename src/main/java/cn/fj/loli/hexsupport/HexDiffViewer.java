package cn.fj.loli.hexsupport;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.PrevNextDifferenceIterable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.LineSeparator;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class HexDiffViewer implements FrameDiffTool.DiffViewer, UiDataProvider {
    private static final int DEFAULT_BYTES_PER_ROW = 16;
    private static final int MAX_CONTENT_BYTES = 64 * 1024 * 1024;

    private final @Nullable Project project;
    private final ContentDiffRequest request;
    private final JPanel component = new NavigationDataPanel();
    private final JPanel center = new JPanel(new BorderLayout());
    private final JBLabel statusLabel = new JBLabel();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final List<AnAction> toolbarActions = new ArrayList<>();
    private final PrevNextDifferenceIterable differenceIterable = new PrevNextDifferenceIterable() {
        @Override public boolean canGoPrev() { return canNavigate(false); }
        @Override public boolean canGoNext() { return canNavigate(true); }
        @Override public void goPrev() { navigate(false); }
        @Override public void goNext() { navigate(true); }
    };
    private Future<?> loadingTask;
    private JTable leftTable;
    private JTable rightTable;
    private JTable leftGutter;
    private JTable rightGutter;
    private JScrollPane leftScrollPane;
    private JScrollPane rightScrollPane;
    private HexDiffTableModel leftModel;
    private HexDiffTableModel rightModel;
    private HexDiffAlignment alignment;
    private long selectedSourceOffset = -1;
    private int selectedContentIndex = -1;
    private int bytesPerRow = DEFAULT_BYTES_PER_ROW;
    private int activeChange = -1;
    private int currentContentIndex;

    HexDiffViewer(DiffContext context, ContentDiffRequest request) {
        this.project = context.getProject();
        this.request = request;
        component.setBackground(panelBackground());
        center.add(messageLabel(HexEditorBundle.message("diff.loading")), BorderLayout.CENTER);
        component.add(center, BorderLayout.CENTER);
        statusLabel.setBorder(JBUI.Borders.empty(2, 8));
        toolbarActions.add(new BytesPerRowAction());
        installNavigationShortcuts();
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(EditorColorsManager.TOPIC, scheme -> refreshEditorStyle());
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return leftTable;
    }

    @Override
    public @NotNull FrameDiffTool.ToolbarComponents init() {
        startLoading();
        FrameDiffTool.ToolbarComponents result = new FrameDiffTool.ToolbarComponents();
        result.toolbarActions = toolbarActions;
        result.popupActions = List.of();
        result.statusPanel = statusLabel;
        return result;
    }

    private void installNavigationShortcuts() {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "hexDiffNext");
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, KeyEvent.SHIFT_DOWN_MASK), "hexDiffPrevious");
        component.getActionMap().put("hexDiffNext", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) { navigate(true); }
        });
        component.getActionMap().put("hexDiffPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) { navigate(false); }
        });
    }

    private void startLoading() {
        List<DiffContent> contents = request.getContents();
        loadingTask = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                byte[] left = readContent(contents.get(0));
                byte[] right = readContent(contents.get(1));
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
        leftModel = new HexDiffTableModel(left, alignment, true, bytesPerRow);
        rightModel = new HexDiffTableModel(right, alignment, false, bytesPerRow);
        leftTable = createTable(leftModel);
        rightTable = createTable(rightModel);
        leftGutter = createGutter(leftModel);
        rightGutter = createGutter(rightModel);
        trackCurrentSide(leftTable, leftGutter, 0);
        trackCurrentSide(rightTable, rightGutter, 1);
        removeOffsetColumn(leftTable, leftModel);
        removeOffsetColumn(rightTable, rightModel);
        leftScrollPane = createPane(leftTable);
        rightScrollPane = createPane(rightTable);
        rightScrollPane.getVerticalScrollBar().setModel(leftScrollPane.getVerticalScrollBar().getModel());

        JSplitPane splitter = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createSide(leftScrollPane, leftGutter, contentTitle(0), true),
                createSide(rightScrollPane, rightGutter, contentTitle(1), false));
        splitter.setBorder(BorderFactory.createEmptyBorder());
        splitter.setDividerSize(JBUI.scale(1));
        splitter.setResizeWeight(0.5);
        splitter.setContinuousLayout(true);
        center.removeAll();
        center.add(splitter, BorderLayout.CENTER);
        center.revalidate();
        center.repaint();
        updateStatus();
    }

    private JScrollPane createPane(JTable table) {
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 0, 1));
        scrollPane.setColumnHeaderView(table.getTableHeader());
        scrollPane.getViewport().setBackground(editorBackground());
        return scrollPane;
    }

    private JPanel createSide(JScrollPane scrollPane, JTable gutter, String title, boolean gutterOnRight) {
        JBLabel label = new JBLabel(title, SwingConstants.CENTER);
        label.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(5, 8)));

        JViewport gutterViewport = new JViewport();
        gutterViewport.setView(gutter);
        gutterViewport.setPreferredSize(new java.awt.Dimension(
                gutter.getColumnModel().getColumn(0).getPreferredWidth(), 0));
        gutterViewport.setBackground(HexEditorStyle.gutterBackground());
        gutter.addMouseWheelListener(event -> forwardMouseWheelEvent(event, scrollPane));
        scrollPane.getViewport().addChangeListener(event -> {
            java.awt.Point position = gutterViewport.getViewPosition();
            int y = scrollPane.getViewport().getViewPosition().y;
            if (position.y != y) gutterViewport.setViewPosition(new java.awt.Point(0, y));
        });

        JPanel gutterPanel = new JPanel(new BorderLayout());
        gutterPanel.add(gutter.getTableHeader(), BorderLayout.NORTH);
        gutterPanel.add(gutterViewport, BorderLayout.CENTER);
        JPanel scrollbarSpacer = new JPanel();
        scrollbarSpacer.setPreferredSize(new java.awt.Dimension(0,
                scrollPane.getHorizontalScrollBar().getPreferredSize().height));
        gutterPanel.add(scrollbarSpacer, BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout());
        body.add(scrollPane, BorderLayout.CENTER);
        body.add(gutterPanel, gutterOnRight ? BorderLayout.EAST : BorderLayout.WEST);
        JPanel side = new JPanel(new BorderLayout());
        side.add(label, BorderLayout.NORTH);
        side.add(body, BorderLayout.CENTER);
        return side;
    }

    private JTable createGutter(HexDiffTableModel model) {
        JTable gutter = createTable(model);
        for (int view = gutter.getColumnCount() - 1; view >= 0; view--) {
            if (gutter.convertColumnIndexToModel(view) != model.offsetColumn()) {
                gutter.removeColumn(gutter.getColumnModel().getColumn(view));
            }
        }
        return gutter;
    }

    private static void removeOffsetColumn(JTable table, HexDiffTableModel model) {
        int view = table.convertColumnIndexToView(model.offsetColumn());
        if (view >= 0) table.removeColumn(table.getColumnModel().getColumn(view));
    }

    private JTable createTable(HexDiffTableModel model) {
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(false);
        table.setRowSelectionAllowed(false);
        table.setColumnSelectionAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(monospacedFont());
        table.setRowHeight(editorRowHeight(table));
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.emptySize());
        table.setFillsViewportHeight(true);
        table.setBackground(editorBackground());
        table.setForeground(editorForeground());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(editorBackground());
        table.getTableHeader().setForeground(HexEditorStyle.lineNumberForeground());
        table.getTableHeader().setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
        table.setDefaultRenderer(Object.class, new DiffCellRenderer());
        applyColumnWidths(table, model);
        return table;
    }

    private void trackCurrentSide(JTable table, JTable gutter, int contentIndex) {
        FocusAdapter focusListener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { currentContentIndex = contentIndex; }
        };
        MouseAdapter mouseListener = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                currentContentIndex = contentIndex;
                if (event.getComponent() != table) return;
                int row = table.rowAtPoint(event.getPoint());
                int viewColumn = table.columnAtPoint(event.getPoint());
                if (row < 0 || viewColumn < 0) return;
                int modelColumn = table.convertColumnIndexToModel(viewColumn);
                HexDiffTableModel model = (HexDiffTableModel) table.getModel();
                if (!model.isByteColumn(modelColumn)) return;
                int sourceOffset = model.sourceOffsetAt(row, modelColumn);
                if (sourceOffset < 0) return;
                selectedSourceOffset = sourceOffset;
                selectedContentIndex = contentIndex;
                selectChangeAt(model.displayOffsetAt(row, modelColumn));
            }
        };
        table.addFocusListener(focusListener);
        gutter.addFocusListener(focusListener);
        table.addMouseListener(mouseListener);
        gutter.addMouseListener(mouseListener);
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
        if (alignment == null || activeChange < 0 || activeChange >= alignment.changes().size()) return -1;
        HexDiffAlignment.ChangeRange change = alignment.changes().get(activeChange);
        boolean left = contentIndex == 0;
        for (long displayOffset = change.start(); displayOffset < change.end(); displayOffset++) {
            HexDiffAlignment.Segment segment = alignment.segmentAt(displayOffset);
            if (segment == null) continue;
            int sourceOffset = segment.sourceOffset(left, displayOffset);
            if (sourceOffset >= 0) return sourceOffset;
        }
        return -1;
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
            HexDiffViewer.this.uiDataSnapshot(sink);
        }
    }

    private void applyColumnWidths(JTable table, HexDiffTableModel model) {
        int byteWidth = Math.max(JBUI.scale(28),
                table.getFontMetrics(table.getFont()).stringWidth("00") + JBUI.scale(16));
        TableColumn offset = table.getColumnModel().getColumn(model.offsetColumn());
        int offsetWidth = table.getFontMetrics(table.getFont()).stringWidth("0000000000000000") + JBUI.scale(18);
        offset.setPreferredWidth(offsetWidth);
        offset.setMinWidth(offsetWidth);
        offset.setMaxWidth(offsetWidth);
        offset.setHeaderRenderer(new DiffHeaderRenderer());
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (!model.isByteColumn(i)) continue;
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(byteWidth);
            column.setMinWidth(byteWidth);
            column.setMaxWidth(byteWidth);
            column.setHeaderRenderer(new DiffHeaderRenderer());
        }
        TableColumn raw = table.getColumnModel().getColumn(model.rawColumn());
        raw.setPreferredWidth(Math.max(JBUI.scale(80),
                table.getFontMetrics(table.getFont()).charWidth('M') * model.getBytesPerRow() + JBUI.scale(18)));
        raw.setMinWidth(JBUI.scale(80));
        raw.setHeaderRenderer(new DiffHeaderRenderer());
    }

    private void refreshEditorStyle() {
        component.setBackground(panelBackground());
        center.setBackground(editorBackground());
        for (JTable table : new JTable[]{leftTable, rightTable, leftGutter, rightGutter}) {
            if (table == null) continue;
            table.setFont(monospacedFont());
            table.setRowHeight(editorRowHeight(table));
            table.setBackground(editorBackground());
            table.setForeground(editorForeground());
            table.getTableHeader().setBackground(editorBackground());
            table.getTableHeader().setForeground(HexEditorStyle.lineNumberForeground());
        }
        if (leftScrollPane != null) leftScrollPane.getViewport().setBackground(editorBackground());
        if (rightScrollPane != null) rightScrollPane.getViewport().setBackground(editorBackground());
        component.revalidate();
        component.repaint();
    }

    private void setBytesPerRow(int value) {
        if (leftModel == null || value == bytesPerRow) {
            bytesPerRow = value;
            return;
        }
        long firstDisplayOffset = (long) (leftScrollPane.getViewport().getViewPosition().y
                / Math.max(1, leftTable.getRowHeight())) * bytesPerRow;
        bytesPerRow = value;
        leftModel.setBytesPerRow(value);
        rightModel.setBytesPerRow(value);
        leftGutter.setModel(leftModel);
        rightGutter.setModel(rightModel);
        applyColumnWidths(leftTable, leftModel);
        applyColumnWidths(rightTable, rightModel);
        applyColumnWidths(leftGutter, leftModel);
        applyColumnWidths(rightGutter, rightModel);
        removeOffsetColumn(leftTable, leftModel);
        removeOffsetColumn(rightTable, rightModel);
        for (int view = leftGutter.getColumnCount() - 1; view >= 0; view--)
            if (leftGutter.convertColumnIndexToModel(view) != leftModel.offsetColumn()) leftGutter.removeColumn(leftGutter.getColumnModel().getColumn(view));
        for (int view = rightGutter.getColumnCount() - 1; view >= 0; view--)
            if (rightGutter.convertColumnIndexToModel(view) != rightModel.offsetColumn()) rightGutter.removeColumn(rightGutter.getColumnModel().getColumn(view));
        int row = (int) Math.min(Integer.MAX_VALUE, firstDisplayOffset / value);
        leftTable.scrollRectToVisible(leftTable.getCellRect(row, Math.min(1, leftTable.getColumnCount() - 1), true));
    }

    private void navigate(boolean next) {
        if (!canNavigate(next)) return;
        int count = alignment.changes().size();
        activeChange = activeChange < 0 ? (next ? 0 : count - 1)
                : activeChange + (next ? 1 : -1);
        selectedSourceOffset = -1;
        selectedContentIndex = -1;
        HexDiffAlignment.ChangeRange change = alignment.changes().get(activeChange);
        int row = (int) Math.min(Integer.MAX_VALUE, change.start() / bytesPerRow);
        leftTable.scrollRectToVisible(leftTable.getCellRect(row, Math.min(1, leftTable.getColumnCount() - 1), true));
        leftTable.repaint();
        rightTable.repaint();
        updateStatus();
    }

    private void selectChangeAt(long displayOffset) {
        if (alignment == null) return;
        for (int i = 0; i < alignment.changes().size(); i++) {
            HexDiffAlignment.ChangeRange change = alignment.changes().get(i);
            if (displayOffset >= change.start() && displayOffset < change.end()) {
                activeChange = i;
                leftTable.repaint();
                rightTable.repaint();
                updateStatus();
                return;
            }
        }
    }

    private boolean canNavigate(boolean next) {
        if (alignment == null || alignment.changes().isEmpty()) return false;
        return activeChange < 0 || (next
                ? activeChange < alignment.changes().size() - 1
                : activeChange > 0);
    }

    private static void forwardMouseWheelEvent(MouseWheelEvent event, JScrollPane scrollPane) {
        java.awt.Point point = javax.swing.SwingUtilities.convertPoint(
                event.getComponent(), event.getPoint(), scrollPane);
        scrollPane.dispatchEvent(new MouseWheelEvent(
                scrollPane, event.getID(), event.getWhen(), event.getModifiersEx(), point.x, point.y,
                event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(),
                event.getScrollType(), event.getScrollAmount(), event.getWheelRotation(),
                event.getPreciseWheelRotation()));
        event.consume();
    }

    private boolean isActiveCell(HexDiffTableModel model, int row, int column) {
        if (alignment == null || activeChange < 0 || !model.isByteColumn(column)) return false;
        long displayOffset = (long) row * model.getBytesPerRow() + (model == leftModel ? column : column - 1L);
        HexDiffAlignment.ChangeRange range = alignment.changes().get(activeChange);
        return displayOffset >= range.start() && displayOffset < range.end();
    }

    private void updateStatus() {
        if (alignment == null) {
            statusLabel.setText("");
            return;
        }
        int count = alignment.changes().size();
        String differences = count == 0 ? HexEditorBundle.message("diff.identical")
                : activeChange < 0 ? HexEditorBundle.message("diff.differences.total", count)
                : HexEditorBundle.message("diff.differences", activeChange + 1, count);
        if (!alignment.isExact()) differences += "  " + HexEditorBundle.message("diff.coarse");
        statusLabel.setText(differences);
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

    static byte[] readContent(DiffContent content) throws IOException {
        if (content instanceof EmptyContent) return new byte[0];
        if (content instanceof FileContent fileContent) {
            VirtualFile file = fileContent.getFile();
            if (file.getLength() > MAX_CONTENT_BYTES) throw tooLargeException();
            try (InputStream input = file.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_CONTENT_BYTES + 1);
                if (bytes.length > MAX_CONTENT_BYTES) throw tooLargeException();
                return bytes;
            }
        }
        if (content instanceof DocumentContent documentContent) {
            String text = ApplicationManager.getApplication().runReadAction((Computable<String>)
                    () -> documentContent.getDocument().getImmutableCharSequence().toString());
            LineSeparator separator = documentContent.getLineSeparator();
            if (separator != null && separator != LineSeparator.LF) text = text.replace("\n", separator.getSeparatorString());
            Charset charset = documentContent.getCharset();
            if (charset == null) charset = StandardCharsets.UTF_8;
            byte[] body = text.getBytes(charset);
            byte[] bom = Boolean.TRUE.equals(documentContent.hasBom()) ? CharsetToolkit.getPossibleBom(charset) : null;
            int length = body.length + (bom == null ? 0 : bom.length);
            if (length > MAX_CONTENT_BYTES) throw tooLargeException();
            if (bom == null || bom.length == 0) return body;
            byte[] result = Arrays.copyOf(bom, length);
            System.arraycopy(body, 0, result, bom.length, body.length);
            return result;
        }
        throw new IOException(HexEditorBundle.message("diff.unsupported.content"));
    }

    private static IOException tooLargeException() {
        return new IOException(HexEditorBundle.message("diff.file.too.large", MAX_CONTENT_BYTES / 1024 / 1024));
    }

    @Override
    public void dispose() {
        disposed.set(true);
        if (loadingTask != null) loadingTask.cancel(true);
    }

    private final class BytesPerRowAction extends AnAction implements CustomComponentAction {
        private BytesPerRowAction() { super(HexEditorBundle.message("action.bytesPerRow.text")); }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) { }

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

    private final class DiffCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setFont(monospacedFont());
            HexDiffTableModel model = (HexDiffTableModel) table.getModel();
            int modelColumn = table.convertColumnIndexToModel(column);
            boolean offsetColumn = model.isOffsetColumn(modelColumn);
            setHorizontalAlignment(offsetColumn ? (model == leftModel ? SwingConstants.RIGHT : SwingConstants.LEFT) : SwingConstants.CENTER);
            setBorder(offsetColumn ? JBUI.Borders.empty(0, 8) : JBUI.Borders.empty(0, 2));
            setForeground(editorForeground());
            setBackground(editorBackground());
            setToolTipText(null);
            if (offsetColumn) {
                setBackground(HexEditorStyle.gutterBackground());
                setForeground(HexEditorStyle.lineNumberForeground());
            }
            if (model.isByteColumn(modelColumn)) {
                int sourceOffset = model.sourceOffsetAt(row, modelColumn);
                if (sourceOffset >= 0) setToolTipText(String.format("0x%016X", (long) sourceOffset));
                HexDiffAlignment.Kind kind = model.kindAt(row, modelColumn);
                if (kind != HexDiffAlignment.Kind.EQUAL) {
                    setBackground(diffColor(kind));
                    setForeground(HexEditorStyle.diffForeground(kind));
                    if (model.isGapAt(row, modelColumn)) {
                        setText("·");
                        setForeground(JBColor.GRAY);
                    }
                    if (isActiveCell(model, row, modelColumn)) {
                        setBackground(HexEditorStyle.selectionBackground());
                        setForeground(HexEditorStyle.selectionForeground());
                    }
                }
            }
            return this;
        }
    }

    private static final class DiffHeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setOpaque(true);
            Font uiFont = UIManager.getFont("TableHeader.font");
            setFont(uiFont == null ? table.getTableHeader().getFont() : uiFont);
            setHorizontalAlignment(SwingConstants.CENTER);
            HexDiffTableModel model = (HexDiffTableModel) table.getModel();
            int modelColumn = table.convertColumnIndexToModel(column);
            setBackground(model.isOffsetColumn(modelColumn) ? HexEditorStyle.gutterBackground() : editorBackground());
            setForeground(model.isOffsetColumn(modelColumn) ? HexEditorStyle.lineNumberForeground() : editorForeground());
            setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
            return this;
        }
    }

    private static Color diffColor(HexDiffAlignment.Kind kind) {
        return HexEditorStyle.diffBackground(kind);
    }

    private static JComponent messageLabel(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(editorForeground());
        return label;
    }

    private static Font monospacedFont() {
        return HexEditorStyle.font();
    }

    private static Color editorBackground() {
        return HexEditorStyle.editorBackground();
    }

    private static Color editorForeground() {
        return HexEditorStyle.editorForeground();
    }

    private static Color panelBackground() {
        Color color = UIManager.getColor("Panel.background");
        return color == null ? JBColor.PanelBackground : color;
    }

    private static Color borderColor() { return JBColor.border(); }

    private static int editorRowHeight(JTable table) {
        return Math.max(JBUI.scale(18), Math.round(table.getFontMetrics(monospacedFont()).getHeight() * HexEditorStyle.lineSpacing()));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
