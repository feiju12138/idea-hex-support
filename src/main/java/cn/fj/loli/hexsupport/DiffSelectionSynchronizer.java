package cn.fj.loli.hexsupport;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.CaretState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.util.LineSeparator;
import org.jetbrains.annotations.NotNull;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shares text and hexadecimal selections while switching Diff viewers. */
public final class DiffSelectionSynchronizer implements Disposable {
    interface HexSelectionTarget {
        void applySynchronizedSelection(Snapshot snapshot);
    }

    record ByteSelection(int contentIndex, long start, long length) {
        long endExclusive() {
            return start + length;
        }
    }

    record Snapshot(List<ByteSelection> selections, int activeIndex) {
        Snapshot {
            selections = List.copyOf(selections);
            activeIndex = selections.isEmpty() ? -1 : Math.max(0, Math.min(activeIndex, selections.size() - 1));
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), -1);
        }
    }

    private enum Origin {
        NONE, TEXT, HEX
    }

    private record TextSelection(int contentIndex, int start, int end, boolean caretOnly) {
    }

    private record SourceLine(int contentIndex, int line) {
    }

    private record UnifiedRange(int start, int end) {
    }

    private record TextSnapshot(List<TextSelection> selections, int activeIndex,
                                List<? extends DocumentContent> contents) {
    }

    private static final class State {
        private byte[][] contentBytes;
        private TextSnapshot pendingTextSnapshot;
        private Snapshot byteSnapshot = Snapshot.empty();
        private Origin origin = Origin.NONE;
        private long generation;
        private final Map<HexSelectionTarget, Boolean> hexTargets = new IdentityHashMap<>();
    }

    private record Binding(ContentDiffRequest request, FrameDiffTool.DiffViewer viewer, State state) {
    }

    private static final Key<State> STATE_KEY = Key.create("cn.fj.loli.hexsupport.diffSelectionState");

    private final Map<Editor, Long> appliedGenerations = Collections.synchronizedMap(new IdentityHashMap<>());
    private boolean syncing;

    public DiffSelectionSynchronizer() {
        EditorFactory factory = EditorFactory.getInstance();
        factory.getEventMulticaster().addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent event) {
                editorSelectionChanged(event.getEditor());
            }
        }, this);
        factory.getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                editorSelectionChanged(event.getEditor());
            }

            @Override
            public void caretAdded(@NotNull CaretEvent event) {
                editorSelectionChanged(event.getEditor());
            }

            @Override
            public void caretRemoved(@NotNull CaretEvent event) {
                editorSelectionChanged(event.getEditor());
            }
        }, this);
        factory.addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                installFocusHandler(event.getEditor());
                ApplicationManager.getApplication().invokeLater(() -> applyStoredSelection(event.getEditor()));
            }

            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                appliedGenerations.remove(event.getEditor());
            }
        }, this);
        for (Editor editor : factory.getAllEditors()) {
            installFocusHandler(editor);
        }
    }

    static DiffSelectionSynchronizer getInstance() {
        return ApplicationManager.getApplication().getService(DiffSelectionSynchronizer.class);
    }

    void register(DiffContext context, ContentDiffRequest request) {
        if (request.getUserData(STATE_KEY) == null) {
            request.putUserData(STATE_KEY, new State());
        }
    }

    Snapshot installHexContents(ContentDiffRequest request, byte[] left, byte[] right) {
        State state = state(request);
        state.contentBytes = new byte[][]{left, right};
        if (state.pendingTextSnapshot != null) {
            state.byteSnapshot = toByteSnapshot(state.pendingTextSnapshot, state.contentBytes);
            state.pendingTextSnapshot = null;
        }
        return state.byteSnapshot;
    }

    void registerHexTarget(ContentDiffRequest request, HexSelectionTarget target) {
        state(request).hexTargets.put(target, Boolean.TRUE);
    }

    void unregisterHexTarget(ContentDiffRequest request, HexSelectionTarget target) {
        State state = request.getUserData(STATE_KEY);
        if (state != null) {
            state.hexTargets.remove(target);
        }
    }

    void hexSelectionChanged(ContentDiffRequest request, Snapshot snapshot, HexSelectionTarget source) {
        State state = state(request);
        state.byteSnapshot = snapshot;
        state.pendingTextSnapshot = null;
        state.origin = Origin.HEX;
        state.generation++;
        applyToHexTargets(state, source);
        applyToOpenTextViewers(request, null);
    }

    private State state(ContentDiffRequest request) {
        State state = request.getUserData(STATE_KEY);
        if (state == null) {
            state = new State();
            request.putUserData(STATE_KEY, state);
        }
        return state;
    }

    private void installFocusHandler(Editor editor) {
        editor.getContentComponent().addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                applyStoredSelection(editor);
            }
        });
    }

    private void editorSelectionChanged(Editor editor) {
        if (syncing || editor.isDisposed() || editor.getEditorKind() != EditorKind.DIFF) {
            return;
        }
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> editorSelectionChanged(editor));
            return;
        }
        Binding binding = binding(editor);
        if (binding == null) {
            return;
        }
        Long appliedGeneration = appliedGenerations.get(editor);
        if (binding.state.origin != Origin.NONE
                && (binding.state.pendingTextSnapshot != null || !binding.state.byteSnapshot.selections().isEmpty())
                && (appliedGeneration == null || appliedGeneration.longValue() != binding.state.generation)) {
            applyStoredSelection(binding);
            return;
        }

        TextSnapshot snapshot = captureTextSelection(editor, binding.viewer);
        if (snapshot == null) {
            return;
        }
        binding.state.pendingTextSnapshot = snapshot;
        if (binding.state.contentBytes != null) {
            binding.state.byteSnapshot = toByteSnapshot(snapshot, binding.state.contentBytes);
            binding.state.pendingTextSnapshot = null;
        }
        binding.state.origin = Origin.TEXT;
        binding.state.generation++;
        appliedGenerations.put(editor, binding.state.generation);
        if (binding.state.contentBytes != null) {
            applyToHexTargets(binding.state, null);
        }
        applyToOpenTextViewers(binding.request, binding.viewer);
    }

    private void applyStoredSelection(Editor editor) {
        if (syncing || editor.isDisposed() || editor.getEditorKind() != EditorKind.DIFF
                || !ApplicationManager.getApplication().isDispatchThread()) {
            return;
        }
        Binding binding = binding(editor);
        if (binding != null && binding.state.origin != Origin.NONE) {
            applyStoredSelection(binding);
        }
    }

    private void applyStoredSelection(Binding binding) {
        if (binding.state.pendingTextSnapshot == null && binding.state.byteSnapshot.selections().isEmpty()) {
            return;
        }
        syncing = true;
        try {
            if (binding.viewer instanceof TwosideTextDiffViewer viewer) {
                if (binding.state.pendingTextSnapshot != null) {
                    applyTextToTwosideViewer(viewer, binding.state.pendingTextSnapshot, binding.state.generation);
                } else {
                    applyToTwosideViewer(viewer, binding.state);
                }
            } else if (binding.viewer instanceof UnifiedDiffViewer viewer) {
                if (binding.state.pendingTextSnapshot != null) {
                    applyTextToUnifiedViewer(viewer, binding.state.pendingTextSnapshot, binding.state.generation);
                } else {
                    applyToUnifiedViewer(viewer, binding.state);
                }
            }
        } finally {
            syncing = false;
        }
    }

    private static void applyToHexTargets(State state, HexSelectionTarget source) {
        List<HexSelectionTarget> targets = new ArrayList<>(state.hexTargets.keySet());
        for (HexSelectionTarget target : targets) {
            if (target != source) {
                target.applySynchronizedSelection(state.byteSnapshot);
            }
        }
    }

    private void applyToOpenTextViewers(ContentDiffRequest request, FrameDiffTool.DiffViewer source) {
        Map<FrameDiffTool.DiffViewer, Boolean> appliedViewers = new IdentityHashMap<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            Binding binding = binding(editor);
            if (binding != null && binding.request == request && binding.viewer != source
                    && appliedViewers.put(binding.viewer, Boolean.TRUE) == null) {
                applyStoredSelection(binding);
            }
        }
    }

    private Binding binding(Editor editor) {
        var dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
        DiffRequest diffRequest = DiffDataKeys.DIFF_REQUEST.getData(dataContext);
        if (!(diffRequest instanceof ContentDiffRequest request)) {
            return null;
        }
        State state = request.getUserData(STATE_KEY);
        if (state == null) {
            return null;
        }
        FrameDiffTool.DiffViewer viewer = DiffDataKeys.DIFF_VIEWER.getData(dataContext);
        if (viewer == null) {
            viewer = DiffDataKeys.WRAPPING_DIFF_VIEWER.getData(dataContext);
        }
        if (viewer instanceof TwosideTextDiffViewer twoside && twoside.getEditors().contains(editor)) {
            return new Binding(request, viewer, state);
        }
        if (viewer instanceof UnifiedDiffViewer unified && unified.getEditor() == editor) {
            return new Binding(request, viewer, state);
        }
        return null;
    }

    private static TextSnapshot captureTextSelection(Editor editor, FrameDiffTool.DiffViewer viewer) {
        if (viewer instanceof TwosideTextDiffViewer twoside) {
            return captureTwosideSelection(editor, twoside);
        }
        if (viewer instanceof UnifiedDiffViewer unified) {
            return captureUnifiedSelection(editor, unified);
        }
        return null;
    }

    private static TextSnapshot captureTwosideSelection(Editor editor, TwosideTextDiffViewer viewer) {
        int activeContentIndex = viewer.getEditors().indexOf(editor);
        if (activeContentIndex < 0 || activeContentIndex >= viewer.getContents().size()) {
            return null;
        }
        List<TextSelection> selections = new ArrayList<>();
        int activeIndex = -1;
        for (int contentIndex = 0; contentIndex < viewer.getEditors().size(); contentIndex++) {
            Editor contentEditor = viewer.getEditors().get(contentIndex);
            Caret primaryCaret = contentEditor.getCaretModel().getPrimaryCaret();
            for (Caret caret : contentEditor.getCaretModel().getAllCarets()) {
                boolean activeEditorCaret = contentIndex == activeContentIndex;
                if (!activeEditorCaret && !caret.hasSelection()) {
                    continue;
                }
                int index = selections.size();
                int start = caret.hasSelection() ? caret.getSelectionStart() : caret.getOffset();
                int end = caret.hasSelection() ? caret.getSelectionEnd() : caret.getOffset();
                selections.add(new TextSelection(contentIndex, start, end, !caret.hasSelection()));
                if (activeEditorCaret && caret == primaryCaret) {
                    activeIndex = index;
                }
            }
        }
        return new TextSnapshot(selections, activeIndex, viewer.getContents());
    }

    private static TextSnapshot captureUnifiedSelection(Editor editor, UnifiedDiffViewer viewer) {
        Document unifiedDocument = editor.getDocument();
        Caret primaryCaret = editor.getCaretModel().getPrimaryCaret();
        List<TextSelection> selections = new ArrayList<>();
        int activeIndex = -1;
        for (Caret caret : editor.getCaretModel().getAllCarets()) {
            int before = selections.size();
            if (caret.hasSelection()) {
                addUnifiedRange(viewer, unifiedDocument, caret.getSelectionStart(), caret.getSelectionEnd(), selections);
            } else {
                addUnifiedCaret(viewer, unifiedDocument, caret.getOffset(), selections);
            }
            if (caret == primaryCaret && selections.size() > before) {
                activeIndex = before;
            }
        }
        return new TextSnapshot(selections, activeIndex, viewer.getContents());
    }

    private static void addUnifiedCaret(UnifiedDiffViewer viewer, Document unifiedDocument, int offset,
                                        List<TextSelection> selections) {
        int clamped = Math.max(0, Math.min(offset, unifiedDocument.getTextLength()));
        int line = unifiedDocument.getLineNumber(clamped);
        int column = clamped - unifiedDocument.getLineStartOffset(line);
        for (SourceLine source : sourceLines(viewer, line)) {
            Side side = Side.fromIndex(source.contentIndex());
            Document sourceDocument = viewer.getDocument(side);
            if (source.line() < 0 || source.line() >= sourceDocument.getLineCount()) {
                continue;
            }
            int sourceOffset = sourceDocument.getLineStartOffset(source.line())
                    + Math.min(column, sourceDocument.getLineEndOffset(source.line())
                    - sourceDocument.getLineStartOffset(source.line()));
            selections.add(new TextSelection(source.contentIndex(), sourceOffset, sourceOffset, true));
        }
    }

    private static void addUnifiedRange(UnifiedDiffViewer viewer, Document unifiedDocument, int start, int end,
                                        List<TextSelection> selections) {
        int clampedStart = Math.max(0, Math.min(start, unifiedDocument.getTextLength()));
        int clampedEnd = Math.max(clampedStart, Math.min(end, unifiedDocument.getTextLength()));
        int firstLine = unifiedDocument.getLineNumber(clampedStart);
        int lastLine = unifiedDocument.getLineNumber(Math.max(clampedStart, clampedEnd - 1));
        List<TextSelection> projected = new ArrayList<>();
        for (int line = firstLine; line <= lastLine; line++) {
            int unifiedLineStart = unifiedDocument.getLineStartOffset(line);
            int unifiedLineEnd = unifiedDocument.getLineEndOffset(line);
            int segmentStart = Math.max(clampedStart, unifiedLineStart);
            int segmentEnd = Math.min(clampedEnd, unifiedLineEnd);
            boolean includesNewline = line + 1 < unifiedDocument.getLineCount() && clampedEnd > unifiedLineEnd;
            if (segmentEnd <= segmentStart && !includesNewline) {
                continue;
            }
            for (SourceLine source : sourceLines(viewer, line)) {
                Side side = Side.fromIndex(source.contentIndex());
                Document sourceDocument = viewer.getDocument(side);
                if (source.line() < 0 || source.line() >= sourceDocument.getLineCount()) {
                    continue;
                }
                int sourceLineStart = sourceDocument.getLineStartOffset(source.line());
                int sourceLineEnd = sourceDocument.getLineEndOffset(source.line());
                int startColumn = Math.max(0, segmentStart - unifiedLineStart);
                int endColumn = Math.max(startColumn, segmentEnd - unifiedLineStart);
                int sourceStart = sourceLineStart + Math.min(startColumn, sourceLineEnd - sourceLineStart);
                int sourceEnd = sourceLineStart + Math.min(endColumn, sourceLineEnd - sourceLineStart);
                if (includesNewline && source.line() + 1 < sourceDocument.getLineCount()) {
                    sourceEnd = sourceDocument.getLineStartOffset(source.line() + 1);
                }
                addProjectedSelection(projected,
                        new TextSelection(source.contentIndex(), sourceStart, sourceEnd, false));
            }
        }
        selections.addAll(projected);
    }

    private static void addProjectedSelection(List<TextSelection> selections, TextSelection candidate) {
        for (int i = selections.size() - 1; i >= 0; i--) {
            TextSelection previous = selections.get(i);
            if (previous.contentIndex() != candidate.contentIndex()) {
                continue;
            }
            if (!previous.caretOnly() && !candidate.caretOnly() && previous.end() == candidate.start()) {
                selections.set(i, new TextSelection(previous.contentIndex(), previous.start(), candidate.end(), false));
                return;
            }
            break;
        }
        selections.add(candidate);
    }

    private static List<SourceLine> sourceLines(UnifiedDiffViewer viewer, int unifiedLine) {
        Pair<int[], Side> approximate = viewer.transferLineFromOneside(unifiedLine);
        Side preferred = approximate.second;
        List<SourceLine> result = new ArrayList<>(2);
        addExactSourceLine(viewer, unifiedLine, preferred, result);
        addExactSourceLine(viewer, unifiedLine, preferred.other(), result);
        if (result.isEmpty()) {
            Side side = resolvedSide(approximate);
            result.add(new SourceLine(side.getIndex(), side.select(approximate.first)));
        }
        return result;
    }

    private static void addExactSourceLine(UnifiedDiffViewer viewer, int unifiedLine, Side side,
                                           List<SourceLine> result) {
        int sourceLine = viewer.transferLineFromOnesideStrict(side, unifiedLine);
        if (sourceLine >= 0) {
            result.add(new SourceLine(side.getIndex(), sourceLine));
        }
    }

    private static Side resolvedSide(Pair<int[], Side> mapping) {
        Side side = mapping.second;
        if (side.select(mapping.first) >= 0) {
            return side;
        }
        return side.other();
    }

    private static Snapshot toByteSnapshot(TextSnapshot textSnapshot, byte[][] contentBytes) {
        List<TextByteOffsetMapper> mappers = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            byte[] bytes = i < contentBytes.length ? contentBytes[i] : new byte[0];
            DocumentContent content = textSnapshot.contents().get(i);
            mappers.add(mapper(content, bytes));
        }

        List<ByteSelection> result = new ArrayList<>();
        int activeIndex = -1;
        for (int i = 0; i < textSnapshot.selections().size(); i++) {
            TextSelection selection = textSnapshot.selections().get(i);
            if (selection.contentIndex() < 0 || selection.contentIndex() >= contentBytes.length) {
                continue;
            }
            byte[] bytes = contentBytes[selection.contentIndex()];
            if (bytes.length == 0) {
                continue;
            }
            TextByteOffsetMapper mapper = mappers.get(selection.contentIndex());
            long start = Math.min(mapper.textStartToByte(selection.start()), bytes.length - 1L);
            long mappedEnd = selection.caretOnly()
                    ? start + 1
                    : mapper.textEndToByte(selection.end());
            long end = Math.max(start + 1, Math.min(mappedEnd, bytes.length));
            if (i == textSnapshot.activeIndex()) {
                activeIndex = result.size();
            }
            result.add(new ByteSelection(selection.contentIndex(), start, end - start));
        }
        if (activeIndex < 0 && !result.isEmpty()) {
            activeIndex = result.size() - 1;
        }
        return new Snapshot(result, activeIndex);
    }

    private static TextByteOffsetMapper mapper(DocumentContent content, byte[] bytes) {
        Charset charset = content.getCharset();
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        byte[] candidateBom = HexDiffViewer.bomFor(charset);
        byte[] bom = candidateBom != null && startsWith(bytes, candidateBom) ? candidateBom : null;
        LineSeparator lineSeparator = content.getLineSeparator();
        String separator = lineSeparator == null ? "\n" : lineSeparator.getSeparatorString();
        return new TextByteOffsetMapper(content.getDocument().getImmutableCharSequence(), charset, bom, separator,
                bytes.length, offset -> bytes[Math.toIntExact(offset)] & 0xFF);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (prefix.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void applyTextToTwosideViewer(TwosideTextDiffViewer viewer, TextSnapshot snapshot, long generation) {
        for (int contentIndex = 0; contentIndex < 2; contentIndex++) {
            Editor editor = viewer.getEditors().get(contentIndex);
            List<CaretState> carets = new ArrayList<>();
            int localActive = -1;
            for (int i = 0; i < snapshot.selections().size(); i++) {
                TextSelection selection = snapshot.selections().get(i);
                if (selection.contentIndex() != contentIndex) {
                    continue;
                }
                if (i == snapshot.activeIndex()) {
                    localActive = carets.size();
                }
                carets.add(toCaretState(editor, selection));
            }
            setCarets(editor, carets, localActive);
            appliedGenerations.put(editor, generation);
            if (localActive >= 0) {
                editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
        }
    }

    private void applyTextToUnifiedViewer(UnifiedDiffViewer viewer, TextSnapshot snapshot, long generation) {
        Editor editor = viewer.getEditor();
        List<CaretState> carets = new ArrayList<>();
        List<UnifiedRange> ranges = new ArrayList<>();
        int localActive = -1;
        for (int i = 0; i < snapshot.selections().size(); i++) {
            TextSelection selection = snapshot.selections().get(i);
            Side side = Side.fromIndex(selection.contentIndex());
            int unifiedStart = toUnifiedOffset(viewer, side, selection.start());
            int unifiedEnd = toUnifiedOffset(viewer, side, selection.end());
            int caretIndex = addUnifiedCaretState(editor, carets, ranges, unifiedStart, unifiedEnd);
            if (i == snapshot.activeIndex()) {
                localActive = caretIndex;
            }
        }
        setCarets(editor, carets, localActive);
        appliedGenerations.put(editor, generation);
        if (localActive >= 0) {
            editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        }
    }

    private void applyToTwosideViewer(TwosideTextDiffViewer viewer, State state) {
        if (state.contentBytes == null) {
            return;
        }
        for (int contentIndex = 0; contentIndex < 2; contentIndex++) {
            Editor editor = viewer.getEditors().get(contentIndex);
            TextByteOffsetMapper mapper = mapper(viewer.getContents().get(contentIndex), state.contentBytes[contentIndex]);
            List<CaretState> carets = new ArrayList<>();
            int localActive = -1;
            for (int i = 0; i < state.byteSnapshot.selections().size(); i++) {
                ByteSelection selection = state.byteSnapshot.selections().get(i);
                if (selection.contentIndex() != contentIndex) {
                    continue;
                }
                if (i == state.byteSnapshot.activeIndex()) {
                    localActive = carets.size();
                }
                carets.add(toCaretState(editor, mapper, selection));
            }
            setCarets(editor, carets, localActive);
            appliedGenerations.put(editor, state.generation);
            if (localActive >= 0) {
                editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
        }
    }

    private void applyToUnifiedViewer(UnifiedDiffViewer viewer, State state) {
        if (state.contentBytes == null) {
            return;
        }
        Editor editor = viewer.getEditor();
        List<TextByteOffsetMapper> mappers = List.of(
                mapper(viewer.getContent(Side.LEFT), state.contentBytes[0]),
                mapper(viewer.getContent(Side.RIGHT), state.contentBytes[1]));
        List<CaretState> carets = new ArrayList<>();
        List<UnifiedRange> ranges = new ArrayList<>();
        int localActive = -1;
        for (int i = 0; i < state.byteSnapshot.selections().size(); i++) {
            ByteSelection selection = state.byteSnapshot.selections().get(i);
            Side side = Side.fromIndex(selection.contentIndex());
            TextByteOffsetMapper mapper = mappers.get(selection.contentIndex());
            int sourceStart = mapper.byteToTextStart(selection.start());
            int sourceEnd = mapper.byteToTextEnd(selection.endExclusive());
            int unifiedStart = toUnifiedOffset(viewer, side, sourceStart);
            int unifiedEnd = toUnifiedOffset(viewer, side, sourceEnd);
            int caretIndex = addUnifiedCaretState(editor, carets, ranges, unifiedStart, unifiedEnd);
            if (i == state.byteSnapshot.activeIndex()) {
                localActive = caretIndex;
            }
        }
        setCarets(editor, carets, localActive);
        appliedGenerations.put(editor, state.generation);
        if (localActive >= 0) {
            editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        }
    }

    private static int toUnifiedOffset(UnifiedDiffViewer viewer, Side side, int sourceOffset) {
        Document sourceDocument = viewer.getDocument(side);
        int clamped = Math.max(0, Math.min(sourceOffset, sourceDocument.getTextLength()));
        int sourceLine = sourceDocument.getLineNumber(clamped);
        int column = clamped - sourceDocument.getLineStartOffset(sourceLine);
        int unifiedLine = viewer.transferLineToOnesideStrict(side, sourceLine);
        if (unifiedLine < 0) {
            unifiedLine = viewer.transferLineToOneside(side, sourceLine);
        }
        Document unifiedDocument = viewer.getEditor().getDocument();
        unifiedLine = Math.max(0, Math.min(unifiedLine, unifiedDocument.getLineCount() - 1));
        int lineStart = unifiedDocument.getLineStartOffset(unifiedLine);
        int lineEnd = unifiedDocument.getLineEndOffset(unifiedLine);
        return lineStart + Math.min(column, lineEnd - lineStart);
    }

    private static CaretState toCaretState(Editor editor, TextByteOffsetMapper mapper, ByteSelection selection) {
        int start = mapper.byteToTextStart(selection.start());
        int end = mapper.byteToTextEnd(selection.endExclusive());
        return new CaretState(editor.offsetToLogicalPosition(end),
                editor.offsetToLogicalPosition(start), editor.offsetToLogicalPosition(end));
    }

    private static CaretState toCaretState(Editor editor, TextSelection selection) {
        int start = Math.max(0, Math.min(selection.start(), editor.getDocument().getTextLength()));
        int end = Math.max(start, Math.min(selection.end(), editor.getDocument().getTextLength()));
        return new CaretState(editor.offsetToLogicalPosition(end),
                editor.offsetToLogicalPosition(start), editor.offsetToLogicalPosition(end));
    }

    private static int addUnifiedCaretState(Editor editor, List<CaretState> carets, List<UnifiedRange> ranges,
                                            int start, int end) {
        UnifiedRange range = new UnifiedRange(start, end);
        int existing = ranges.indexOf(range);
        if (existing >= 0) {
            return existing;
        }
        ranges.add(range);
        carets.add(new CaretState(editor.offsetToLogicalPosition(end),
                editor.offsetToLogicalPosition(start), editor.offsetToLogicalPosition(end)));
        return carets.size() - 1;
    }

    private static void setCarets(Editor editor, List<CaretState> carets, int activeIndex) {
        CaretModel caretModel = editor.getCaretModel();
        if (carets.isEmpty()) {
            caretModel.removeSecondaryCarets();
            caretModel.getPrimaryCaret().removeSelection();
            return;
        }
        int resolvedActive = activeIndex >= 0 && activeIndex < carets.size() ? activeIndex : carets.size() - 1;
        if (resolvedActive != carets.size() - 1) {
            List<CaretState> ordered = new ArrayList<>(carets.size());
            for (int i = 0; i < carets.size(); i++) {
                if (i != resolvedActive) {
                    ordered.add(carets.get(i));
                }
            }
            ordered.add(carets.get(resolvedActive));
            carets = ordered;
        }
        caretModel.setCaretsAndSelections(carets);
    }

    @Override
    public void dispose() {
    }
}
