package cn.fj.loli.hexsupport;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;

public final class HexOperationHistoryToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        HexOperationHistoryPanel historyPanel = new HexOperationHistoryPanel(project);
        Content historyContent = ContentFactory.getInstance().createContent(historyPanel, "", false);
        historyContent.setDisposer(historyPanel);
        toolWindow.getContentManager().addContent(historyContent);
    }

    private static final class HexOperationHistoryPanel extends JPanel implements Disposable {
        private final Project project;
        private final JBLabel fileLabel = new JBLabel();
        private final JBList<String> historyList = new JBList<>();
        private final MessageBusConnection connection;
        private final PropertyChangeListener historyListener = this::historyChanged;
        private HexFileEditor currentEditor;

        private HexOperationHistoryPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            setBorder(JBUI.Borders.empty(6));

            fileLabel.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
            add(fileLabel, BorderLayout.NORTH);

            historyList.setEmptyText(HexEditorBundle.message("dialog.operationHistory.empty"));
            historyList.setCellRenderer(new HistoryLineRenderer());
            installHistoryPopup();
            add(new JBScrollPane(historyList), BorderLayout.CENTER);

            connection = project.getMessageBus().connect(this);
            connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                    refreshFromSelection();
                }
            });
            refreshFromSelection();
        }

        private void installHistoryPopup() {
            historyList.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    showHistoryPopupIfNeeded(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    showHistoryPopupIfNeeded(event);
                }
            });
        }

        private void showHistoryPopupIfNeeded(MouseEvent event) {
            HexFileEditor editor = currentEditor;
            if (!event.isPopupTrigger() || editor == null) {
                return;
            }
            int index = historyList.locationToIndex(event.getPoint());
            if (index < 0) {
                return;
            }
            Rectangle bounds = historyList.getCellBounds(index, index);
            if (bounds == null || !bounds.contains(event.getPoint())) {
                return;
            }
            historyList.setSelectedIndex(index);
            HexFileEditor.OperationHistorySelection target = editor.operationHistorySelectionAt(index);
            if (target == null) {
                return;
            }
            JMenuItem item = new JMenuItem(HexEditorBundle.message(target.undone()
                    ? "operationHistory.redoToHere"
                    : "operationHistory.undoToHere"));
            item.addActionListener(action -> editor.applyOperationHistorySelection(target));
            JPopupMenu popup = new JPopupMenu();
            popup.add(item);
            popup.show(historyList, event.getX(), event.getY());
            event.consume();
        }

        private void refreshFromSelection() {
            FileEditor selected = FileEditorManager.getInstance(project).getSelectedEditor();
            if (selected instanceof HexFileEditor hexEditor) {
                attach(hexEditor);
            } else {
                detach();
                fileLabel.setText(HexEditorBundle.message("toolWindow.operationHistory.noHexEditor"));
                historyList.setListData(new String[0]);
            }
        }

        private void attach(HexFileEditor editor) {
            if (currentEditor == editor) {
                updateList();
                return;
            }
            detach();
            currentEditor = editor;
            currentEditor.addPropertyChangeListener(historyListener);
            fileLabel.setText(editor.getFile().getName());
            updateList();
        }

        private void detach() {
            if (currentEditor != null) {
                currentEditor.removePropertyChangeListener(historyListener);
                currentEditor = null;
            }
        }

        private void historyChanged(PropertyChangeEvent event) {
            if (HexFileEditor.HISTORY_PROPERTY.equals(event.getPropertyName())) {
                updateList();
            }
        }

        private void updateList() {
            if (currentEditor == null) {
                historyList.setListData(new String[0]);
                return;
            }
            List<String> lines = currentEditor.operationHistoryDisplayLines();
            historyList.setListData(lines.toArray(String[]::new));
            fileLabel.setText(currentEditor.getFile().getName());
        }

        @Override
        public void dispose() {
            detach();
        }
    }

    private static final class HistoryLineRenderer extends DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            java.awt.Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (component instanceof JComponent jComponent) {
                jComponent.setBorder(JBUI.Borders.empty(3, 4));
            }
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, list.getFont().getSize()));
            if (!isSelected && value instanceof String text && (text.contains("Undone") || text.contains("已撤销"))) {
                setForeground(JBColor.GRAY);
            }
            return component;
        }
    }
}
