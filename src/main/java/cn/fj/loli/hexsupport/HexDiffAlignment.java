package cn.fj.loli.hexsupport;

import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;

import java.util.ArrayList;
import java.util.List;

/** Byte-level alignment shared by both tables in the hex diff viewer. */
final class HexDiffAlignment {
    private static final int MAX_EXACT_DIFF_BYTES = 8 * 1024 * 1024;

    enum Kind {
        EQUAL, MODIFIED, REMOVED, ADDED
    }

    record Segment(long displayStart, int length, int leftStart, int rightStart, Kind kind) {
        long displayEnd() {
            return displayStart + length;
        }

        int sourceOffset(boolean left, long displayOffset) {
            int sourceStart = left ? leftStart : rightStart;
            return sourceStart < 0 ? -1 : sourceStart + (int) (displayOffset - displayStart);
        }
    }

    record ChangeRange(long start, long end) {
    }

    private final List<Segment> segments;
    private final List<ChangeRange> changes;
    private final long displayLength;
    private final boolean exact;

    private HexDiffAlignment(List<Segment> segments, List<ChangeRange> changes, long displayLength, boolean exact) {
        this.segments = List.copyOf(segments);
        this.changes = List.copyOf(changes);
        this.displayLength = displayLength;
        this.exact = exact;
    }

    static HexDiffAlignment build(byte[] left, byte[] right) {
        if (left.length <= MAX_EXACT_DIFF_BYTES && right.length <= MAX_EXACT_DIFF_BYTES) {
            try {
                return buildExact(left, right);
            } catch (FilesTooBigForDiffException ignored) {
                // IntelliJ applies an additional complexity limit. Keep the viewer useful by
                // collapsing the unmatched middle into a single aligned change.
            }
        }
        return buildCoarse(left, right);
    }

    private static HexDiffAlignment buildExact(byte[] left, byte[] right) throws FilesTooBigForDiffException {
        int[] leftValues = unsignedValues(left);
        int[] rightValues = unsignedValues(right);
        Diff.Change change = Diff.buildChanges(leftValues, rightValues);
        Builder builder = new Builder();
        int leftPosition = 0;
        int rightPosition = 0;
        while (change != null) {
            int unchanged = Math.min(change.line0 - leftPosition, change.line1 - rightPosition);
            builder.addEqual(leftPosition, rightPosition, unchanged);
            leftPosition += unchanged;
            rightPosition += unchanged;
            builder.addChange(change.line0, change.line1, change.deleted, change.inserted);
            leftPosition = change.line0 + change.deleted;
            rightPosition = change.line1 + change.inserted;
            change = change.link;
        }
        builder.addEqual(leftPosition, rightPosition,
                Math.min(left.length - leftPosition, right.length - rightPosition));
        return builder.finish(true);
    }

    private static HexDiffAlignment buildCoarse(byte[] left, byte[] right) {
        int prefix = 0;
        int commonLength = Math.min(left.length, right.length);
        while (prefix < commonLength && left[prefix] == right[prefix]) {
            prefix++;
        }
        if (prefix == left.length && prefix == right.length) {
            Builder identical = new Builder();
            identical.addEqual(0, 0, prefix);
            return identical.finish(true);
        }
        int suffix = 0;
        while (suffix < commonLength - prefix
                && left[left.length - suffix - 1] == right[right.length - suffix - 1]) {
            suffix++;
        }

        Builder builder = new Builder();
        builder.addEqual(0, 0, prefix);
        builder.addChange(prefix, prefix, left.length - prefix - suffix, right.length - prefix - suffix);
        builder.addEqual(left.length - suffix, right.length - suffix, suffix);
        return builder.finish(false);
    }

    private static int[] unsignedValues(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            values[i] = bytes[i] & 0xFF;
        }
        return values;
    }

    long displayLength() {
        return displayLength;
    }

    List<ChangeRange> changes() {
        return changes;
    }

    List<Segment> segments() {
        return segments;
    }

    boolean isExact() {
        return exact;
    }

    Segment segmentAt(long displayOffset) {
        int low = 0;
        int high = segments.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Segment segment = segments.get(middle);
            if (displayOffset < segment.displayStart()) {
                high = middle - 1;
            } else if (displayOffset >= segment.displayEnd()) {
                low = middle + 1;
            } else {
                return segment;
            }
        }
        return null;
    }

    private static final class Builder {
        private final List<Segment> segments = new ArrayList<>();
        private final List<ChangeRange> changes = new ArrayList<>();
        private long displayPosition;

        void addEqual(int leftStart, int rightStart, int length) {
            addSegment(length, leftStart, rightStart, Kind.EQUAL);
        }

        void addChange(int leftStart, int rightStart, int deleted, int inserted) {
            int paired = Math.min(deleted, inserted);
            long start = displayPosition;
            addSegment(paired, leftStart, rightStart, Kind.MODIFIED);
            addSegment(deleted - paired, leftStart + paired, -1, Kind.REMOVED);
            addSegment(inserted - paired, -1, rightStart + paired, Kind.ADDED);
            if (displayPosition > start) {
                changes.add(new ChangeRange(start, displayPosition));
            }
        }

        private void addSegment(int length, int leftStart, int rightStart, Kind kind) {
            if (length <= 0) {
                return;
            }
            segments.add(new Segment(displayPosition, length, leftStart, rightStart, kind));
            displayPosition += length;
        }

        HexDiffAlignment finish(boolean exact) {
            return new HexDiffAlignment(segments, changes, displayPosition, exact);
        }
    }
}
