package cn.fj.loli.hexsupport;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.undo.BasicUndoableAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
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
import javax.swing.Timer;
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
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EventObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class HexFileEditor extends UserDataHolderBase implements FileEditor {
    private static final int DEFAULT_BYTES_PER_ROW = 16;
    private static final String MODIFIED_PROPERTY = "modified";
    static final String HISTORY_PROPERTY = "history";
    private static final int LARGE_SEARCH_DEBOUNCE_MS = 2000;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Project project;
    private final VirtualFile file;
    private final PropertyChangeSupport changeSupport = new PropertyChangeSupport(this);
    private final JPanel component = new JPanel(new BorderLayout());
    private final HexTableModel model;
    private final JTable table;
    private final Deque<HexDocument.State> undoStack = new ArrayDeque<>();
    private final Deque<HexDocument.State> redoStack = new ArrayDeque<>();
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
    private long anchor = -1;
    private long caret = -1;
    private final StringBuilder multiEditBuffer = new StringBuilder();
    private boolean modified;
    private boolean searchActive = false;
    private boolean syncingFields = false;
    private boolean searchInProgress = false;
    private boolean searchMatchesCapped = false;
    private final AtomicBoolean saveInProgress = new AtomicBoolean();
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final Timer searchDebounceTimer;
    private final Timer historyAutoSaveTimer;
    private JPanel searchLoadingOverlay;
    private AsyncProcessIcon searchLoadingIcon;

    public HexFileEditor(Project project, VirtualFile file) {
        this.project = project;
        this.file = file;
        this.model = new HexTableModel(new HexDocument(Path.of(file.getPath())), DEFAULT_BYTES_PER_ROW);
        this.table = new HexTable(model);
        this.searchDebounceTimer = new Timer(LARGE_SEARCH_DEBOUNCE_MS, event -> updateSearchMatchesNow());
        this.searchDebounceTimer.setRepeats(false);
        this.historyAutoSaveTimer = new Timer(1000, event -> autoExportOperationHistory());
        this.historyAutoSaveTimer.setRepeats(false);

        component.setBackground(panelBackground());
        installEditorKeyBindings();
        configureTable();
        loadOperationHistoryIfPresent();

        component.add(createTopPanel(), BorderLayout.NORTH);
        component.add(createEditorCenter(), BorderLayout.CENTER);
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(EditorColorsManager.TOPIC, scheme -> refreshEditorStyle());
        updateStatus(selectedOffset());
    }

    private void configureTable() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.setRowSelectionAllowed(false);
        table.setFont(monospacedFont());
        table.setRowHeight(editorRowHeight(table));
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.emptySize());
        table.setFillsViewportHeight(true);
        table.setBackground(editorBackground());
        table.setForeground(editorForeground());
        table.setSelectionBackground(selectionBackground());
        table.setSelectionForeground(selectionForeground());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(editorBackground());
        table.getTableHeader().setForeground(lineNumberForeground());
        table.getTableHeader().setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
        model.setByteChangeListener(() -> {
            setModified(true);
            refreshActiveSearch();
            scheduleHistoryChanged();
        });
        table.setDefaultEditor(Object.class, new HexCellEditor());
        installHexKeyBindings();
        installByteSelectionHandler();
        applyColumnWidths();
    }

    private void installEditorKeyBindings() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke saveKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask);
        bindAction(component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, saveKeyStroke, "hexSave", this::save);
        AnAction saveAllAction = ActionManager.getInstance().getAction("SaveAll");
        ShortcutSet saveShortcutSet = saveAllAction == null ? new CustomShortcutSet(saveKeyStroke) : saveAllAction.getShortcutSet();
        new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                save();
            }
        }.registerCustomShortcutSet(saveShortcutSet, component);
        bindAction(component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "hexUndo", this::requestUndo);
        bindAction(component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | KeyEvent.SHIFT_DOWN_MASK), "hexRedo", this::requestRedo);
    }

    private void installHexKeyBindings() {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "hexTabEdit");
        table.getActionMap().put("hexTabEdit", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                handleTabKey();
            }
        });
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
        bindAction(table, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "hexUndo", this::requestUndo);
        bindAction(table, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "hexUndo", this::requestUndo);
        bindAction(table, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | KeyEvent.SHIFT_DOWN_MASK), "hexRedo", this::requestRedo);
        bindAction(table, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | KeyEvent.SHIFT_DOWN_MASK), "hexRedo", this::requestRedo);
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

    private static void bindAction(JComponent component, int condition, KeyStroke keyStroke, String name, Runnable action) {
        component.getInputMap(condition).put(keyStroke, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
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
        long offset = byteOffsetAtMouse(event);
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
        long offset = byteOffsetAtMouse(event);
        if (offset < 0) {
            return;
        }
        if (anchor >= 0) {
            extendActiveTo(offset);
        }
    }

    private long byteOffsetAtMouse(MouseEvent event) {
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

    private JComponent createEditorCenter() {
        JScrollPane scrollPane = wrap(table);
        searchLoadingIcon = new AsyncProcessIcon.Big("Hex Search");
        searchLoadingOverlay = new JPanel(new GridBagLayout());
        searchLoadingOverlay.setOpaque(false);
        searchLoadingOverlay.add(searchLoadingIcon);
        searchLoadingOverlay.setVisible(false);
        searchLoadingIcon.suspend();

        JLayeredPane layeredPane = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (java.awt.Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return scrollPane.getPreferredSize();
            }
        };
        layeredPane.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(searchLoadingOverlay, JLayeredPane.PALETTE_LAYER);
        return layeredPane;
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
        group.add(new ToolbarAction(HexEditorBundle.message("action.exportHistory.text"), HexEditorBundle.message("action.exportHistory.description"), AllIcons.General.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                exportOperationHistory(true);
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
                requestUndo();
            }
        });
        group.add(new ToolbarAction(HexEditorBundle.message("action.redo.text"), HexEditorBundle.message("action.redo.description"), AllIcons.Actions.Redo) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                requestRedo();
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
        int offsetColumnWidth = Math.max(JBUI.scale(82), table.getFontMetrics(table.getFont()).stringWidth("0000000000000000") + JBUI.scale(18));
        int byteColumnWidth = Math.max(JBUI.scale(28), table.getFontMetrics(table.getFont()).stringWidth("00") + JBUI.scale(16));
        int rawColumnWidth = Math.max(JBUI.scale(80),
                table.getFontMetrics(table.getFont()).charWidth('M') * model.getBytesPerRow() + JBUI.scale(18));
        for (int i = 0; i < columns.getColumnCount(); i++) {
            TableColumn column = columns.getColumn(i);
            if (i == 0) {
                column.setPreferredWidth(offsetColumnWidth);
            } else if (i == columns.getColumnCount() - 1) {
                column.setPreferredWidth(rawColumnWidth);
            } else {
                column.setPreferredWidth(byteColumnWidth);
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

    private void refreshEditorStyle() {
        component.setBackground(panelBackground());
        table.setFont(monospacedFont());
        table.setRowHeight(editorRowHeight(table));
        table.setBackground(editorBackground());
        table.setForeground(editorForeground());
        table.setSelectionBackground(selectionBackground());
        table.setSelectionForeground(selectionForeground());
        table.getTableHeader().setBackground(editorBackground());
        table.getTableHeader().setForeground(lineNumberForeground());
        applyColumnWidths();
        component.revalidate();
        component.repaint();
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
        long firstCell = firstSelectedOffset();
        long lastCell = lastSelectedOffset();
        int bytesPerRow = model.getBytesPerRow();
        long target;
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

    private long clampOffset(long offset) {
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

    private long countSelectedCells() {
        long count = 0;
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
        HexDocument.State before = rememberUndo();
        List<Selection> snapshot = new ArrayList<>(selections);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), value);
        }
        finishUndo(before);
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
        long offset = selectedOffset();
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
            long target = clampOffset(lastSelectedOffset() + 1);
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
        HexDocument.State before = rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), 0);
        }
        finishUndo(before);
        table.repaint();
    }

    private void deleteSelectedBytes() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            return;
        }
        clearSearchResults();
        HexDocument.State before = rememberUndo();
        List<Selection> copy = new ArrayList<>(ranges);
        copy.sort((a, b) -> Long.compare(b.start(), a.start()));
        List<Selection> snapshot = new ArrayList<>(copy);
        for (Selection selection : snapshot) {
            model.deleteRange(selection.start(), selection.length());
        }
        finishUndo(before);
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
        long bytesToCopy = 0;
        for (Selection range : ranges) {
            bytesToCopy += range.length();
        }
        if (bytesToCopy > 16L * 1024 * 1024) {
            Messages.showWarningDialog(project, "Selection is too large to copy to the clipboard.", HexEditorBundle.message("editor.name"));
            return;
        }
        ranges.sort((a, b) -> Long.compare(a.start(), b.start()));
        List<Selection> groups = mergeContiguousSameRowRanges(ranges);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            Selection selection = groups.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            for (long j = 0; j < selection.length(); j++) {
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
        ranges.sort((a, b) -> Long.compare(a.start(), b.start()));
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
            HexDocument.State before = rememberUndo();
            model.setBytesAt(selection.start(), bytes, false, 0);
            finishUndo(before);
        } else {
            List<Selection> groups = mergeContiguousSameRowRanges(ranges);
            String[] parts = text.split("\n");
            List<byte[]> groupBytes = new ArrayList<>();
            for (String part : parts) {
                groupBytes.add(HexTableModel.parseHexBytes(part));
            }
            HexDocument.State before = rememberUndo();
            for (int i = 0; i < groups.size() && i < groupBytes.size(); i++) {
                byte[] group = groupBytes.get(i);
                if (group.length == 0) {
                    continue;
                }
                Selection selection = groups.get(i);
                model.setBytesAt(selection.start(), group, true, selection.length());
            }
            finishUndo(before);
        }
        table.repaint();
    }

    private void pasteHexBeforeSelection() {
        long insertIndex = selections.isEmpty() ? 0 : firstSelectedOffset();
        pasteHexAt(insertIndex);
    }

    private void pasteHexAfterSelection() {
        long insertIndex = selections.isEmpty() ? model.getDataLength() : lastSelectedOffset() + 1;
        pasteHexAt(insertIndex);
    }

    private void pasteHexAt(long insertIndex) {
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
        HexDocument.State before = rememberUndo();
        model.insertBytes(insertIndex, bytes);
        finishUndo(before);
        setSelectionRange(insertIndex, insertIndex + bytes.length - 1);
    }

    private List<Selection> mergeContiguousSameRowRanges(List<Selection> sortedRanges) {
        int bytesPerRow = model.getBytesPerRow();
        List<Selection> merged = new ArrayList<>();
        for (Selection range : sortedRanges) {
            if (!merged.isEmpty()) {
                Selection previous = merged.get(merged.size() - 1);
                long previousEnd = previous.start() + previous.length();
                long rangeEnd = range.start() + range.length();
                if (previousEnd >= range.start() && range.start() % bytesPerRow != 0) {
                    long newEnd = Math.max(previousEnd, rangeEnd);
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
            long count = Long.parseLong(value.trim());
            insertZerosRelative(before, count);
        } catch (NumberFormatException exception) {
            Messages.showErrorDialog(project, HexEditorBundle.message("dialog.invalidCount.message"), HexEditorBundle.message("dialog.invalidCount.title"));
        }
    }

    private void insertZerosRelative(boolean before, long count) {
        if (count <= 0) {
            return;
        }
        Selection selection = selectedByteRange();
        long index = selection.isEmpty() ? model.getDataLength() : before ? selection.start() : selection.start() + selection.length();
        HexDocument.State undoBefore = rememberUndo();
        model.insertZeros(index, count);
        finishUndo(undoBefore);
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
        long last = model.getDataLength() - 1;
        List<Selection> inverted = new ArrayList<>();
        long cursor = 0;
        for (Selection selection : selections) {
            long start = selection.start();
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

    private HexDocument.State rememberUndo() {
        HexDocument.State before = model.snapshot();
        undoStack.push(before);
        redoStack.clear();
        return before;
    }

    private void finishUndo(HexDocument.State before) {
        if (before == null) {
            return;
        }
        if (before.revision() == model.revision()) {
            undoStack.removeFirstOccurrence(before);
            return;
        }
        registerUndoableAction();
    }

    private void requestUndo() {
        UndoManager manager = UndoManager.getInstance(project);
        if (manager.isUndoAvailable(this)) {
            manager.undo(this);
        } else {
            undoFromStack();
        }
    }

    private void requestRedo() {
        UndoManager manager = UndoManager.getInstance(project);
        if (manager.isRedoAvailable(this)) {
            manager.redo(this);
        } else {
            redoFromStack();
        }
    }

    private void undoFromStack() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(model.snapshot());
        model.restore(undoStack.pop());
        setModified(true);
        if (searchActive) {
            refreshActiveSearch();
        } else {
            clampSelectionsToData();
        }
        scheduleHistoryChanged();
        table.repaint();
    }

    private void redoFromStack() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(model.snapshot());
        model.restore(redoStack.pop());
        setModified(true);
        if (searchActive) {
            refreshActiveSearch();
        } else {
            clampSelectionsToData();
        }
        scheduleHistoryChanged();
        table.repaint();
    }

    private void registerUndoableAction() {
        CommandProcessor.getInstance().executeCommand(project,
                () -> UndoManager.getInstance(project).undoableActionPerformed(new HexUndoableAction()),
                "Hex Edit",
                new Object());
    }

    private void clampSelectionsToData() {
        long last = model.getDataLength() - 1;
        if (last < 0) {
            clearByteSelection();
            return;
        }
        List<Selection> clamped = new ArrayList<>();
        for (Selection selection : selections) {
            long start = Math.max(0, Math.min(selection.start(), last));
            long end = Math.min(selection.start() + selection.length() - 1, last);
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

    private long firstSelectedOffset() {
        long min = Long.MAX_VALUE;
        for (Selection selection : selections) {
            if (selection.start() < min) {
                min = selection.start();
            }
        }
        return min == Long.MAX_VALUE ? -1 : min;
    }

    private long lastSelectedOffset() {
        long max = -1;
        for (Selection selection : selections) {
            long end = selection.start() + selection.length() - 1;
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
        private HexDocument.State beforeEdit;
        private long beforeRevision;

        private HexCellEditor() {
            super(new JTextField());
            field = (JTextField) getComponent();
            field.setHorizontalAlignment(SwingConstants.CENTER);
            field.setFont(monospacedFont());
            field.setBackground(editorBackground());
            field.setForeground(editorForeground());
            field.setSelectionColor(selectionBackground());
            field.setSelectedTextColor(selectionForeground());
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
            field.setFont(monospacedFont());
            field.setBackground(editorBackground());
            field.setForeground(editorForeground());
            field.setSelectionColor(selectionBackground());
            field.setSelectedTextColor(selectionForeground());
            beforeEdit = model.snapshot();
            beforeRevision = model.revision();
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
            HexDocument.State before = beforeEdit;
            boolean stopped = super.stopCellEditing();
            if (stopped && before != null && beforeRevision != model.revision()) {
                undoStack.push(before);
                redoStack.clear();
                registerUndoableAction();
                scheduleHistoryChanged();
            }
            beforeEdit = null;
            return stopped;
        }
    }

    private final class OffsetRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(table, value, false, false, row, column);
            long rowStart = (long) row * model.getBytesPerRow();
            long rowEnd = model.getDataLength() <= 0 ? rowStart - 1 : Math.min(rowStart + model.getBytesPerRow() - 1L, model.getDataLength() - 1);
            if (rowEnd >= rowStart && isAnyOffsetInSelection(rowStart, rowEnd)) {
                component.setBackground(caretRowBackground());
                component.setForeground(activeLineNumberForeground());
            } else {
                component.setBackground(gutterBackground());
                component.setForeground(lineNumberForeground());
            }
            setHorizontalAlignment(SwingConstants.RIGHT);
            setFont(monospacedFont());
            setBorder(JBUI.Borders.emptyRight(8));
            return component;
        }
    }

    private boolean isAnyOffsetInSelection(long from, long to) {
        for (Selection selection : selections) {
            long selEnd = selection.start() + selection.length() - 1;
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
            long start = selection.start();
            long end = start + selection.length() - 1;
            long mod = start % bytesPerRow;
            long adjust = (bytePosition - mod + bytesPerRow) % bytesPerRow;
            long candidate = start + adjust;
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
                setBackground(column == 0 ? gutterBackground() : editorBackground());
                setForeground(column == 0 ? lineNumberForeground() : editorForeground());
            }
            setHorizontalAlignment(SwingConstants.CENTER);
            Font uiFont = UIManager.getFont("TableHeader.font");
            setFont(uiFont == null ? table.getTableHeader().getFont() : uiFont);
            setBorder(JBUI.Borders.customLine(borderColor(), 0, 0, 1, 0));
            return this;
        }
    }

    private final class HexByteRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            long offset = model.byteIndexAt(row, column);
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
                long offset = (long) row * model.getBytesPerRow() + i;
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

    private void applyByteColors(java.awt.Component component, long offset) {
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

    private record Selection(long start, long length) {
        static Selection empty() {
            return new Selection(-1, 0);
        }

        boolean isEmpty() {
            return start < 0 || length <= 0;
        }
    }

    private record OperationHistoryEntry(HexDocument.OperationRecord record, boolean undone) {
    }

    record OperationHistorySelection(long sequence, boolean undone) {
    }

    private final class HexUndoableAction extends BasicUndoableAction {
        private HexUndoableAction() {
            super(file);
        }

        @Override
        public void undo() {
            undoFromStack();
        }

        @Override
        public void redo() {
            redoFromStack();
        }
    }

    private void save() {
        saveWithProgress(Path.of(file.getPath()), true, HexEditorBundle.message("progress.save.title"),
                HexEditorBundle.message("dialog.save.failed.title"));
    }

    private void saveAs() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(HexEditorBundle.message("dialog.saveAs.title"), HexEditorBundle.message("dialog.saveAs.description"));
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(file.getParent(), file.getName());
        if (wrapper == null) {
            return;
        }
        saveWithProgress(wrapper.getFile().toPath(), false, HexEditorBundle.message("progress.saveAs.title"),
                HexEditorBundle.message("dialog.saveAs.failed.title"));
    }

    private void saveWithProgress(Path target, boolean originalFile, String title, String failureTitle) {
        if (!saveInProgress.compareAndSet(false, true)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(title);
                try {
                    model.saveTo(target, progressReporter(indicator));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }

            @Override
            public void onSuccess() {
                try {
                    if (originalFile) {
                        file.refresh(false, false);
                        setModified(false);
                    }
                    deleteOperationHistoryFileIfNeeded();
                    changeSupport.firePropertyChange(HISTORY_PROPERTY, null, null);
                    updateStatus(selectedOffset());
                } finally {
                    saveInProgress.set(false);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                try {
                    Messages.showErrorDialog(project, rootMessage(error), failureTitle);
                } finally {
                    saveInProgress.set(false);
                }
            }

            @Override
            public void onCancel() {
                saveInProgress.set(false);
            }
        });
    }

    private void exportFragment() {
        List<Selection> ranges = selectedByteRanges();
        if (ranges.isEmpty()) {
            Messages.showInfoMessage(project, HexEditorBundle.message("dialog.fragmentExport.noSelection"), HexEditorBundle.message("dialog.fragmentExport.title"));
            return;
        }
        ranges.sort((a, b) -> Long.compare(a.start(), b.start()));
        FileSaverDescriptor descriptor = new FileSaverDescriptor(HexEditorBundle.message("dialog.fragmentExport.title"), HexEditorBundle.message("dialog.fragmentExport.description"));
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(file.getParent(), file.getName() + ".frag");
        if (wrapper == null) {
            return;
        }
        Path target = wrapper.getFile().toPath();
        long totalBytes = ranges.stream().mapToLong(Selection::length).sum();
        String taskTitle = HexEditorBundle.message("progress.exportFragment.title");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, taskTitle, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(taskTitle);
                long[] completedBeforeRange = {0};
                try (OutputStream output = Files.newOutputStream(target)) {
                    for (Selection range : ranges) {
                        long base = completedBeforeRange[0];
                        model.writeRangeTo(range.start(), range.length(), output,
                                (processed, total) -> updateProgress(indicator, base + processed, totalBytes));
                        completedBeforeRange[0] += range.length();
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Messages.showErrorDialog(project, rootMessage(error), HexEditorBundle.message("dialog.fragmentExport.failed.title"));
            }
        });
    }

    private void importFragmentAtStart() {
        importFragmentAt(0);
    }

    private void importFragmentAtEnd() {
        importFragmentAt(model.getDataLength());
    }

    private void importFragmentAfterSelection() {
        long insertIndex;
        if (selections.isEmpty()) {
            insertIndex = model.getDataLength();
        } else {
            Selection last = selections.get(selections.size() - 1);
            insertIndex = last.start() + last.length();
        }
        importFragmentAt(insertIndex);
    }

    private void importFragmentAt(long insertIndex) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
        descriptor.setTitle(HexEditorBundle.message("dialog.fragmentImport.title"));
        VirtualFile selected = FileChooser.chooseFile(descriptor, project, file.getParent());
        if (selected == null) {
            return;
        }
        Path selectedPath = Path.of(selected.getPath());
        long selectedSize;
        try {
            selectedSize = Files.size(selectedPath);
        } catch (IOException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.fragmentImport.failed.title"));
            return;
        }
        if (selectedSize == 0) {
            return;
        }
        HexDocument.State before = rememberUndo();
        String taskTitle = HexEditorBundle.message("progress.importFragment.title");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, taskTitle, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(taskTitle);
                model.insertFile(insertIndex, selectedPath, progressReporter(indicator));
            }

            @Override
            public void onSuccess() {
                model.dataChanged();
                finishUndo(before);
                setModified(true);
                scheduleHistoryChanged();
                setSelectionRange(insertIndex, insertIndex + selectedSize - 1);
            }

            @Override
            public void onCancel() {
                finishUndo(before);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                finishUndo(before);
                Messages.showErrorDialog(project, rootMessage(error), HexEditorBundle.message("dialog.fragmentImport.failed.title"));
            }
        });
    }

    private List<OperationHistoryEntry> operationHistoryEntries() {
        List<HexDocument.OperationRecord> activeRecords = model.operationRecords();
        Set<Long> activeSequences = new HashSet<>();
        Map<Long, OperationHistoryEntry> entries = new LinkedHashMap<>();
        for (HexDocument.OperationRecord record : activeRecords) {
            activeSequences.add(record.sequence());
            entries.put(record.sequence(), new OperationHistoryEntry(record, false));
        }
        for (HexDocument.State state : redoStack) {
            for (HexDocument.OperationRecord record : state.operations()) {
                if (!activeSequences.contains(record.sequence())) {
                    entries.putIfAbsent(record.sequence(), new OperationHistoryEntry(record, true));
                }
            }
        }
        List<OperationHistoryEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort((left, right) -> Long.compare(right.record().createdAtMillis(), left.record().createdAtMillis()));
        return sorted;
    }

    List<String> operationHistoryDisplayLines() {
        List<OperationHistoryEntry> entries = operationHistoryEntries();
        List<String> lines = new ArrayList<>(entries.size());
        for (OperationHistoryEntry entry : entries) {
            lines.add(formatOperationHistoryLine(entry));
        }
        return lines;
    }

    OperationHistorySelection operationHistorySelectionAt(int displayIndex) {
        List<OperationHistoryEntry> entries = operationHistoryEntries();
        if (displayIndex < 0 || displayIndex >= entries.size()) {
            return null;
        }
        OperationHistoryEntry entry = entries.get(displayIndex);
        return new OperationHistorySelection(entry.record().sequence(), entry.undone());
    }

    void applyOperationHistorySelection(OperationHistorySelection selection) {
        if (selection == null) {
            return;
        }
        if (selection.undone()) {
            redoOperationHistoryTo(selection.sequence());
        } else {
            undoOperationHistoryTo(selection.sequence());
        }
    }

    private void undoOperationHistoryTo(long targetSequence) {
        while (isOperationSequenceActive(targetSequence)) {
            if (!performUndoStep()) {
                return;
            }
        }
    }

    private void redoOperationHistoryTo(long targetSequence) {
        while (!isOperationSequenceActive(targetSequence) && isOperationSequenceRedoable(targetSequence)) {
            if (!performRedoStep()) {
                return;
            }
        }
    }

    private boolean performUndoStep() {
        long beforeRevision = model.revision();
        int beforeUndoSize = undoStack.size();
        int beforeRedoSize = redoStack.size();
        requestUndo();
        return beforeRevision != model.revision()
                || beforeUndoSize != undoStack.size()
                || beforeRedoSize != redoStack.size();
    }

    private boolean performRedoStep() {
        long beforeRevision = model.revision();
        int beforeUndoSize = undoStack.size();
        int beforeRedoSize = redoStack.size();
        requestRedo();
        return beforeRevision != model.revision()
                || beforeUndoSize != undoStack.size()
                || beforeRedoSize != redoStack.size();
    }

    private boolean isOperationSequenceActive(long sequence) {
        for (HexDocument.OperationRecord record : model.operationRecords()) {
            if (record.sequence() == sequence) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperationSequenceRedoable(long sequence) {
        for (HexDocument.State state : redoStack) {
            for (HexDocument.OperationRecord record : state.operations()) {
                if (record.sequence() == sequence) {
                    return true;
                }
            }
        }
        return false;
    }

    private String formatOperationHistoryLine(OperationHistoryEntry entry) {
        HexDocument.OperationRecord record = entry.record();
        StringBuilder builder = new StringBuilder();
        builder.append(HISTORY_TIME_FORMAT.format(Instant.ofEpochMilli(record.createdAtMillis())))
                .append("  #")
                .append(record.sequence())
                .append("  ");
        if (entry.undone()) {
            builder.append(HexEditorBundle.message("operationHistory.undone")).append("  ");
        }
        builder.append(operationTypeText(record.type()))
                .append("  @0x")
                .append(Long.toHexString(record.offset()).toUpperCase())
                .append("  ")
                .append(record.beforeLength())
                .append(" -> ")
                .append(record.afterLength());
        if (record.beforePreview().length > 0) {
            builder.append("  before: ").append(hexPreview(record.beforePreview()));
        }
        if (record.afterPreview().length > 0) {
            builder.append("  after: ").append(hexPreview(record.afterPreview()));
        }
        return builder.toString();
    }

    private void scheduleHistoryChanged() {
        changeSupport.firePropertyChange(HISTORY_PROPERTY, null, null);
        if (HexSupportSettings.getInstance().autoSaveHistory()) {
            historyAutoSaveTimer.restart();
        }
    }

    private void autoExportOperationHistory() {
        if (HexSupportSettings.getInstance().autoSaveHistory()) {
            exportOperationHistory(false);
        }
    }

    private void exportOperationHistory(boolean showMessage) {
        try {
            Path historyPath = operationHistoryPath();
            Files.writeString(historyPath, operationHistoryText(), StandardCharsets.UTF_8);
            if (showMessage) {
                Messages.showInfoMessage(project,
                        HexEditorBundle.message("dialog.operationHistory.exported", historyPath.toString()),
                        HexEditorBundle.message("dialog.operationHistory.title"));
            }
        } catch (IOException exception) {
            if (showMessage) {
                Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.operationHistory.exportFailed"));
            }
        }
    }

    private void deleteOperationHistoryFileIfNeeded() {
        if (!HexSupportSettings.getInstance().deleteHistoryOnSave()) {
            return;
        }
        try {
            Files.deleteIfExists(operationHistoryPath());
        } catch (IOException exception) {
            Messages.showErrorDialog(project, rootMessage(exception), HexEditorBundle.message("dialog.operationHistory.deleteFailed"));
        }
    }

    private Path operationHistoryPath() {
        return Path.of(file.getPath()).resolveSibling(file.getName() + ".hex-history.txt");
    }

    private String operationHistoryText() throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("Hex Support Operation History\n");
        builder.append("Generated: ").append(HISTORY_TIME_FORMAT.format(Instant.now())).append('\n');
        builder.append('\n');
        List<OperationHistoryEntry> entries = operationHistoryEntries();
        if (entries.isEmpty()) {
            builder.append(HexEditorBundle.message("dialog.operationHistory.empty")).append('\n');
        } else {
            for (OperationHistoryEntry entry : entries) {
                builder.append(formatOperationHistoryLine(entry)).append('\n');
            }
        }
        List<HexOperationHistoryFile.PersistedOperation> machineEntries = new ArrayList<>(entries.size());
        for (OperationHistoryEntry entry : entries) {
            machineEntries.add(new HexOperationHistoryFile.PersistedOperation(entry.record(), entry.undone()));
        }
        builder.append(HexOperationHistoryFile.machineSection(Path.of(file.getPath()), machineEntries));
        return builder.toString();
    }

    private void loadOperationHistoryIfPresent() {
        Path historyPath = operationHistoryPath();
        if (!Files.isRegularFile(historyPath)) {
            return;
        }
        HexDocument.State before = model.snapshot();
        Deque<HexDocument.State> undoBeforeLoad = new ArrayDeque<>(undoStack);
        Deque<HexDocument.State> redoBeforeLoad = new ArrayDeque<>(redoStack);
        Deque<HexDocument.State> loadedUndoStates = new ArrayDeque<>();
        Deque<HexDocument.State> loadedRedoStates = new ArrayDeque<>();
        try {
            HexOperationHistoryFile.LoadedHistory history = HexOperationHistoryFile.read(historyPath, Path.of(file.getPath()));
            if (!history.baseMatches() || history.operations().isEmpty()) {
                return;
            }
            List<HexDocument.OperationRecord> activeRecords = new ArrayList<>();
            List<HexDocument.OperationRecord> undoneRecords = new ArrayList<>();
            for (HexOperationHistoryFile.PersistedOperation operation : history.operations()) {
                if (operation.undone()) {
                    undoneRecords.add(operation.record());
                } else {
                    activeRecords.add(operation.record());
                }
            }
            model.applyHistoryRecords(activeRecords, loadedUndoStates::push);
            HexDocument.State activeState = model.snapshot();
            for (HexDocument.OperationRecord record : undoneRecords) {
                model.applyHistoryRecords(List.of(record));
                loadedRedoStates.addLast(model.snapshot());
            }
            if (!undoneRecords.isEmpty()) {
                model.restore(activeState);
            }
            undoStack.clear();
            undoStack.addAll(undoBeforeLoad);
            undoStack.addAll(loadedUndoStates);
            redoStack.clear();
            redoStack.addAll(loadedRedoStates);
            setModified(!activeRecords.isEmpty());
            scheduleHistoryChanged();
        } catch (RuntimeException | IOException exception) {
            model.restore(before);
            undoStack.clear();
            undoStack.addAll(undoBeforeLoad);
            redoStack.clear();
            redoStack.addAll(redoBeforeLoad);
        }
    }

    private String operationTypeText(HexDocument.OperationType type) {
        return switch (type) {
            case OVERWRITE -> HexEditorBundle.message("operation.overwrite");
            case FILL -> HexEditorBundle.message("operation.fill");
            case INSERT -> HexEditorBundle.message("operation.insert");
            case INSERT_FILL -> HexEditorBundle.message("operation.insertFill");
            case IMPORT_FILE -> HexEditorBundle.message("operation.importFile");
            case DELETE -> HexEditorBundle.message("operation.delete");
        };
    }

    private static String hexPreview(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return builder.toString();
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
        model.reload();
        undoStack.clear();
        redoStack.clear();
        setModified(false);
        changeSupport.firePropertyChange(HISTORY_PROPERTY, null, null);
        updateStatus(-1);
    }

    private void goToOffset() {
        String value = Messages.showInputDialog(project, HexEditorBundle.message("dialog.goToOffset.message"), HexEditorBundle.message("dialog.goToOffset.title"), null);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            long offset = parseOffset(value);
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
        updateSearchMatches(false);
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
        searchDebounceTimer.stop();
        findPanel.setVisible(false);
        if (replaceRow != null) {
            replaceRow.setVisible(false);
        }
        if (toggleReplaceButton != null) {
            toggleReplaceButton.setIcon(AllIcons.General.ArrowRight);
            toggleReplaceButton.setToolTipText(HexEditorBundle.message("button.toggleReplace.show.tooltip"));
        }
        searchActive = false;
        searchGeneration.incrementAndGet();
        searchInProgress = false;
        setSearchLoadingVisible(false);
        searchMatches.clear();
        activeMatchIndex = -1;
        matchLabel.setText("");
        table.requestFocusInWindow();
        table.repaint();
    }

    private void restoreSearchSelection() {
        if (!searchActive && findField != null && !findField.getText().isBlank()) {
            searchActive = true;
            updateSearchMatches(false);
        }
    }

    private void refreshActiveSearch() {
        if (searchActive && findPanel != null && findPanel.isVisible()) {
            updateSearchMatches(false);
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
        updateSearchMatches(false);
    }

    private void onHexChanged() {
        searchActive = true;
        if (!syncingFields && stringFindField != null && !stringFindField.getText().isEmpty()) {
            // Hex edited directly: clear the string field so the two inputs never disagree (one-way binding).
            syncingFields = true;
            stringFindField.setText("");
            syncingFields = false;
        }
        updateSearchMatches(false);
    }

    private String convertStringToHex(String text) {
        if (text.isEmpty()) {
            return "";
        }
        byte[] bytes = text.getBytes(file.getCharset());
        StringBuilder hex = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return hex.toString();
    }

    private void updateSearchMatches(boolean immediate) {
        searchGeneration.incrementAndGet();
        searchDebounceTimer.stop();
        searchMatches.clear();
        activeMatchIndex = -1;
        searchMatchesCapped = false;
        byte[] pattern = HexTableModel.parseHexBytes(findField.getText());
        if (pattern.length == 0) {
            searchInProgress = false;
            setSearchLoadingVisible(false);
            selections.clear();
            activeIndex = -1;
            anchor = -1;
            caret = -1;
            matchLabel.setText("");
            table.repaint();
            return;
        }
        if (model.isLargeMode() && !immediate) {
            searchInProgress = false;
            setSearchLoadingVisible(false);
            updateMatchLabel();
            table.repaint();
            searchDebounceTimer.restart();
            return;
        }
        updateSearchMatchesNow();
    }

    private void updateSearchMatchesNow() {
        int generation = searchGeneration.incrementAndGet();
        searchMatches.clear();
        activeMatchIndex = -1;
        searchMatchesCapped = false;
        byte[] pattern = HexTableModel.parseHexBytes(findField.getText());
        if (pattern.length == 0) {
            searchInProgress = false;
            setSearchLoadingVisible(false);
            selections.clear();
            activeIndex = -1;
            anchor = -1;
            caret = -1;
            matchLabel.setText("");
            table.repaint();
            return;
        }
        searchInProgress = true;
        setSearchLoadingVisible(model.isLargeMode());
        updateMatchLabel();
        table.repaint();
        long selected = selectedOffset();
        String taskTitle = HexEditorBundle.message("progress.search.title");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, taskTitle, true) {
            private final List<Selection> matches = new ArrayList<>();
            private boolean capped;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(taskTitle);
                HexDocument.FindResult result = model.findAll(pattern, 0, 100_000,
                        () -> generation == searchGeneration.get() && !indicator.isCanceled(),
                        progressReporter(indicator));
                for (Long offset : result.offsets()) {
                    matches.add(new Selection(offset, pattern.length));
                }
                capped = result.capped();
            }

            @Override
            public void onSuccess() {
                if (generation != searchGeneration.get()) {
                    return;
                }
                applySearchMatches(generation, selected, matches, capped);
            }

            @Override
            public void onCancel() {
                if (generation == searchGeneration.get()) {
                    searchInProgress = false;
                    setSearchLoadingVisible(false);
                    updateMatchLabel();
                    table.repaint();
                }
            }
        });
    }

    private void applySearchMatches(int generation, long selected, List<Selection> matches, boolean capped) {
        if (generation != searchGeneration.get()) {
            return;
        }
        searchInProgress = false;
        setSearchLoadingVisible(false);
        searchMatchesCapped = capped;
        searchMatches.clear();
        searchMatches.addAll(matches);
        selections.clear();
        if (searchMatches.isEmpty()) {
            activeIndex = -1;
            anchor = -1;
            caret = -1;
        } else {
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
        if (searchInProgress) {
            matchLabel.setText(HexEditorBundle.message("status.searching"));
        } else if (searchMatches.isEmpty()) {
            matchLabel.setText(findField == null || findField.getText().isBlank() ? "" : HexEditorBundle.message("status.noResults"));
        } else {
            matchLabel.setText((activeMatchIndex + 1) + "/" + searchMatches.size() + (searchMatchesCapped ? "+" : ""));
        }
    }

    private void setSearchLoadingVisible(boolean visible) {
        if (searchLoadingOverlay == null || searchLoadingIcon == null) {
            return;
        }
        searchLoadingOverlay.setVisible(visible);
        if (visible) {
            searchLoadingIcon.resume();
        } else {
            searchLoadingIcon.suspend();
        }
        searchLoadingOverlay.repaint();
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
        HexDocument.State before = rememberUndo();
        model.setBytesAt(match.start(), replacement, true, match.length());
        finishUndo(before);
        updateSearchMatches(true);
    }

    private void replaceAllMatches() {
        byte[] replacement = HexTableModel.parseHexBytes(replaceField.getText());
        if (searchMatches.isEmpty() || replacement.length == 0) {
            return;
        }
        HexDocument.State before = rememberUndo();
        List<Selection> snapshot = new ArrayList<>(searchMatches);
        for (Selection match : snapshot) {
            model.setBytesAt(match.start(), replacement, true, match.length());
        }
        finishUndo(before);
        updateSearchMatches(true);
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
        HexDocument.State before = rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        snapshot.sort((a, b) -> Long.compare(b.start(), a.start()));
        for (Selection selection : snapshot) {
            model.deleteRange(selection.start(), selection.length());
        }
        finishUndo(before);
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
        HexDocument.State before = rememberUndo();
        List<Selection> snapshot = new ArrayList<>(ranges);
        for (Selection selection : snapshot) {
            model.fillRange(selection.start(), selection.length(), 0);
        }
        finishUndo(before);
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
        HexDocument.State before = rememberUndo();
        model.deleteRange(selection.start(), selection.length());
        finishUndo(before);
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
        HexDocument.State before = rememberUndo();
        model.fillRange(selection.start(), selection.length(), 0);
        finishUndo(before);
        if (wasSearchActive) {
            refreshActiveSearch();
        }
        table.repaint();
    }

    private long parseOffset(String value) {
        String text = value.trim().replace("_", "");
        int radix = text.startsWith("0x") || text.startsWith("0X") ? 16 : 10;
        if (radix == 16) {
            text = text.substring(2);
        }
        long offset = Long.parseLong(text, radix);
        if (offset < 0 || offset >= model.getDataLength()) {
            throw new IllegalArgumentException(HexEditorBundle.message("dialog.invalidOffset.message"));
        }
        return offset;
    }

    private void selectOffset(long offset) {
        clearSearchResults();
        setSelectionRange(offset, offset);
    }

    /** Selects and reveals a byte requested by a diff viewer's Jump to Source action. */
    void navigateToOffset(long offset) {
        selectOffset(offset);
        table.requestFocusInWindow();
    }

    private void setSelectionRange(long start, long end) {
        if (model.getDataLength() == 0) {
            clearByteSelection();
            return;
        }
        long last = model.getDataLength() - 1;
        long s = Math.max(0, Math.min(start, last));
        long e = Math.max(0, Math.min(end, last));
        long lo = Math.min(s, e);
        long hi = Math.max(s, e);
        selections.clear();
        selections.add(new Selection(lo, hi - lo + 1));
        activeIndex = 0;
        anchor = s;
        caret = e;
        scrollToOffset(e);
        updateStatus(e);
    }

    private void extendActiveTo(long offset) {
        clearSearchResults();
        if (model.getDataLength() == 0) {
            clearByteSelection();
            return;
        }
        long last = model.getDataLength() - 1;
        long o = Math.max(0, Math.min(offset, last));
        long a = anchor < 0 ? o : anchor;
        long lo = Math.min(a, o);
        long hi = Math.max(a, o);
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

    private void addSelection(long offset) {
        clearSearchResults();
        if (model.getDataLength() == 0) {
            return;
        }
        long o = clampOffset(offset);
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

    private void removeOffsetFromSelection(long offset) {
        clearSearchResults();
        for (int i = 0; i < selections.size(); i++) {
            Selection selection = selections.get(i);
            long start = selection.start();
            long end = start + selection.length() - 1;
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

    private void scrollToOffset(long offset) {
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
        selections.sort((a, b) -> Long.compare(a.start(), b.start()));
        List<Selection> merged = new ArrayList<>();
        for (Selection range : selections) {
            if (!merged.isEmpty()) {
                Selection previous = merged.get(merged.size() - 1);
                long previousEnd = previous.start() + previous.length();
                long rangeEnd = range.start() + range.length();
                if (previousEnd > range.start()) {
                    long newEnd = Math.max(previousEnd, rangeEnd);
                    merged.set(merged.size() - 1, new Selection(previous.start(), newEnd - previous.start()));
                    continue;
                }
            }
            merged.add(range);
        }
        selections.clear();
        selections.addAll(merged);
    }

    private int findSelectionContaining(long offset) {
        for (int i = 0; i < selections.size(); i++) {
            Selection s = selections.get(i);
            if (offset >= s.start() && offset < s.start() + s.length()) {
                return i;
            }
        }
        return -1;
    }

    private long selectedOffset() {
        return caret;
    }

    private boolean isOffsetInSelection(long offset) {
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

    private boolean isOffsetInSearchMatch(long offset) {
        for (Selection match : searchMatches) {
            if (offset >= match.start() && offset < match.start() + match.length()) {
                return true;
            }
        }
        return false;
    }

    private int findMatchIndexContaining(long offset) {
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
            searchGeneration.incrementAndGet();
            searchDebounceTimer.stop();
            searchActive = false;
            searchInProgress = false;
            setSearchLoadingVisible(false);
            searchMatchesCapped = false;
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
            int minimumBytesPerRow = model.minimumSupportedBytesPerRow();
            int maximumBytesPerRow = Math.max(4096, minimumBytesPerRow);
            JSpinner bytesPerRow = new JSpinner(new SpinnerNumberModel(Math.max(DEFAULT_BYTES_PER_ROW, minimumBytesPerRow), 4, maximumBytesPerRow, 4));
            bytesPerRow.addChangeListener(event -> {
                int requested = (Integer) bytesPerRow.getValue();
                model.setBytesPerRow(requested);
                if (model.getBytesPerRow() != requested) {
                    SwingUtilities.invokeLater(() -> bytesPerRow.setValue(model.getBytesPerRow()));
                }
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

    private void updateStatus(long offset) {
        table.repaint();
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

    private static Color selectionBackground() {
        return HexEditorStyle.selectionBackground();
    }

    private static Color selectionForeground() {
        return HexEditorStyle.selectionForeground();
    }

    private static Color searchBackground() {
        return HexEditorStyle.searchBackground();
    }

    private static Color gutterBackground() { return HexEditorStyle.gutterBackground(); }

    private static Color lineNumberForeground() { return HexEditorStyle.lineNumberForeground(); }

    private static Color activeLineNumberForeground() { return HexEditorStyle.activeLineNumberForeground(); }

    private static Color caretRowBackground() { return HexEditorStyle.caretRowBackground(); }

    private static int editorRowHeight(JTable table) {
        return Math.max(JBUI.scale(18), Math.round(table.getFontMetrics(monospacedFont()).getHeight() * HexEditorStyle.lineSpacing()));
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

    private static HexDocument.ProgressReporter progressReporter(ProgressIndicator indicator) {
        return (processed, total) -> updateProgress(indicator, processed, total);
    }

    private static void updateProgress(ProgressIndicator indicator, long processed, long total) {
        indicator.checkCanceled();
        if (total <= 0) {
            indicator.setIndeterminate(true);
            indicator.setText2("");
            return;
        }
        long clampedProcessed = Math.max(0, Math.min(processed, total));
        indicator.setIndeterminate(false);
        indicator.setFraction((double) clampedProcessed / (double) total);
        String processedText = formatBytes(clampedProcessed);
        String totalText = formatBytes(total);
        indicator.setText2(HexEditorBundle.message("progress.bytes", processedText, totalText));
    }

    private static String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        double value = Math.max(0, bytes);
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) {
            return bytes + " " + units[unit];
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
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
    public void dispose() {
        searchGeneration.incrementAndGet();
        searchDebounceTimer.stop();
        boolean flushHistory = historyAutoSaveTimer.isRunning();
        historyAutoSaveTimer.stop();
        if (flushHistory) {
            autoExportOperationHistory();
        }
        try {
            model.close();
        } catch (IOException ignored) {
        }
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

}
