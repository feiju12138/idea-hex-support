package cn.fj.loli.hexsupport;

import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.CaretState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps all selections in a Hex editor and the file's embedded text editors in sync. */
final class HexSelectionSynchronizer {
    record ByteSelection(long start, long length) {
        long endExclusive() {
            return start + length;
        }
    }

    private final Project project;
    private final VirtualFile file;
    private final HexFileEditor hexEditor;
    private final Map<Document, CachedMapper> mapperCache = new IdentityHashMap<>();
    private boolean syncing;

    HexSelectionSynchronizer(Project project, VirtualFile file, HexFileEditor hexEditor) {
        this.project = project;
        this.file = file;
        this.hexEditor = hexEditor;
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent event) {
                textSelectionChanged(event.getEditor());
            }
        }, hexEditor);
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                textSelectionChanged(event.getEditor());
            }

            @Override
            public void caretAdded(@NotNull CaretEvent event) {
                textSelectionChanged(event.getEditor());
            }

            @Override
            public void caretRemoved(@NotNull CaretEvent event) {
                textSelectionChanged(event.getEditor());
            }
        }, hexEditor);
    }

    void hexSelectionChanged(List<ByteSelection> selections, int activeIndex) {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (isFileTextEditor(editor)) {
                    applyHexSelections(editor, selections, activeIndex);
                }
            }
        } finally {
            syncing = false;
        }
    }

    private void textSelectionChanged(Editor editor) {
        if (syncing || !isFileTextEditor(editor)) {
            return;
        }
        syncing = true;
        try {
            TextByteOffsetMapper mapper = mapperFor(editor);
            CaretModel caretModel = editor.getCaretModel();
            Caret primaryCaret = caretModel.getPrimaryCaret();
            List<ByteSelection> byteSelections = new ArrayList<>();
            int activeIndex = -1;
            for (Caret caret : caretModel.getAllCarets()) {
                int selectionStart = caret.hasSelection() ? caret.getSelectionStart() : caret.getOffset();
                int selectionEnd = caret.hasSelection() ? caret.getSelectionEnd() : caret.getOffset();
                long byteStart = mapper.textStartToByte(selectionStart);
                long byteEnd = caret.hasSelection() ? mapper.textEndToByte(selectionEnd) : byteStart + 1;
                int index = byteSelections.size();
                byteSelections.add(new ByteSelection(byteStart, Math.max(1, byteEnd - byteStart)));
                if (caret == primaryCaret) {
                    activeIndex = index;
                }
            }
            hexEditor.applySynchronizedSelections(byteSelections, activeIndex);
        } finally {
            syncing = false;
        }
    }

    private void applyHexSelections(Editor editor, List<ByteSelection> selections, int activeIndex) {
        CaretModel caretModel = editor.getCaretModel();
        if (selections.isEmpty()) {
            caretModel.removeSecondaryCarets();
            caretModel.getPrimaryCaret().removeSelection();
            return;
        }
        TextByteOffsetMapper mapper = mapperFor(editor);
        List<CaretState> states = new ArrayList<>(selections.size());
        int resolvedActiveIndex = activeIndex >= 0 && activeIndex < selections.size()
                ? activeIndex
                : selections.size() - 1;
        for (int i = 0; i < selections.size(); i++) {
            if (i != resolvedActiveIndex) {
                states.add(toCaretState(editor, mapper, selections.get(i)));
            }
        }
        // CaretModel keeps the final state as the primary caret.
        states.add(toCaretState(editor, mapper, selections.get(resolvedActiveIndex)));
        caretModel.setCaretsAndSelections(states);
        editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    private static CaretState toCaretState(Editor editor, TextByteOffsetMapper mapper, ByteSelection selection) {
        int start = mapper.byteToTextStart(selection.start());
        int end = mapper.byteToTextEnd(selection.endExclusive());
        return new CaretState(editor.offsetToLogicalPosition(end),
                editor.offsetToLogicalPosition(start),
                editor.offsetToLogicalPosition(end));
    }

    private boolean isFileTextEditor(Editor editor) {
        if (editor.isDisposed() || editor.getProject() != project) {
            return false;
        }
        VirtualFile editorFile = editor.getVirtualFile();
        if (editorFile == null) {
            editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        }
        if (!file.equals(editorFile)) {
            return false;
        }
        for (FileEditor candidate : FileEditorManager.getInstance(project).getAllEditors(file)) {
            if (candidate == hexEditor) {
                continue;
            }
            if (candidate.getComponent() == editor.getComponent()
                    || SwingUtilities.isDescendingFrom(editor.getComponent(), candidate.getComponent())) {
                return true;
            }
        }
        return false;
    }

    private TextByteOffsetMapper mapperFor(Editor editor) {
        Document document = editor.getDocument();
        long stamp = document.getModificationStamp();
        Charset charset = file.getCharset();
        byte[] bom = file.getBOM();
        String lineSeparator = FileDocumentManager.getInstance().getLineSeparator(file, project);
        long binaryRevision = hexEditor.binaryRevision();
        long binaryLength = hexEditor.binaryLength();
        CachedMapper cached = mapperCache.get(document);
        if (cached == null || cached.stamp != stamp || !cached.charset.equals(charset)
                || !Arrays.equals(cached.bom, bom) || !Objects.equals(cached.lineSeparator, lineSeparator)
                || cached.binaryRevision != binaryRevision || cached.binaryLength != binaryLength) {
            cached = new CachedMapper(stamp, charset, bom == null ? null : bom.clone(), lineSeparator,
                    binaryRevision, binaryLength,
                    new TextByteOffsetMapper(document.getImmutableCharSequence(), charset, bom, lineSeparator,
                            binaryLength, hexEditor::unsignedByteAt));
            mapperCache.put(document, cached);
        }
        return cached.mapper;
    }

    private record CachedMapper(long stamp, Charset charset, byte[] bom, String lineSeparator,
                                long binaryRevision, long binaryLength,
                                TextByteOffsetMapper mapper) {
    }
}
