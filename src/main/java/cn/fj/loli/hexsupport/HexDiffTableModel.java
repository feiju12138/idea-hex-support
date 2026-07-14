package cn.fj.loli.hexsupport;

import javax.swing.table.AbstractTableModel;

final class HexDiffTableModel extends AbstractTableModel {
    private final byte[] bytes;
    private final HexDiffAlignment alignment;
    private final boolean left;
    private int bytesPerRow;

    HexDiffTableModel(byte[] bytes, HexDiffAlignment alignment, boolean left, int bytesPerRow) {
        this.bytes = bytes;
        this.alignment = alignment;
        this.left = left;
        this.bytesPerRow = bytesPerRow;
    }

    void setBytesPerRow(int bytesPerRow) {
        this.bytesPerRow = bytesPerRow;
        fireTableStructureChanged();
    }

    int getBytesPerRow() {
        return bytesPerRow;
    }

    int offsetColumn() {
        return left ? bytesPerRow + 1 : 0;
    }

    int rawColumn() {
        return left ? bytesPerRow : bytesPerRow + 1;
    }

    boolean isOffsetColumn(int column) {
        return column == offsetColumn();
    }

    boolean isByteColumn(int column) {
        return left ? column >= 0 && column < bytesPerRow : column > 0 && column <= bytesPerRow;
    }

    private int byteIndex(int column) {
        return left ? column : column - 1;
    }

    HexDiffAlignment.Kind kindAt(int row, int column) {
        long displayOffset = displayOffset(row, column);
        HexDiffAlignment.Segment segment = alignment.segmentAt(displayOffset);
        return segment == null ? HexDiffAlignment.Kind.EQUAL : segment.kind();
    }

    boolean isGapAt(int row, int column) {
        long displayOffset = displayOffset(row, column);
        HexDiffAlignment.Segment segment = alignment.segmentAt(displayOffset);
        return segment != null && segment.sourceOffset(left, displayOffset) < 0;
    }

    int sourceOffsetAt(int row, int column) {
        return sourceOffset(displayOffset(row, column));
    }

    long displayOffsetAt(int row, int column) {
        return displayOffset(row, column);
    }

    private long displayOffset(int row, int column) {
        return (long) row * bytesPerRow + byteIndex(column);
    }

    @Override
    public int getRowCount() {
        if (alignment.displayLength() == 0) {
            return 1;
        }
        long rows = (alignment.displayLength() + bytesPerRow - 1L) / bytesPerRow;
        return rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rows;
    }

    @Override
    public int getColumnCount() {
        return bytesPerRow + 2;
    }

    @Override
    public String getColumnName(int column) {
        if (isOffsetColumn(column)) {
            return HexEditorBundle.message("column.offset");
        }
        if (column == rawColumn()) {
            return HexEditorBundle.message("column.raw");
        }
        return String.format("%02X", byteIndex(column));
    }

    @Override
    public Object getValueAt(int row, int column) {
        if (isOffsetColumn(column)) {
            return offsetText(row);
        }
        if (column == rawColumn()) {
            return rawText(row);
        }
        long displayOffset = displayOffset(row, column);
        int sourceOffset = sourceOffset(displayOffset);
        return sourceOffset < 0 ? "" : String.format("%02X", bytes[sourceOffset] & 0xFF);
    }

    private String offsetText(int row) {
        long start = (long) row * bytesPerRow;
        long end = Math.min(start + bytesPerRow, alignment.displayLength());
        for (long displayOffset = start; displayOffset < end; displayOffset++) {
            int sourceOffset = sourceOffset(displayOffset);
            if (sourceOffset >= 0) {
                return String.format("%016X", (long) sourceOffset);
            }
        }
        return "";
    }

    private String rawText(int row) {
        StringBuilder result = new StringBuilder(bytesPerRow);
        long start = (long) row * bytesPerRow;
        for (int i = 0; i < bytesPerRow && start + i < alignment.displayLength(); i++) {
            int sourceOffset = sourceOffset(start + i);
            if (sourceOffset < 0) {
                result.append(' ');
                continue;
            }
            int value = bytes[sourceOffset] & 0xFF;
            result.append(value >= 0x20 && value <= 0x7E ? (char) value : '.');
        }
        return result.toString();
    }

    private int sourceOffset(long displayOffset) {
        HexDiffAlignment.Segment segment = alignment.segmentAt(displayOffset);
        return segment == null ? -1 : segment.sourceOffset(left, displayOffset);
    }
}
