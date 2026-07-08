package cn.fj.loli.hexsupport;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.DefaultCellEditor;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EventObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class HexFileEditor extends UserDataHolderBase implements FileEditor {
    private static final int DEFAULT_BYTES_PER_ROW = 16;
    private static final String MODIFIED_PROPERTY = "modified";

    private final Project project;
    private final VirtualFile file;
    private final PropertyChangeSupport changeSupport = new PropertyChangeSupport(this);
    private final JPanel component = new JPanel(new BorderLayout());
    private final HexTableModel model;
    private final JTable table;
    private final Deque<byte[]> undoStack = new ArrayDeque<>();
    private final Deque<byte[]> redoStack = new ArrayDeque<>();
    private final List<Selection> searchMatches = new ArrayList<>();
    private JPanel findPanel;
    private JPanel replaceRow;
    private SearchTextField findField;
    private SearchTextField replaceField;
    private SearchTextField stringFindField;
    private JButton toggleReplaceButton;
    private final JBLabel matchLabel = new JBLabel("");
    private int activeMatchIndex = -1;
    private final List<Selection> selections = new ArrayList<>();
    private int activeIndex = -1;
    private int anchor = -1;
    private int caret = -1;
    private final StringBuilder multiEditBuffer = new StringBuilder();
    private boolean modified;
    private boolean searchActive = false;
    private boolean syncingFields = false;

    public HexFileEditor(Project project, VirtualFile file) {
        this.project = project;
        this.file = file;
        this.model = new HexTableModel(readBytes(file), DEFAULT_BYTES_PER_ROW, StandardCharsets.UTF_8);
        this.table = new HexTable(model);

        component.setBackground(panelBackground());
        configureTable();

        component.add(createTopPanel(), BorderLayout.NORTH);
        component.add(wrap(table), BorderLayout.CENTER);
        updateStatus(selectedOffset());
    }

    private static byte[] readBytes(VirtualFile file) {
        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<byte[]>) () -> {
            try {
                return file.contentsToByteArray();
            } catch (IOException exception) {
                return new byte[0];
            }
        });
    }

    private void configureTable() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.setRowSelectionAllowed(false);
        table.setFont(monospacedFont());
        table.setRowHeight(JBUI.scale(22));
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.emptySize());
        table.setFillsViewportHeight(true);
        table.setBackground(editorBackground());
        table.setForeground(editorForeground());
        table.setSelectionBackground(selectionBackground());
        table.setSelectionForeground(selectionForeground());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(panelBackground());
        table.getTableHeader().setForeground(editorForeground());
        table.getTableHeader().setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
        model.setByteChangeListener(() -> {
            setModified(true);
            refreshActiveSearch();
        });
        table.setDefaultEditor(Object.class, new HexCellEditor());
        installHexKeyBindings();
        installByteSelectionHandler();
        applyColumnWidths();
    }

    private void installHexKeyBindings() {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "hexMoveRight");
        table.getActionMap().put("hexMoveRight", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(1);
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "hexTabEdit");
        table.getActionMap().put("hexTabEdit", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                handleTabKey();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK), "hexExtendRight");
        table.getActionMap().put("hexExtendRight", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                extendSelection(1);
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "hexMoveLeft");
        table.getActionMap().put("hexMoveLeft", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(-1);
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK), "hexExtendLeft");
        table.getActionMap().put("hexExtendLeft", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                extendSelection(-1);
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "hexMoveDown");
        table.getActionMap().put("hexMoveDown", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(model.getBytesPerRow());
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.SHIFT_DOWN_MASK), "hexExtendDown");
        table.getActionMap().put("hexExtendDown", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                extendSelection(model.getBytesPerRow());
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "hexMoveUp");
        table.getActionMap().put("hexMoveUp", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(-model.getBytesPerRow());
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.SHIFT_DOWN_MASK), "hexExtendUp");
        table.getActionMap().put("hexExtendUp", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                extendSelection(-model.getBytesPerRow());
            }
        });

        // JTable's default WHEN_FOCUSED input map (Table.focusInputMap) also binds the arrow
        // keys to its own selection actions, which take precedence over our WHEN_ANCESTOR
        // bindings when the table itself has focus (non-edit mode). Mirror our arrow-key
        // bindings onto WHEN_FOCUSED so they win in both edit and non-edit modes.
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "hexMoveRight");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "hexMoveLeft");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "hexMoveDown");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "hexMoveUp");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK), "hexExtendRight");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK), "hexExtendLeft");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.SHIFT_DOWN_MASK), "hexExtendDown");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.SHIFT_DOWN_MASK), "hexExtendUp");

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hexClearSelection");
        table.getActionMap().put("hexClearSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                commitMultiEditIfNeeded();
                if (!selections.isEmpty() || searchActive || !searchMatches.isEmpty()) {
                    clearSearchResults();
                    clearByteSelection();
                } else if (findPanel != null && findPanel.isVisible()) {
                    hideFindPanel();
                }
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "hexStartEdit");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "hexStartEdit");
        table.getActionMap().put("hexStartEdit", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isDirectEditingBlocked()) {
                    return;
                }
                startEditingSelectedCell();
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "hexZeroSelection");
        table.getActionMap().put("hexZeroSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isDirectEditingBlocked()) {
                    return;
                }
                zeroSelectedBytes();
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "hexDeleteSelection");
        table.getActionMap().put("hexDeleteSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isDirectEditingBlocked()) {
                    return;
                }
                deleteSelectedBytes();
            }
        });

        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask), "hexPaste");
        table.getActionMap().put("hexPaste", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isDirectEditingBlocked()) {
                    return;
                }
                pasteHexIntoSelection();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "hexCopy");
        table.getActionMap().put("hexCopy", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                copySelectionToClipboard();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "hexUndo");
        table.getActionMap().put("hexUndo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                undo();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | KeyEvent.SHIFT_DOWN_MASK), "hexRedo");
        table.getActionMap().put("hexRedo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                redo();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask), "hexSave");
        table.getActionMap().put("hexSave", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                save();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask), "hexSelectAll");
        table.getActionMap().put("hexSelectAll", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                selectAllBytes();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask | KeyEvent.SHIFT_DOWN_MASK), "hexInvertSelection");
        table.getActionMap().put("hexInvertSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                invertSelection();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_G, menuMask), "hexGoToOffset");
        table.getActionMap().put("hexGoToOffset", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                goToOffset();
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "hexFind");
        table.getActionMap().put("hexFind", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                showFindPanel(false);
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), "hexReplace");
        table.getActionMap().put("hexReplace", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                showFindPanel(true);
            }
        });

        for (char ch = '0'; ch <= '9'; ch++) {
            installStartEditKey(ch);
        }
        for (char ch = 'a'; ch <= 'f'; ch++) {
            installStartEditKey(ch);
            installStartEditKey(Character.toUpperCase(ch));
        }
    }

    private void installStartEditKey(char ch) {
        String actionKey = "hexType" + ch;
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(ch), actionKey);
        table.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (isDirectEditingBlocked()) {
                    return;
                }
                if (countSelectedCells() > 1) {
                    handleMultiEditKey(ch);
                } else {
                    startEditingSelectedCell(String.valueOf(ch));
                }
            }
        });
    }

    private void installByteSelectionHandler() {
        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                handleMousePress(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                handleMouseDrag(event);
            }
        };
        table.addMouseListener(handler);
        table.addMouseMotionListener(handler);
    }

    private void handleMousePress(MouseEvent event) {
        if (event.isPopupTrigger() || !SwingUtilities.isLeftMouseButton(event)) {
            return;
        }
        int offset = byteOffsetAtMouse(event);
        if (offset < 0) {
            return;
        }
        commitMultiEditIfNeeded();
        if (event.getClickCount() < 2 && table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        boolean ctrl = (event.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK) != 0;
        boolean shift = (event.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0;
        if (ctrl && !shift) {
            clearSearchResults();
            if (isOffsetInSelection(offset)) {
                removeOffsetFromSelection(offset);
            } else {
                addSelection(offset);
            }
        } else if (ctrl) {
            clearSearchResults();
            addSelection(offset);
        } else if (shift) {
            extendActiveTo(offset);
        } else {
            int matchIndex = searchActive ? findMatchIndexContaining(offset) : -1;
            if (matchIndex >= 0) {
                activateSearchMatch(matchIndex);
            } else {
                clearSearchResults();
                setSelectionRange(offset, offset);
            }
        }
        if (event.getClickCount() < 2) {
            table.requestFocusInWindow();
        }
    }

    private void handleMouseDrag(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event)) {
            return;
        }
        int offset = byteOffsetAtMouse(event);
        if (offset < 0) {
            return;
        }
        if (anchor >= 0) {
            extendActiveTo(offset);
        }
    }

    private int byteOffsetAtMouse(MouseEvent event) {
        int row = table.rowAtPoint(event.getPoint());
        int column = table.columnAtPoint(event.getPoint());
        return model.byteIndexAt(row, column);
    }

    private JScrollPane wrap(JComponent child) {
        JScrollPane scrollPane = new JScrollPane(child);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(editorBackground());
        return scrollPane;
    }

    private JComponent createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(panelBackground());
        topPanel.add(createToolbar(), BorderLayout.NORTH);
        topPanel.add(createFindPanel(), BorderLayout.SOUTH);
        return topPanel;
    }

    private JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ToolbarAction(HexEditorBundle.message("action.save.text"), HexEditorBundle.message("action.save.description"), AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                save();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.saveAs.text"), HexEditorBundle.message("action.saveAs.description"), AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                saveAs();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.reload.text"), HexEditorBundle.message("action.reload.description"), AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                reload();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.undo.text"), HexEditorBundle.message("action.undo.description"), AllIcons.Actions.Undo) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                undo();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.redo.text"), HexEditorBundle.message("action.redo.description"), AllIcons.Actions.Redo) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                redo();
            }
        });
        group.addSeparator();
        group.add(new BytesPerRowAction());
        group.addSeparator();
        group.add(new ToolbarAction(HexEditorBundle.message("action.goToOffset.text"), HexEditorBundle.message("action.goToOffset.description"), AllIcons.General.Locate) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                goToOffset();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.find.text"), HexEditorBundle.message("action.find.description"), AllIcons.Actions.Search) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                showFindPanel(false);
            }
        });
        group.addSeparator();
        group.add(new ToolbarAction(HexEditorBundle.message("action.copy.text"), HexEditorBundle.message("action.copy.description"), AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                copySelectionToClipboard();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.cut.text"), HexEditorBundle.message("action.cut.description"), AllIcons.Actions.MenuCut) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                cutSelectionToClipboard();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.pasteBefore.text"), HexEditorBundle.message("action.pasteBefore.description"), AllIcons.Actions.MenuPaste) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                pasteHexBeforeSelection();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.pasteAfter.text"), HexEditorBundle.message("action.pasteAfter.description"), AllIcons.Actions.MenuPaste) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                pasteHexAfterSelection();
            }
        });
        group.addSeparator();
        group.add(new ToolbarAction(HexEditorBundle.message("action.insert1ZeroBefore.text"), HexEditorBundle.message("action.insert1ZeroBefore.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                insertZerosRelative(true, 1);
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.insert1ZeroAfter.text"), HexEditorBundle.message("action.insert1ZeroAfter.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                insertZerosRelative(false, 1);
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.insertNZerosBefore.text"), HexEditorBundle.message("action.insertNZerosBefore.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                askAndInsertZeros(true);
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.insertNZerosAfter.text"), HexEditorBundle.message("action.insertNZerosAfter.description"), AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                askAndInsertZeros(false);
            }
        });
        group.addSeparator();
        group.add(new ToolbarAction(HexEditorBundle.message("action.fragmentExport.text"), HexEditorBundle.message("action.fragmentExport.description"), AllIcons.General.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                exportFragment();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.importHead.text"), HexEditorBundle.message("action.importHead.description"), AllIcons.ToolbarDecorator.Import) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                importFragmentAtStart();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.importTail.text"), HexEditorBundle.message("action.importTail.description"), AllIcons.ToolbarDecorator.Import) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                importFragmentAtEnd();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.importAfterSelection.text"), HexEditorBundle.message("action.importAfterSelection.description"), AllIcons.ToolbarDecorator.Import) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                importFragmentAfterSelection();
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HexEditor.Toolbar", group, true);
        toolbar.setTargetComponent(component);
        JComponent toolbarComponent = toolbar.getComponent();
        toolbarComponent.setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
        return toolbarComponent;
    }

    private JComponent createFindPanel() {
        findPanel = new JPanel(new BorderLayout());
        findPanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 8)));
        findPanel.setBackground(panelBackground());
        findPanel.setVisible(false);

        findField = createSearchField();
        replaceField = createSearchField();

        toggleReplaceButton = new JButton(AllIcons.General.ArrowRight);
        toggleReplaceButton.setToolTipText(HexEditorBundle.message("button.toggleReplace.show.tooltip"));
        toggleReplaceButton.setBorderPainted(false);
        toggleReplaceButton.setContentAreaFilled(false);
        toggleReplaceButton.setFocusPainted(false);
        toggleReplaceButton.setOpaque(false);
        toggleReplaceButton.setMargin(JBUI.emptyInsets());
        toggleReplaceButton.addActionListener(event -> setReplaceVisible(replaceRow == null || !replaceRow.isVisible()));
        Dimension toggleSize = JBUI.size(22, 22);
        toggleReplaceButton.setPreferredSize(toggleSize);
        toggleReplaceButton.setMinimumSize(toggleSize);
        toggleReplaceButton.setMaximumSize(toggleSize);

        JPanel findRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        findRow.setOpaque(false);
        findRow.add(toggleReplaceButton, BorderLayout.WEST);
        findRow.add(findField, BorderLayout.CENTER);

        stringFindField = createSearchField();
        stringFindField.getTextEditor().setColumns(16);

        JPanel stringArea = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        stringArea.setOpaque(false);
        stringArea.add(stringFindField);
        stringArea.add(createFindControls());
        findRow.add(stringArea, BorderLayout.EAST);

        JPanel replaceSpacer = new JPanel(null);
        replaceSpacer.setOpaque(false);
        replaceSpacer.setPreferredSize(toggleSize);
        replaceSpacer.setMinimumSize(toggleSize);
        replaceSpacer.setMaximumSize(toggleSize);

        replaceRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        replaceRow.setOpaque(false);
        replaceRow.add(replaceSpacer, BorderLayout.WEST);
        replaceRow.add(replaceField, BorderLayout.CENTER);
        replaceRow.add(createReplaceControls(), BorderLayout.EAST);
        replaceRow.setVisible(false);

        JPanel content = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(4)));
        content.setOpaque(false);
        content.add(findRow, BorderLayout.NORTH);
        content.add(replaceRow, BorderLayout.SOUTH);
        findPanel.add(content, BorderLayout.CENTER);

        findField.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                onHexChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                onHexChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                onHexChanged();
            }
        });
        installFindFieldKeyBindings(findField.getTextEditor());
        installReplaceFieldKeyBindings(replaceField.getTextEditor());
        findField.getTextEditor().addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                restoreSearchSelection();
            }
        });
        replaceField.getTextEditor().addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                restoreSearchSelection();
            }
        });
        stringFindField.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                if (!syncingFields) {
                    syncStringToHex();
                }
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                if (!syncingFields) {
                    syncStringToHex();
                }
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                if (!syncingFields) {
                    syncStringToHex();
                }
            }
        });
        installStringFindFieldKeyBindings(stringFindField.getTextEditor());
        stringFindField.getTextEditor().addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                restoreSearchSelection();
            }
        });
        return findPanel;
    }

    private SearchTextField createSearchField() {
        SearchTextField field = new SearchTextField() {
            @Override
            protected boolean toClearTextOnEscape() {
                return false;
            }
        };
        field.getTextEditor().setColumns(24);
        return field;
    }

    private JComponent createFindControls() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ToolbarAction(HexEditorBundle.message("action.previousOccurrence.text"), HexEditorBundle.message("action.previousOccurrence.text"), AllIcons.Actions.PreviousOccurence) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                activateSearchMatch(activeMatchIndex - 1);
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.nextOccurrence.text"), HexEditorBundle.message("action.nextOccurrence.text"), AllIcons.Actions.NextOccurence) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                activateSearchMatch(activeMatchIndex + 1);
            }
        });
        group.add(new MatchCountAction());
        group.add(new ToolbarAction(HexEditorBundle.message("action.close.text"), HexEditorBundle.message("action.close.text"), AllIcons.Actions.Close) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                hideFindPanel();
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HexEditor.FindBar", group, true);
        toolbar.setTargetComponent(component);
        JComponent toolbarComponent = toolbar.getComponent();
        toolbarComponent.setOpaque(false);
        return toolbarComponent;
    }

    private JComponent createReplaceControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        panel.setOpaque(false);
        JButton replaceButton = new JButton(HexEditorBundle.message("button.replace.text"));
        replaceButton.setToolTipText(HexEditorBundle.message("button.replace.tooltip"));
        replaceButton.addActionListener(event -> replaceCurrentMatch());
        JButton replaceAllButton = new JButton(HexEditorBundle.message("button.replaceAll.text"));
        replaceAllButton.setToolTipText(HexEditorBundle.message("button.replaceAll.tooltip"));
        replaceAllButton.addActionListener(event -> replaceAllMatches());
        JButton deleteButton = new JButton(HexEditorBundle.message("button.delete.text"));
        deleteButton.setToolTipText(HexEditorBundle.message("button.delete.tooltip"));
        deleteButton.addActionListener(event -> deleteCurrentSelection());
        JButton deleteAllButton = new JButton(HexEditorBundle.message("button.deleteAll.text"));
        deleteAllButton.setToolTipText(HexEditorBundle.message("button.deleteAll.tooltip"));
        deleteAllButton.addActionListener(event -> deleteAllSelections());
        JButton zeroButton = new JButton(HexEditorBundle.message("button.zero.text"));
        zeroButton.setToolTipText(HexEditorBundle.message("button.zero.tooltip"));
        zeroButton.addActionListener(event -> zeroCurrentSelection());
        JButton zeroAllButton = new JButton(HexEditorBundle.message("button.zeroAll.text"));
        zeroAllButton.setToolTipText(HexEditorBundle.message("button.zeroAll.tooltip"));
        zeroAllButton.addActionListener(event -> zeroAllSelections());
        panel.add(replaceButton);
        panel.add(replaceAllButton);
        panel.add(deleteButton);
        panel.add(deleteAllButton);
        panel.add(zeroButton);
        panel.add(zeroAllButton);
        return panel;
    }

    private void installFindFieldKeyBindings(JComponent field) {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), "toggleReplace");
        field.getActionMap().put("toggleReplace", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setReplaceVisible(replaceRow == null || !replaceRow.isVisible());
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "focusFind");
        field.getActionMap().put("focusFind", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setReplaceVisible(false);
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "findNext");
        field.getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSearchMatch(activeMatchIndex + 1);
            }
        });
        installNavigationKeyBindings(field);
    }

    private void installReplaceFieldKeyBindings(JComponent field) {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), "toggleReplace");
        field.getActionMap().put("toggleReplace", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setReplaceVisible(replaceRow == null || !replaceRow.isVisible());
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "focusFind");
        field.getActionMap().put("focusFind", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                setReplaceVisible(false);
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "replaceCurrent");
        field.getActionMap().put("replaceCurrent", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                replaceCurrentMatch();
            }
        });
        installNavigationKeyBindings(field);
    }

    private void installNavigationKeyBindings(JComponent field) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "findPrevious");
        field.getActionMap().put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSearchMatch(activeMatchIndex - 1);
            }
        });
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        field.getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                hideFindPanel();
            }
        });
    }

    private void installStringFindFieldKeyBindings(JComponent field) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "findNext");
        field.getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSearchMatch(activeMatchIndex + 1);
            }
        });
        installNavigationKeyBindings(field);
    }

    private void applyColumnWidths() {
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            TableColumn column = columns.getColumn(i);
            if (i == 0) {
                column.setPreferredWidth(JBUI.scale(82));
            } else if (i == columns.getColumnCount() - 1) {
                column.setPreferredWidth(JBUI.scale(210));
            } else {
                column.setPreferredWidth(JBUI.scale(36));
            }
            DefaultTableCellRenderer renderer;
            if (i == 0) {
                renderer = new OffsetRenderer();
            } else if (i == columns.getColumnCount() - 1) {
                renderer = new RawPreviewRenderer();
            } else {
                renderer = new HexByteRenderer();
            }
            renderer.setHorizontalAlignment(i == columns.getColumnCount() - 1 ? SwingConstants.LEFT : SwingConstants.CENTER);
            renderer.setBackground(editorBackground());
            renderer.setForeground(editorForeground());
            column.setCellRenderer(renderer);
            column.setHeaderRenderer(new HexHeaderRenderer());
        }
    }

    private void moveSelection(int delta) {
        commitMultiEditIfNeeded();
        if (model.getDataLength() == 0) {
            return;
        }
        clearSearchResults();
        if (selections.isEmpty()) {
            setSelectionRange(0, 0);
            return;
        }
        int firstCell = firstSelectedOffset();
        int lastCell = lastSelectedOffset();
        int bytesPerRow = model.getBytesPerRow();
        int target;
        if (selections.size() == 1 && selections.get(0).length() == 1) {
            // Single byte selection: natural offset arithmetic handles line-end/start wrap.
            target = firstCell + delta;
        } else {
            // Multi-byte selection (single range with length > 1, or multiple ranges):
            // Up/Down/Left use first cell, Right uses last cell.
            if (delta == 1) {
                target = lastCell + 1;
            } else if (delta == -1) {
                target = firstCell - 1;
            } else if (delta == bytesPerRow) {
                target = firstCell + bytesPerRow;
            } else if (delta == -bytesPerRow) {
                target = firstCell - bytesPerRow;
            } else {
                target = firstCell + delta;
            }
        }
        if (target < 0 || target >= model.getDataLength()) {
            return;
        }
        setSelectionRange(target, target);
    }

    private void extendSelection(int delta) {
        commitMultiEditIfNeeded();
        clearSearchResults();
        int current = selectedOffset();
        if (current < 0 || model.getDataLength() == 0) {
            return;
        }
        int target = clampOffset(current + delta);
        extendActiveTo(target);
    }

    private int clampOffset(int offset) {
        if (model.getDataLength() == 0) {
            return 0;
        }
        if (offset < 0) {
            return 0;
        }
        if (offset >= model.getDataLength()) {
            return model.getDataLength() - 1;
        }
        return offset;
    }

    private int countSelectedCells() {
        int count = 0;
        for (Selection selection : selections) {
            count += selection.length();
        }
        return count;
    }

    private void handleMultiEditKey(char ch) {
        multiEditBuffer.append(Character.toLowerCase(ch));
        if (multiEditBuffer.length() >= 2) {
            commitMultiEdit();
        }
    }

    private void commitMultiEditIfNeeded() {
        if (multiEditBuffer.length() > 0) {
            commitMultiEdit();
        }
    }

    private void commitMultiEdit() {
        if (multiEditBuffer.length() == 0) {
            return;
        }
        String normalized = HexTableModel.normalizeHexByte(multiEditBuffer.toString());
        multiEditBuffer.setLength(0);
        int value;
        try {
            value = Integer.parseInt(normalized, 16);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (selections.isEmpty()) {
            return;
        }
        rememberUndo();
        List<Selection> snapshot = new ArrayList<>(selections);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), value);
        }
        table.repaint();
    }

    private void startEditingSelectedCell() {
        if (multiEditBuffer.length() > 0) {
            commitMultiEdit();
            return;
        }
        if (countSelectedCells() > 1) {
            return;
        }
        startEditingSelectedCell(null);
    }

    private void startEditingSelectedCell(@Nullable String initialText) {
        int offset = selectedOffset();
        if (offset < 0) {
            return;
        }
        int row = model.rowForOffset(offset);
        int column = model.columnForOffset(offset);
        table.editCellAt(row, column);
        JComponent editor = (JComponent) table.getEditorComponent();
        if (editor != null) {
            if (initialText != null && editor instanceof JTextField textField) {
                textField.setText(initialText);
                textField.setCaretPosition(textField.getText().length());
            }
            editor.requestFocusInWindow();
        }
    }

    private void handleTabKey() {
        if (multiEditBuffer.length() > 0) {
            commitMultiEdit();
            if (model.getDataLength() == 0) {
                return;
            }
            int target = clampOffset(lastSelectedOffset() + 1);
            setSelectionRange(target, target);
            startEditingSelectedCell();
        } else {
            moveSelection(1);
        }
    }

    private void zeroSelectedBytes() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        clearSearchResults();
        rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), 0);
        }
        table.repaint();
    }

    private void deleteSelectedBytes() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        clearSearchResults();
        rememberUndo();
        List<Selection> copy = new ArrayList<>(ranges);
        copy.sort((a, b) -> Integer.compare(b.start(), a.start()));
        List<Selection> snapshot = new ArrayList<>(copy);
        for (Selection selection : snapshot) {
            model.deleteRange(selection.start(), selection.length());
        }
        clearByteSelection();
    }

    private void cutSelectionToClipboard() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        copySelectionToClipboard();
        deleteSelectedBytes();
    }

    private void copySelectionToClipboard() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        ranges.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<Selection> groups = mergeContiguousSameRowRanges(ranges);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            Selection selection = groups.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            for (int j = 0; j < selection.length(); j++) {
                if (j > 0) {
                    builder.append(' ');
                }
                builder.append(String.format("%02X", model.unsignedAt(selection.start() + j)));
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(builder.toString()), null);
    }

    private void pasteHexIntoSelection() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        ranges.sort((a, b) -> Integer.compare(a.start(), b.start()));
        String text;
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = String.valueOf(data);
        } catch (Exception ignored) {
            return;
        }
        if (ranges.size() == 1) {
            Selection selection = ranges.get(0);
            byte[] bytes = HexTableModel.parseHexBytes(text);
            if (bytes.length == 0) {
                return;
            }
            rememberUndo();
            model.setBytesAt(selection.start(), bytes, false, 0);
        } else {
            List<Selection> groups = mergeContiguousSameRowRanges(ranges);
            String[] parts = text.split("\n");
            List<byte[]> groupBytes = new ArrayList<>();
            for (String part : parts) {
                groupBytes.add(HexTableModel.parseHexBytes(part));
            }
            rememberUndo();
            for (int i = 0; i < groups.size() && i < groupBytes.size(); i++) {
                byte[] group = groupBytes.get(i);
                if (group.length == 0) {
                    continue;
                }
                Selection selection = groups.get(i);
                model.setBytesAt(selection.start(), group, true, selection.length());
            }
        }
        table.repaint();
    }

    private void pasteHexBeforeSelection() {
        int insertIndex = selections.isEmpty() ? 0 : firstSelectedOffset();
        pasteHexAt(insertIndex);
    }

    private void pasteHexAfterSelection() {
        int insertIndex = selections.isEmpty() ? model.getDataLength() : lastSelectedOffset() + 1;
        pasteHexAt(insertIndex);
    }

    private void pasteHexAt(int insertIndex) {
        String text;
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = String.valueOf(data);
        } catch (Exception ignored) {
            return;
        }
        byte[] bytes = HexTableModel.parseHexBytes(text);
        if (bytes.length == 0) {
            return;
        }
        clearSearchResults();
        rememberUndo();
        model.insertBytes(insertIndex, bytes);
        setSelectionRange(insertIndex, insertIndex + bytes.length - 1);
    }

    private List<Selection> mergeContiguousSameRowRanges(List<Selection> sortedRanges) {
        int bytesPerRow = model.getBytesPerRow();
        List<Selection> merged = new ArrayList<>();
        for (Selection range : sortedRanges) {
            if (!merged.isEmpty()) {
                Selection previous = merged.get(merged.size() - 1);
                int previousEnd = previous.start() + previous.length();
                int rangeEnd = range.start() + range.length();
                if (previousEnd >= range.start() && range.start() % bytesPerRow != 0) {
                    int newEnd = Math.max(previousEnd, rangeEnd);
                    merged.set(merged.size() - 1, new Selection(previous.start(), newEnd - previous.start()));
                    continue;
                }
            }
            merged.add(range);
        }
        return merged;
    }

    private void askAndInsertZeros(boolean before) {
        String title = before ? HexEditorBundle.message("dialog.insertBefore.title") : HexEditorBundle.message("dialog.insertAfter.title");
        String value = Messages.showInputDialog(project, HexEditorBundle.message("dialog.insertZeros.message"), title, null, "1", null);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int count = Integer.parseInt(value.trim());
            insertZerosRelative(before, count);
        } catch (NumberFormatException exception) {
            Messages.showErrorDialog(project, HexEditorBundle.message("dialog.invalidCount.message"), HexEditorBundle.message("dialog.invalidCount.title"));
        }
    }

    private void insertZerosRelative(boolean before, int count) {
        if (count <= 0) {
            return;
        }
        Selection selection = selectedByteRange();
        int index = selection.isEmpty() ? model.getDataLength() : before ? selection.start() : selection.start() + selection.length();
        rememberUndo();
        model.insertZeros(index, count);
        selectOffset(Math.min(index, Math.max(0, model.getDataLength() - 1)));
    }

    private void selectAllBytes() {
        clearSearchResults();
        if (model.getDataLength() == 0) {
            return;
        }
        setSelectionRange(0, model.getDataLength() - 1);
    }

    private void invertSelection() {
        commitMultiEditIfNeeded();
        clearSearchResults();
        if (model.getDataLength() == 0) {
            return;
        }
        normalizeSelectionsInPlace();
        int last = model.getDataLength() - 1;
        List<Selection> inverted = new ArrayList<>();
        int cursor = 0;
        for (Selection selection : selections) {
            int start = selection.start();
            if (cursor < start) {
                inverted.add(new Selection(cursor, start - cursor));
            }
            cursor = start + selection.length();
        }
        if (cursor <= last) {
            inverted.add(new Selection(cursor, last - cursor + 1));
        }
        selections.clear();
        selections.addAll(inverted);
        if (selections.isEmpty()) {
            activeIndex = -1;
            anchor = -1;
            caret = -1;
        } else {
            activeIndex = 0;
            anchor = selections.get(0).start();
            caret = selections.get(0).start();
            scrollToOffset(caret);
        }
        updateStatus(caret);
        table.repaint();
    }

    private void rememberUndo() {
        undoStack.push(model.copyData());
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(model.copyData());
        model.replaceData(undoStack.pop());
        setModified(true);
        if (searchActive) {
            refreshActiveSearch();
        } else {
            clampSelectionsToData();
        }
        table.repaint();
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(model.copyData());
        model.replaceData(redoStack.pop());
        setModified(true);
        if (searchActive) {
            refreshActiveSearch();
        } else {
            clampSelectionsToData();
        }
        table.repaint();
    }

    private void clampSelectionsToData() {
        int last = model.getDataLength() - 1;
        if (last < 0) {
            clearByteSelection();
            return;
        }
        List<Selection> clamped = new ArrayList<>();
        for (Selection selection : selections) {
            int start = Math.max(0, Math.min(selection.start(), last));
            int end = Math.min(selection.start() + selection.length() - 1, last);
            if (end >= start) {
                clamped.add(new Selection(start, end - start + 1));
            }
        }
        selections.clear();
        selections.addAll(clamped);
        if (activeIndex >= selections.size()) {
            activeIndex = selections.size() - 1;
        }
        if (caret >= 0 && caret > last) {
            caret = last;
        }
        if (anchor >= 0 && anchor > last) {
            anchor = last;
        }
    }

    private int firstSelectedOffset() {
        int min = Integer.MAX_VALUE;
        for (Selection selection : selections) {
            if (selection.start() < min) {
                min = selection.start();
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private int lastSelectedOffset() {
        int max = -1;
        for (Selection selection : selections) {
            int end = selection.start() + selection.length() - 1;
            if (end > max) {
                max = end;
            }
        }
        return max;
    }

    private Selection selectedByteRange() {
        return activeSelection();
    }

    private Selection activeSelection() {
        if (activeIndex >= 0 && activeIndex < selections.size()) {
            return selections.get(activeIndex);
        }
        return Selection.empty();
    }

    private List<Selection> selectedByteRanges() {
        return new ArrayList<>(selections);
    }

    private final class HexTable extends JTable {
        private HexTable(HexTableModel model) {
            super(model);
        }

        @Override
        public void repaint() {
            super.repaint();
            if (getTableHeader() != null) {
                getTableHeader().repaint();
            }
        }

        @Override
        public void repaint(long tm, int x, int y, int width, int height) {
            super.repaint(tm, x, y, width, height);
            if (getTableHeader() != null) {
                getTableHeader().repaint();
            }
        }

        @Override
        public boolean editCellAt(int row, int column, EventObject event) {
            if (searchActive) {
                return false;
            }
            if (model.byteIndexAt(row, column) < 0) {
                return false;
            }
            if (event instanceof MouseEvent mouseEvent && mouseEvent.getClickCount() < 2) {
                return false;
            }
            if (event instanceof KeyEvent) {
                return false;
            }
            return super.editCellAt(row, column, event);
        }
    }

    private final class HexCellEditor extends DefaultCellEditor {
        private final JTextField field;
        private byte[] beforeEdit;

        private HexCellEditor() {
            super(new JTextField());
            field = (JTextField) getComponent();
            field.setHorizontalAlignment(SwingConstants.CENTER);
            field.setFont(monospacedFont());
            field.setBorder(BorderFactory.createEmptyBorder());
            PlainDocument document = (PlainDocument) field.getDocument();
            document.setDocumentFilter(new DocumentFilter() {
                @Override
                public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                    replace(fb, offset, 0, string, attr);
                }

                @Override
                public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                    if (text == null) {
                        text = "";
                    }
                    String current = fb.getDocument().getText(0, fb.getDocument().getLength());
                    String candidate = current.substring(0, offset) + text + current.substring(offset + length);
                    StringBuilder filtered = new StringBuilder(2);
                    for (int i = 0; i < candidate.length() && filtered.length() < 2; i++) {
                        char ch = candidate.charAt(i);
                        if (Character.digit(ch, 16) >= 0) {
                            filtered.append(Character.toLowerCase(ch));
                        }
                    }
                    fb.replace(0, fb.getDocument().getLength(), filtered.toString(), attrs);
                }
            });
            field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "commitMoveEditNext");
            field.getActionMap().put("commitMoveEditNext", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (stopCellEditing()) {
                        moveSelection(1);
                        startEditingSelectedCell();
                    }
                }
            });
            field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitStay");
            field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "commitStay");
            field.getActionMap().put("commitStay", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    stopCellEditing();
                }
            });
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            java.awt.Component component = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            beforeEdit = model.copyData();
            field.setText(String.valueOf(value));
            field.selectAll();
            return component;
        }

        @Override
        public Object getCellEditorValue() {
            return HexTableModel.normalizeHexByte(field.getText());
        }

        @Override
        public boolean stopCellEditing() {
            byte[] before = beforeEdit;
            boolean stopped = super.stopCellEditing();
            if (stopped && before != null && !Arrays.equals(before, model.copyData())) {
                undoStack.push(before);
                redoStack.clear();
            }
            beforeEdit = null;
            return stopped;
        }
    }

    private final class OffsetRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(table, value, false, false, row, column);
            int rowStart = row * model.getBytesPerRow();
            int rowEnd = model.getDataLength() <= 0 ? rowStart - 1 : Math.min(rowStart + model.getBytesPerRow() - 1, model.getDataLength() - 1);
            if (rowEnd >= rowStart && isAnyOffsetInSelection(rowStart, rowEnd)) {
                component.setBackground(selectionBackground());
                component.setForeground(selectionForeground());
            } else {
                component.setBackground(editorBackground());
                component.setForeground(JBColor.GRAY);
            }
            return component;
        }
    }

    private boolean isAnyOffsetInSelection(int from, int to) {
        for (Selection selection : selections) {
            int selEnd = selection.start() + selection.length() - 1;
            if (selection.start() <= to && selEnd >= from) {
                return true;
            }
        }
        return false;
    }

    private boolean isColumnInSelection(int column) {
        if (selections.isEmpty() || model.getDataLength() == 0) {
            return false;
        }
        int total = table.getColumnCount();
        if (column <= 0 || column >= total - 1) {
            return false;
        }
        int bytePosition = column - 1;
        int bytesPerRow = model.getBytesPerRow();
        for (Selection selection : selections) {
            int start = selection.start();
            int end = start + selection.length() - 1;
            int mod = start % bytesPerRow;
            int adjust = (bytePosition - mod + bytesPerRow) % bytesPerRow;
            int candidate = start + adjust;
            if (candidate <= end && candidate < model.getDataLength()) {
                return true;
            }
        }
        return false;
    }

    private final class HexHeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setOpaque(true);
            if (isColumnInSelection(column)) {
                setBackground(selectionBackground());
                setForeground(selectionForeground());
            } else {
                setBackground(panelBackground());
                setForeground(editorForeground());
            }
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(monospacedFont());
            setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
            return this;
        }
    }

    private final class HexByteRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            int offset = model.byteIndexAt(row, column);
            Object displayValue = value;
            if (offset >= 0 && multiEditBuffer.length() == 1 && isOffsetInSelection(offset)) {
                displayValue = multiEditBuffer.toString();
            }
            java.awt.Component component = super.getTableCellRendererComponent(table, displayValue, false, false, row, column);
            applyByteColors(component, offset);
            return component;
        }
    }

    private final class RawPreviewRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(table, value, false, false, row, column);
            component.setBackground(editorBackground());
            component.setForeground(editorForeground());
            String raw = model.rawText(row);
            if (raw.isEmpty()) {
                return component;
            }
            StringBuilder html = new StringBuilder("<html>");
            for (int i = 0; i < raw.length(); i++) {
                int offset = row * model.getBytesPerRow() + i;
                String text = escape(raw.substring(i, i + 1));
                if (isOffsetInSelection(offset)) {
                    html.append("<span style=\"background-color:")
                            .append(htmlColor(selectionBackground()))
                            .append(";color:")
                            .append(htmlColor(selectionForeground()))
                            .append(";\">")
                            .append(text)
                            .append("</span>");
                } else if (isOffsetInSearchMatch(offset)) {
                    html.append("<span style=\"background-color:")
                            .append(htmlColor(searchBackground()))
                            .append(";\">")
                            .append(text)
                            .append("</span>");
                } else {
                    html.append(text);
                }
            }
            html.append("</html>");
            setText(html.toString());
            return component;
        }
    }

    private void applyByteColors(java.awt.Component component, int offset) {
        if (offset < 0) {
            component.setBackground(editorBackground());
            component.setForeground(editorForeground());
        } else if (isOffsetInSelection(offset)) {
            component.setBackground(selectionBackground());
            component.setForeground(selectionForeground());
        } else if (isOffsetInSearchMatch(offset)) {
            component.setBackground(searchBackground());
            component.setForeground(editorForeground());
        } else {
            component.setBackground(editorBackground());
            component.setForeground(editorForeground());
        }
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(" ", "&nbsp;");
    }

    private static String htmlColor(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private record Selection(int start, int length) {
        static Selection empty() {
            return new Selection(-1, 0);
        }

        boolean isEmpty() {
            return start < 0 || length <= 0;
        }
    }

    private void save() {
        byte[] bytes = model.copyData();
        try {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    file.setBinaryContent(bytes);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            setModified(false);
            updateStatus(selectedOffset());
        } catch (RuntimeException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.save.failed.title"));
        }
    }

    private void saveAs() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(HexEditorBundle.message("dialog.saveAs.title"), HexEditorBundle.message("dialog.saveAs.description"));
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(file.getParent(), file.getName());
        if (wrapper == null) {
            return;
        }
        byte[] bytes = model.copyData();
        try {
            writeBytesToFile(wrapper.getFile(), bytes);
        } catch (IOException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.saveAs.failed.title"));
        }
    }

    private void exportFragment() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            Messages.showInfoMessage(project, HexEditorBundle.message("dialog.fragmentExport.noSelection"), HexEditorBundle.message("dialog.fragmentExport.title"));
            return;
        }
        ranges.sort((a, b) -> Integer.compare(a.start(), b.start()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Selection range : ranges) {
            for (int i = 0; i < range.length(); i++) {
                out.write(model.unsignedAt(range.start() + i));
            }
        }
        byte[] bytes = out.toByteArray();
        FileSaverDescriptor descriptor = new FileSaverDescriptor(HexEditorBundle.message("dialog.fragmentExport.title"), HexEditorBundle.message("dialog.fragmentExport.description"));
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(file.getParent(), file.getName() + ".frag");
        if (wrapper == null) {
            return;
        }
        try {
            writeBytesToFile(wrapper.getFile(), bytes);
        } catch (IOException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.fragmentExport.failed.title"));
        }
    }

    private void importFragmentAtStart() {
        importFragmentAt(0);
    }

    private void importFragmentAtEnd() {
        importFragmentAt(model.getDataLength());
    }

    private void importFragmentAfterSelection() {
        int insertIndex;
        if (selections.isEmpty()) {
            insertIndex = model.getDataLength();
        } else {
            Selection last = selections.get(selections.size() - 1);
            insertIndex = last.start() + last.length();
        }
        importFragmentAt(insertIndex);
    }

    private void importFragmentAt(int insertIndex) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
        descriptor.setTitle(HexEditorBundle.message("dialog.fragmentImport.title"));
        VirtualFile selected = FileChooser.chooseFile(descriptor, project, file.getParent());
        if (selected == null) {
            return;
        }
        byte[] bytes;
        try {
            bytes = selected.contentsToByteArray();
        } catch (IOException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.fragmentImport.failed.title"));
            return;
        }
        if (bytes.length == 0) {
            return;
        }
        rememberUndo();
        model.insertBytes(insertIndex, bytes);
        setSelectionRange(insertIndex, insertIndex + bytes.length - 1);
    }

    private static void writeBytesToFile(File target, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
    }

    private void reload() {
        if (modified) {
            int result = Messages.showOkCancelDialog(project,
                    HexEditorBundle.message("dialog.reload.confirm.message"),
                    HexEditorBundle.message("dialog.reload.confirm.title"),
                    HexEditorBundle.message("dialog.reload.confirm.ok"),
                    HexEditorBundle.message("dialog.reload.confirm.cancel"),
                    null);
            if (result != Messages.OK) {
                return;
            }
        }
        model.replaceData(readBytes(file));
        undoStack.clear();
        redoStack.clear();
        setModified(false);
        updateStatus(-1);
    }

    private void goToOffset() {
        String value = Messages.showInputDialog(project, HexEditorBundle.message("dialog.goToOffset.message"), HexEditorBundle.message("dialog.goToOffset.title"), null);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int offset = parseOffset(value);
            selectOffset(offset);
        } catch (IllegalArgumentException exception) {
            Messages.showErrorDialog(project, exception.getMessage(), HexEditorBundle.message("dialog.invalidOffset.title"));
        }
    }

    private void showFindPanel(boolean replaceMode) {
        if (findPanel == null) {
            return;
        }
        findPanel.setVisible(true);
        setReplaceVisible(replaceMode);
        searchActive = true;
        updateSearchMatches();
    }

    private void setReplaceVisible(boolean visible) {
        if (replaceRow != null) {
            replaceRow.setVisible(visible);
        }
        if (toggleReplaceButton != null) {
            toggleReplaceButton.setIcon(visible ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
            toggleReplaceButton.setToolTipText(visible
                    ? HexEditorBundle.message("button.toggleReplace.hide.tooltip")
                    : HexEditorBundle.message("button.toggleReplace.show.tooltip"));
        }
        if (findPanel != null) {
            findPanel.revalidate();
            findPanel.repaint();
        }
        SearchTextField target = visible ? replaceField : findField;
        if (target != null) {
            target.getTextEditor().requestFocusInWindow();
            target.selectText();
        }
    }

    private void hideFindPanel() {
        if (findPanel == null) {
            return;
        }
        findPanel.setVisible(false);
        if (replaceRow != null) {
            replaceRow.setVisible(false);
        }
        if (toggleReplaceButton != null) {
            toggleReplaceButton.setIcon(AllIcons.General.ArrowRight);
            toggleReplaceButton.setToolTipText(HexEditorBundle.message("button.toggleReplace.show.tooltip"));
        }
        searchActive = false;
        searchMatches.clear();
        activeMatchIndex = -1;
        matchLabel.setText("");
        table.requestFocusInWindow();
        table.repaint();
    }

    private void restoreSearchSelection() {
        if (!searchActive && findField != null && !findField.getText().isBlank()) {
            searchActive = true;
            updateSearchMatches();
        }
    }

    private void refreshActiveSearch() {
        if (searchActive && findPanel != null && findPanel.isVisible()) {
            updateSearchMatches();
        }
    }

    private void syncStringToHex() {
        String text = stringFindField.getText();
        String hex = convertStringToHex(text);
        if (!findField.getText().equals(hex)) {
            syncingFields = true;
            findField.setText(hex);
            syncingFields = false;
        }
    }

    private void onHexChanged() {
        searchActive = true;
        if (!syncingFields && stringFindField != null && !stringFindField.getText().isEmpty()) {
            // Hex edited directly: clear the string field so the two inputs never disagree (one-way binding).
            syncingFields = true;
            stringFindField.setText("");
            syncingFields = false;
        }
        updateSearchMatches();
    }

    private static String convertStringToHex(String text) {
        if (text.isEmpty()) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder hex = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return hex.toString();
    }

    private void updateSearchMatches() {
        searchMatches.clear();
        activeMatchIndex = -1;
        byte[] pattern = HexTableModel.parseHexBytes(findField.getText());
        if (pattern.length == 0) {
            selections.clear();
            activeIndex = -1;
            anchor = -1;
            caret = -1;
            matchLabel.setText("");
            table.repaint();
            return;
        }
        int from = 0;
        while (from <= model.getDataLength() - pattern.length) {
            int found = model.find(pattern, from);
            if (found < 0) {
                break;
            }
            searchMatches.add(new Selection(found, pattern.length));
            from = found + Math.max(1, pattern.length);
        }
        selections.clear();
        if (searchMatches.isEmpty()) {
            activeIndex = -1;
            anchor = -1;
            caret = -1;
        } else {
            int selected = selectedOffset();
            activeMatchIndex = 0;
            for (int i = 0; i < searchMatches.size(); i++) {
                Selection match = searchMatches.get(i);
                if (selected <= match.start()) {
                    activeMatchIndex = i;
                    break;
                }
            }
            Selection active = searchMatches.get(activeMatchIndex);
            selections.add(new Selection(active.start(), active.length()));
            activeIndex = 0;
            anchor = active.start();
            caret = active.start();
            scrollToOffset(active.start());
        }
        updateMatchLabel();
        table.repaint();
    }

    private void activateSearchMatch(int index) {
        if (searchMatches.isEmpty()) {
            updateMatchLabel();
            return;
        }
        activeMatchIndex = Math.floorMod(index, searchMatches.size());
        Selection match = searchMatches.get(activeMatchIndex);
        selections.clear();
        selections.add(new Selection(match.start(), match.length()));
        activeIndex = 0;
        anchor = match.start();
        caret = match.start();
        scrollToOffset(match.start());
        updateMatchLabel();
        table.repaint();
    }

    private void updateMatchLabel() {
        if (matchLabel == null) {
            return;
        }
        if (searchMatches.isEmpty()) {
            matchLabel.setText(findField == null || findField.getText().isBlank() ? "" : HexEditorBundle.message("status.noResults"));
        } else {
            matchLabel.setText((activeMatchIndex + 1) + "/" + searchMatches.size());
        }
    }

    private void replaceCurrentMatch() {
        if (activeMatchIndex < 0 || activeMatchIndex >= searchMatches.size()) {
            return;
        }
        byte[] replacement = HexTableModel.parseHexBytes(replaceField.getText());
        if (replacement.length == 0) {
            return;
        }
        Selection match = searchMatches.get(activeMatchIndex);
        rememberUndo();
        model.setBytesAt(match.start(), replacement, true, match.length());
        updateSearchMatches();
    }

    private void replaceAllMatches() {
        byte[] replacement = HexTableModel.parseHexBytes(replaceField.getText());
        if (searchMatches.isEmpty() || replacement.length == 0) {
            return;
        }
        rememberUndo();
        List<Selection> snapshot = new ArrayList<>(searchMatches);
        for (Selection match : snapshot) {
            model.setBytesAt(match.start(), replacement, true, match.length());
        }
        updateSearchMatches();
        table.repaint();
    }

    private void deleteAllSelections() {
        boolean wasSearchActive = searchActive;
        List<Selection> ranges = wasSearchActive && !searchMatches.isEmpty()
                ? new ArrayList<>(searchMatches)
                : selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        snapshot.sort((a, b) -> Integer.compare(b.start(), a.start()));
        for (Selection selection : snapshot) {
            model.deleteRange(selection.start(), selection.length());
        }
        if (wasSearchActive) {
            refreshActiveSearch();
        } else {
            clearByteSelection();
        }
        table.repaint();
    }

    private void zeroAllSelections() {
        boolean wasSearchActive = searchActive;
        List<Selection> ranges = wasSearchActive && !searchMatches.isEmpty()
                ? new ArrayList<>(searchMatches)
                : selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), 0);
        }
        if (wasSearchActive) {
            refreshActiveSearch();
        }
        table.repaint();
    }

    private void deleteCurrentSelection() {
        Selection selection = activeSelection();
        if (selection.isEmpty()) {
            return;
        }
        boolean wasSearchActive = searchActive;
        rememberUndo();
        model.deleteRange(selection.start(), selection.length());
        if (wasSearchActive) {
            refreshActiveSearch();
        } else {
            clearByteSelection();
        }
        table.repaint();
    }

    private void zeroCurrentSelection() {
        Selection selection = activeSelection();
        if (selection.isEmpty()) {
            return;
        }
        boolean wasSearchActive = searchActive;
        rememberUndo();
        model.fillRange(selection.start(), selection.length(), 0);
        if (wasSearchActive) {
            refreshActiveSearch();
        }
        table.repaint();
    }

    private int parseOffset(String value) {
        String text = value.trim().replace("_", "");
        int radix = text.startsWith("0x") || text.startsWith("0X") ? 16 : 10;
        if (radix == 16) {
            text = text.substring(2);
        }
        int offset = Integer.parseInt(text, radix);
        if (offset < 0 || offset >= model.getDataLength()) {
            throw new IllegalArgumentException(HexEditorBundle.message("dialog.invalidOffset.message"));
        }
        return offset;
    }

    private void selectOffset(int offset) {
        clearSearchResults();
        setSelectionRange(offset, offset);
    }

    private void setSelectionRange(int start, int end) {
        if (model.getDataLength() == 0) {
            clearByteSelection();
            return;
        }
        int last = model.getDataLength() - 1;
        int s = Math.max(0, Math.min(start, last));
        int e = Math.max(0, Math.min(end, last));
        int lo = Math.min(s, e);
        int hi = Math.max(s, e);
        selections.clear();
        selections.add(new Selection(lo, hi - lo + 1));
        activeIndex = 0;
        anchor = s;
        caret = e;
        scrollToOffset(e);
        updateStatus(e);
    }

    private void extendActiveTo(int offset) {
        clearSearchResults();
        if (model.getDataLength() == 0) {
            clearByteSelection();
            return;
        }
        int last = model.getDataLength() - 1;
        int o = Math.max(0, Math.min(offset, last));
        int a = anchor < 0 ? o : anchor;
        int lo = Math.min(a, o);
        int hi = Math.max(a, o);
        if (activeIndex >= 0 && activeIndex < selections.size()) {
            selections.set(activeIndex, new Selection(lo, hi - lo + 1));
        } else {
            selections.clear();
            selections.add(new Selection(lo, hi - lo + 1));
            activeIndex = 0;
        }
        normalizeSelectionsInPlace();
        activeIndex = findSelectionContaining(o);
        if (activeIndex < 0) {
            activeIndex = selections.size() - 1;
        }
        anchor = a;
        caret = o;
        scrollToOffset(o);
        updateStatus(o);
    }

    private void addSelection(int offset) {
        clearSearchResults();
        if (model.getDataLength() == 0) {
            return;
        }
        int o = clampOffset(offset);
        selections.add(new Selection(o, 1));
        normalizeSelectionsInPlace();
        activeIndex = findSelectionContaining(o);
        if (activeIndex < 0) {
            activeIndex = selections.size() - 1;
        }
        anchor = o;
        caret = o;
        scrollToOffset(o);
        updateStatus(o);
        table.repaint();
    }

    private void removeOffsetFromSelection(int offset) {
        clearSearchResults();
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.get(i);
            int start = selection.start();
            int end = start + selection.length() - 1;
            if (offset >= start && offset <= end) {
                List<Selection> replacements = new ArrayList<>();
                if (offset > start) {
                    replacements.add(new Selection(start, offset - start));
                }
                if (offset < end) {
                    replacements.add(new Selection(offset + 1, end - offset));
                }
                selections.remove(i);
                selections.addAll(i, replacements);
                normalizeSelectionsInPlace();
                if (selections.isEmpty()) {
                    activeIndex = -1;
                    anchor = -1;
                    caret = -1;
                } else {
                    activeIndex = findSelectionContaining(offset);
                    if (activeIndex < 0) {
                        for (int j = selections.size() - 1; j >= 0; j--) {
                            if (selections.get(j).start() <= offset) {
                                activeIndex = j;
                                break;
                            }
                        }
                        if (activeIndex < 0) {
                            activeIndex = 0;
                        }
                    }
                    anchor = offset;
                    caret = offset;
                }
                table.repaint();
                return;
            }
        }
    }

    private void scrollToOffset(int offset) {
        int row = model.rowForOffset(offset);
        int column = model.columnForOffset(offset);
        table.scrollRectToVisible(table.getCellRect(row, column, true));
    }

    private void clearByteSelection() {
        selections.clear();
        activeIndex = -1;
        anchor = -1;
        caret = -1;
        multiEditBuffer.setLength(0);
        table.repaint();
    }

    private void normalizeSelectionsInPlace() {
        selections.removeIf(s -> s.isEmpty());
        if (selections.isEmpty()) {
            return;
        }
        selections.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<Selection> merged = new ArrayList<>();
        for (Selection range : selections) {
            if (!merged.isEmpty()) {
                Selection previous = merged.get(merged.size() - 1);
                int previousEnd = previous.start() + previous.length();
                int rangeEnd = range.start() + range.length();
                if (previousEnd > range.start()) {
                    int newEnd = Math.max(previousEnd, rangeEnd);
                    merged.set(merged.size() - 1, new Selection(previous.start(), newEnd - previous.start()));
                    continue;
                }
            }
            merged.add(range);
        }
        selections.clear();
        selections.addAll(merged);
    }

    private int findSelectionContaining(int offset) {
        for (int i = 0; i < selections.size(); i++) {
            Selection s = selections.get(i);
            if (offset >= s.start() && offset < s.start() + s.length()) {
                return i;
            }
        }
        return -1;
    }

    private int selectedOffset() {
        return caret;
    }

    private boolean isOffsetInSelection(int offset) {
        if (offset < 0) {
            return false;
        }
        for (Selection selection : selections) {
            if (offset >= selection.start() && offset < selection.start() + selection.length()) {
                return true;
            }
        }
        return false;
    }

    private boolean isOffsetInSearchMatch(int offset) {
        for (Selection match : searchMatches) {
            if (offset >= match.start() && offset < match.start() + match.length()) {
                return true;
            }
        }
        return false;
    }

    private int findMatchIndexContaining(int offset) {
        for (int i = 0; i < searchMatches.size(); i++) {
            Selection match = searchMatches.get(i);
            if (offset >= match.start() && offset < match.start() + match.length()) {
                return i;
            }
        }
        return -1;
    }

    private void clearSearchResults() {
        if (searchActive || !searchMatches.isEmpty()) {
            searchActive = false;
            searchMatches.clear();
            activeMatchIndex = -1;
            updateMatchLabel();
            table.repaint();
        }
    }

    private boolean isDirectEditingBlocked() {
        return searchActive;
    }

    private abstract static class ToolbarAction extends AnAction {
        ToolbarAction(String text, String description, javax.swing.Icon icon) {
            super(text, description, icon);
        }
    }

    private final class BytesPerRowAction extends AnAction implements CustomComponentAction {
        private BytesPerRowAction() {
            super(HexEditorBundle.message("action.bytesPerRow.text"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
        }

        @Override
        public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            panel.setOpaque(false);
            panel.add(new JBLabel(HexEditorBundle.message("label.bytesPerRow")));
            JSpinner bytesPerRow = new JSpinner(new SpinnerNumberModel(DEFAULT_BYTES_PER_ROW, 4, 32, 4));
            bytesPerRow.addChangeListener(event -> {
                model.setBytesPerRow((Integer) bytesPerRow.getValue());
                applyColumnWidths();
                updateStatus(selectedOffset());
            });
            panel.add(bytesPerRow);
            return panel;
        }
    }

    private final class MatchCountAction extends AnAction implements CustomComponentAction {
        private MatchCountAction() {
            super(HexEditorBundle.message("action.matches.text"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
        }

        @Override
        public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            matchLabel.setHorizontalAlignment(SwingConstants.CENTER);
            matchLabel.setBorder(JBUI.Borders.empty(0, 6));
            return matchLabel;
        }
    }

    private void updateStatus(int offset) {
        table.repaint();
    }

    private static Font monospacedFont() {
        Font base = UIManager.getFont("EditorPane.font");
        if (base == null) {
            base = UIManager.getFont("TextArea.font");
        }
        int size = base == null ? JBUI.scale(13) : base.getSize();
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    private static Color editorBackground() {
        Color color = UIManager.getColor("EditorPane.background");
        return color == null ? JBColor.PanelBackground : color;
    }

    private static Color editorForeground() {
        Color color = UIManager.getColor("EditorPane.foreground");
        return color == null ? JBColor.foreground() : color;
    }

    private static Color panelBackground() {
        Color color = UIManager.getColor("Panel.background");
        return color == null ? JBColor.PanelBackground : color;
    }

    private static Color selectionBackground() {
        Color color = UIManager.getColor("Table.selectionBackground");
        return color == null ? JBColor.namedColor("Table.selectionBackground", new JBColor(0xD6E8FF, 0x2F4B67)) : color;
    }

    private static Color selectionForeground() {
        Color color = UIManager.getColor("Table.selectionForeground");
        return color == null ? JBColor.foreground() : color;
    }

    private static Color searchBackground() {
        Color color = UIManager.getColor("SearchResult.selectionBackground");
        return color == null ? JBColor.namedColor("SearchResult.selectionBackground", new JBColor(0xFFF2A8, 0x675F20)) : color;
    }

    private static Color borderColor() {
        return JBColor.border();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void setModified(boolean modified) {
        boolean old = this.modified;
        this.modified = modified;
        if (old != modified) {
            changeSupport.firePropertyChange(MODIFIED_PROPERTY, old, modified);
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return table;
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public @NotNull String getName() {
        return HexEditorBundle.message("editor.name");
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        changeSupport.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        changeSupport.removePropertyChangeListener(listener);
    }

    @Override
    public @Nullable BackgroundEditorHighlighter getBackgroundHighlighter() {
        return null;
    }

    @Override
    public @Nullable FileEditorLocation getCurrentLocation() {
        return null;
    }

    @Override
    public @Nullable com.intellij.ide.structureView.StructureViewBuilder getStructureViewBuilder() {
        return null;
    }

    @Override
    public void dispose() {
    }
}
