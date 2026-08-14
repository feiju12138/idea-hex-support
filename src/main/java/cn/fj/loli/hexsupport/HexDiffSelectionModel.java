package cn.fj.loli.hexsupport;

import java.util.ArrayList;
import java.util.List;

/** Source-offset selection model shared by both hexadecimal Diff viewers. */
final class HexDiffSelectionModel {
    private final List<DiffSelectionSynchronizer.ByteSelection> selections = new ArrayList<>();
    private int activeIndex = -1;
    private int anchorContentIndex = -1;
    private long anchor = -1;
    private long caret = -1;

    void replace(DiffSelectionSynchronizer.Snapshot snapshot) {
        selections.clear();
        selections.addAll(snapshot.selections());
        activeIndex = snapshot.activeIndex();
        if (activeIndex >= 0 && activeIndex < selections.size()) {
            DiffSelectionSynchronizer.ByteSelection active = selections.get(activeIndex);
            anchorContentIndex = active.contentIndex();
            anchor = active.start();
            caret = active.endExclusive() - 1;
        } else {
            anchorContentIndex = -1;
            anchor = -1;
            caret = -1;
        }
        normalize();
    }

    void press(int contentIndex, long offset, boolean control, boolean shift) {
        if (offset < 0) {
            return;
        }
        if (control && !shift) {
            int selected = findContaining(contentIndex, offset);
            if (selected >= 0) {
                removeOffset(selected, offset);
            } else {
                selections.add(new DiffSelectionSynchronizer.ByteSelection(contentIndex, offset, 1));
            }
            anchorContentIndex = contentIndex;
            anchor = offset;
            caret = offset;
            normalize();
            activeIndex = findContaining(contentIndex, offset);
            if (activeIndex < 0 && !selections.isEmpty()) {
                activeIndex = selections.size() - 1;
            }
            return;
        }
        if (control) {
            selections.add(new DiffSelectionSynchronizer.ByteSelection(contentIndex, offset, 1));
            anchorContentIndex = contentIndex;
            anchor = offset;
            caret = offset;
            normalize();
            activeIndex = findContaining(contentIndex, offset);
            return;
        }
        if (shift && anchor >= 0 && anchorContentIndex == contentIndex) {
            extend(contentIndex, offset);
            return;
        }
        selections.clear();
        selections.add(new DiffSelectionSynchronizer.ByteSelection(contentIndex, offset, 1));
        activeIndex = 0;
        anchorContentIndex = contentIndex;
        anchor = offset;
        caret = offset;
    }

    void drag(int contentIndex, long offset) {
        if (offset >= 0 && anchor >= 0 && anchorContentIndex == contentIndex) {
            extend(contentIndex, offset);
        }
    }

    boolean contains(int contentIndex, long offset) {
        return findContaining(contentIndex, offset) >= 0;
    }

    DiffSelectionSynchronizer.Snapshot snapshot() {
        return new DiffSelectionSynchronizer.Snapshot(selections, activeIndex);
    }

    DiffSelectionSynchronizer.ByteSelection activeSelection() {
        return activeIndex >= 0 && activeIndex < selections.size() ? selections.get(activeIndex) : null;
    }

    private void extend(int contentIndex, long offset) {
        long start = Math.min(anchor, offset);
        long end = Math.max(anchor, offset);
        if (activeIndex >= 0 && activeIndex < selections.size()
                && selections.get(activeIndex).contentIndex() == contentIndex) {
            selections.set(activeIndex, new DiffSelectionSynchronizer.ByteSelection(contentIndex, start, end - start + 1));
        } else {
            selections.add(new DiffSelectionSynchronizer.ByteSelection(contentIndex, start, end - start + 1));
        }
        caret = offset;
        normalize();
        activeIndex = findContaining(contentIndex, offset);
    }

    private void removeOffset(int index, long offset) {
        DiffSelectionSynchronizer.ByteSelection selection = selections.remove(index);
        long end = selection.endExclusive();
        if (offset > selection.start()) {
            selections.add(new DiffSelectionSynchronizer.ByteSelection(selection.contentIndex(),
                    selection.start(), offset - selection.start()));
        }
        if (offset + 1 < end) {
            selections.add(new DiffSelectionSynchronizer.ByteSelection(selection.contentIndex(),
                    offset + 1, end - offset - 1));
        }
    }

    private int findContaining(int contentIndex, long offset) {
        for (int i = 0; i < selections.size(); i++) {
            DiffSelectionSynchronizer.ByteSelection selection = selections.get(i);
            if (selection.contentIndex() == contentIndex
                    && offset >= selection.start() && offset < selection.endExclusive()) {
                return i;
            }
        }
        return -1;
    }

    private void normalize() {
        selections.removeIf(selection -> selection.contentIndex() < 0 || selection.start() < 0 || selection.length() <= 0);
        selections.sort((left, right) -> {
            int side = Integer.compare(left.contentIndex(), right.contentIndex());
            return side != 0 ? side : Long.compare(left.start(), right.start());
        });
        List<DiffSelectionSynchronizer.ByteSelection> merged = new ArrayList<>();
        for (DiffSelectionSynchronizer.ByteSelection selection : selections) {
            if (!merged.isEmpty()) {
                DiffSelectionSynchronizer.ByteSelection previous = merged.get(merged.size() - 1);
                if (previous.contentIndex() == selection.contentIndex()
                        && previous.endExclusive() > selection.start()) {
                    long end = Math.max(previous.endExclusive(), selection.endExclusive());
                    merged.set(merged.size() - 1, new DiffSelectionSynchronizer.ByteSelection(
                            previous.contentIndex(), previous.start(), end - previous.start()));
                    continue;
                }
            }
            merged.add(selection);
        }
        selections.clear();
        selections.addAll(merged);
        if (selections.isEmpty()) {
            activeIndex = -1;
        } else if (caret >= 0 && anchorContentIndex >= 0) {
            activeIndex = findContaining(anchorContentIndex, caret);
        }
    }
}
