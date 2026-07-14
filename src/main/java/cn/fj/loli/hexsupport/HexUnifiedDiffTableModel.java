package cn.fj.loli.hexsupport;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/** A row-oriented projection of a byte alignment for an IDEA-style unified diff. */
final class HexUnifiedDiffTableModel extends AbstractTableModel {
    private record Block(int firstRow, int rowCount, int leftStart, int rightStart, int length,
                         HexDiffAlignment.Kind kind, int changeIndex) {
    }

    private final byte[] leftBytes;
    private final byte[] rightBytes;
    private final HexDiffAlignment alignment;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Integer> changeRows = new ArrayList<>();
    private int bytesPerRow;
    private int rowCount;

    HexUnifiedDiffTableModel(byte[] leftBytes, byte[] rightBytes, HexDiffAlignment alignment, int bytesPerRow) {
        this.leftBytes = leftBytes;
        this.rightBytes = rightBytes;
        this.alignment = alignment;
        this.bytesPerRow = bytesPerRow;
        rebuild();
    }

    void setBytesPerRow(int bytesPerRow) {
        this.bytesPerRow = bytesPerRow;
        rebuild();
        fireTableStructureChanged();
    }

    int getBytesPerRow() { return bytesPerRow; }
    int oldOffsetColumn() { return 0; }
    int newOffsetColumn() { return 1; }
    int rawColumn() { return bytesPerRow + 2; }
    boolean isOffsetColumn(int column) { return column == 0 || column == 1; }
    boolean isByteColumn(int column) { return column >= 2 && column < bytesPerRow + 2; }
    List<Integer> changeRows() { return List.copyOf(changeRows); }

    HexDiffAlignment.Kind kindAt(int row) { return blockAt(row).kind(); }
    int changeIndexAt(int row) { return blockAt(row).changeIndex(); }

    int sourceOffsetAt(int row, int column) {
        Block block = blockAt(row);
        return sourceOffsetAt(row, column, block.leftStart() >= 0);
    }

    int sourceOffsetAt(int row, int column, boolean left) {
        if (!isByteColumn(column)) return -1;
        Block block = blockAt(row);
        int inBlock = (row - block.firstRow()) * bytesPerRow + column - 2;
        if (inBlock >= block.length()) return -1;
        int start = left ? block.leftStart() : block.rightStart();
        return start < 0 ? -1 : start + inBlock;
    }

    int sourceOffsetForChange(int changeIndex, boolean left) {
        for (Block block : blocks) {
            if (block.changeIndex() != changeIndex) continue;
            int start = left ? block.leftStart() : block.rightStart();
            if (start >= 0 && block.length() > 0) return start;
        }
        return -1;
    }

    private void rebuild() {
        blocks.clear();
        changeRows.clear();
        rowCount = 0;
        int changeIndex = -1;
        boolean inChange = false;
        for (HexDiffAlignment.Segment segment : alignment.segments()) {
            if (segment.kind() == HexDiffAlignment.Kind.EQUAL) {
                inChange = false;
                addBlock(segment.leftStart(), segment.rightStart(), segment.length(), HexDiffAlignment.Kind.EQUAL, -1);
                continue;
            }
            if (!inChange) {
                changeIndex++;
                changeRows.add(rowCount);
                inChange = true;
            }
            if (segment.kind() == HexDiffAlignment.Kind.MODIFIED) {
                addBlock(segment.leftStart(), -1, segment.length(), HexDiffAlignment.Kind.REMOVED, changeIndex);
                addBlock(-1, segment.rightStart(), segment.length(), HexDiffAlignment.Kind.ADDED, changeIndex);
            } else {
                addBlock(segment.leftStart(), segment.rightStart(), segment.length(), segment.kind(), changeIndex);
            }
        }
        if (blocks.isEmpty()) addBlock(0, 0, 0, HexDiffAlignment.Kind.EQUAL, -1);
    }

    private void addBlock(int leftStart, int rightStart, int length, HexDiffAlignment.Kind kind, int changeIndex) {
        int rows = Math.max(1, (length + bytesPerRow - 1) / bytesPerRow);
        blocks.add(new Block(rowCount, rows, leftStart, rightStart, length, kind, changeIndex));
        rowCount += rows;
    }

    private Block blockAt(int row) {
        int low = 0;
        int high = blocks.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Block block = blocks.get(middle);
            if (row < block.firstRow()) high = middle - 1;
            else if (row >= block.firstRow() + block.rowCount()) low = middle + 1;
            else return block;
        }
        return blocks.get(Math.max(0, blocks.size() - 1));
    }

    @Override public int getRowCount() { return rowCount; }
    @Override public int getColumnCount() { return bytesPerRow + 3; }

    @Override
    public String getColumnName(int column) {
        if (column == 0) return HexEditorBundle.message("diff.old.offset");
        if (column == 1) return HexEditorBundle.message("diff.new.offset");
        if (column == rawColumn()) return HexEditorBundle.message("column.raw");
        return String.format("%02X", column - 2);
    }

    @Override
    public Object getValueAt(int row, int column) {
        Block block = blockAt(row);
        int rowDelta = (row - block.firstRow()) * bytesPerRow;
        if (column == 0) return offsetText(block.leftStart(), rowDelta, block.length());
        if (column == 1) return offsetText(block.rightStart(), rowDelta, block.length());
        if (column == rawColumn()) return rawText(block, rowDelta);
        int sourceOffset = sourceOffsetAt(row, column);
        if (sourceOffset < 0) return "";
        byte[] source = block.leftStart() >= 0 ? leftBytes : rightBytes;
        return String.format("%02X", source[sourceOffset] & 0xFF);
    }

    private static String offsetText(int start, int rowDelta, int length) {
        return start < 0 || rowDelta >= length ? "" : String.format("%016X", (long) start + rowDelta);
    }

    private String rawText(Block block, int rowDelta) {
        int available = Math.min(bytesPerRow, Math.max(0, block.length() - rowDelta));
        if (available == 0) return "";
        byte[] source = block.leftStart() >= 0 ? leftBytes : rightBytes;
        int start = (block.leftStart() >= 0 ? block.leftStart() : block.rightStart()) + rowDelta;
        StringBuilder result = new StringBuilder(available);
        for (int i = 0; i < available; i++) {
            int value = source[start + i] & 0xFF;
            result.append(value >= 0x20 && value <= 0x7E ? (char) value : '.');
        }
        return result.toString();
    }
}
