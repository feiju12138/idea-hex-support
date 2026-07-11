package cn.fj.loli.hexsupport;

import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

final class HexTableModel extends AbstractTableModel {
    private static final int OFFSET_COLUMN = 0;
    private static final long MAX_SCROLLABLE_ROWS = 90_000_000L;
    private static final int BYTES_PER_ROW_STEP = 4;

    private final HexDocument document;
    private int bytesPerRow;
    private Runnable byteChangeListener = () -> {
    };

    HexTableModel(HexDocument document, int bytesPerRow) {
        this.document = document;
        this.bytesPerRow = Math.max(bytesPerRow, minimumSupportedBytesPerRow());
    }

    void setBytesPerRow(int bytesPerRow) {
        this.bytesPerRow = Math.max(bytesPerRow, minimumSupportedBytesPerRow());
        fireTableStructureChanged();
    }

    int minimumSupportedBytesPerRow() {
        long dataLength = getDataLength();
        if (dataLength <= 0) {
            return 4;
        }
        long minimum = (dataLength + MAX_SCROLLABLE_ROWS - 1L) / MAX_SCROLLABLE_ROWS;
        long rounded = Math.max(4, ((minimum + BYTES_PER_ROW_STEP - 1L) / BYTES_PER_ROW_STEP) * BYTES_PER_ROW_STEP);
        return rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }

    void setByteChangeListener(Runnable byteChangeListener) {
        this.byteChangeListener = byteChangeListener == null ? () -> {
        } : byteChangeListener;
    }

    HexDocument.State snapshot() {
        return document.snapshot();
    }

    long revision() {
        return document.revision();
    }

    boolean isLargeMode() {
        return document.isLargeMode();
    }

    void restore(HexDocument.State state) {
        document.restore(state);
        fireTableDataChanged();
    }

    void dataChanged() {
        fireTableDataChanged();
    }

    int getBytesPerRow() {
        return bytesPerRow;
    }

    void reload() {
        document.reload();
        fireTableDataChanged();
    }

    void saveTo(Path target, HexDocument.ProgressReporter progressReporter) throws IOException {
        document.saveTo(target, progressReporter);
    }

    void close() throws IOException {
        document.close();
    }

    byte[] read(long startIndex, int length) {
        return document.read(startIndex, length);
    }

    void writeRangeTo(long startIndex, long length, OutputStream output, HexDocument.ProgressReporter progressReporter) throws IOException {
        document.writeRangeTo(startIndex, length, output, progressReporter);
    }

    void setByteAt(long index, int value) {
        document.overwrite(index, new byte[]{(byte) value});
        byteChangeListener.run();
        fireTableRowsUpdated(rowForOffset(index), rowForOffset(index));
    }

    void setBytesAt(long startIndex, byte[] values, boolean overwriteSelectionLength, long selectionLength) {
        if (values.length == 0 || startIndex < 0) {
            return;
        }
        int length = overwriteSelectionLength ? (int) Math.min(values.length, selectionLength) : values.length;
        if (length <= 0) {
            return;
        }
        byte[] actual = values.length == length ? values : java.util.Arrays.copyOf(values, length);
        document.overwrite(startIndex, actual);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void fillRange(long startIndex, long length, int value) {
        if (startIndex < 0 || length <= 0) {
            return;
        }
        document.fill(startIndex, length, value);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void deleteRange(long startIndex, long length) {
        if (startIndex < 0 || length <= 0 || startIndex >= getDataLength()) {
            return;
        }
        document.delete(startIndex, length);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void insertZeros(long index, long count) {
        if (count <= 0) {
            return;
        }
        document.insertFill(index, count, 0);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void insertBytes(long index, byte[] values) {
        if (values.length == 0) {
            return;
        }
        document.insert(index, values);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void insertFile(long index, Path path, HexDocument.ProgressReporter progressReporter) {
        document.insertFile(index, path, progressReporter);
    }

    long getDataLength() {
        return document.length();
    }

    int unsignedAt(long index) {
        return document.unsignedAt(index);
    }

    long byteIndexAt(int row, int column) {
        if (column <= OFFSET_COLUMN || column > bytesPerRow || row < 0) {
            return -1;
        }
        long index = (long) row * bytesPerRow + (column - 1L);
        return index < getDataLength() ? index : -1;
    }

    int rowForOffset(long offset) {
        long row = offset / bytesPerRow;
        return row > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) row;
    }

    int columnForOffset(long offset) {
        return (int) (offset % bytesPerRow) + 1;
    }

    HexDocument.FindResult findAll(byte[] pattern, long fromOffset, int limit, java.util.function.BooleanSupplier shouldContinue,
                                   HexDocument.ProgressReporter progressReporter) {
        return document.findAll(pattern, fromOffset, limit, shouldContinue, progressReporter);
    }

    java.util.List<HexDocument.OperationRecord> operationRecords() {
        return document.operationRecords();
    }

    void applyHistoryRecords(java.util.List<HexDocument.OperationRecord> records) {
        applyHistoryRecords(records, null);
    }

    void applyHistoryRecords(java.util.List<HexDocument.OperationRecord> records, Consumer<HexDocument.State> beforeRecordApplied) {
        if (records.isEmpty()) {
            return;
        }
        for (HexDocument.OperationRecord record : records) {
            if (beforeRecordApplied != null) {
                beforeRecordApplied.accept(document.snapshot());
            }
            document.applyHistoryRecord(record);
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        long dataLength = getDataLength();
        if (dataLength == 0) {
            return 1;
        }
        long rows = (dataLength + bytesPerRow - 1L) / bytesPerRow;
        return rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rows;
    }

    @Override
    public int getColumnCount() {
        return bytesPerRow + 2;
    }

    @Override
    public String getColumnName(int column) {
        if (column == OFFSET_COLUMN) {
            return HexEditorBundle.message("column.offset");
        }
        if (column == bytesPerRow + 1) {
            return HexEditorBundle.message("column.raw");
        }
        return String.format("%02X", column - 1);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return byteIndexAt(rowIndex, columnIndex) >= 0;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (columnIndex == OFFSET_COLUMN) {
            return String.format("%016X", (long) rowIndex * bytesPerRow);
        }
        if (columnIndex == bytesPerRow + 1) {
            return rawText(rowIndex);
        }
        long index = byteIndexAt(rowIndex, columnIndex);
        return index >= 0 ? String.format("%02X", unsignedAt(index)) : "";
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        long index = byteIndexAt(rowIndex, columnIndex);
        if (index < 0 || value == null) {
            return;
        }
        String text = normalizeHexByte(value.toString());
        if (text.isEmpty()) {
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(text, 16);
        } catch (NumberFormatException ignored) {
            return;
        }
        setByteAt(index, parsed);
    }

    static String normalizeHexByte(String value) {
        StringBuilder hex = new StringBuilder(2);
        for (int i = 0; i < value.length() && hex.length() < 2; i++) {
            char ch = value.charAt(i);
            if (Character.digit(ch, 16) >= 0) {
                hex.append(Character.toLowerCase(ch));
            }
        }
        if (hex.length() == 1) {
            hex.insert(0, '0');
        }
        return hex.toString();
    }

    static byte[] parseHexBytes(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return new byte[0];
        }
        StringBuilder hex = new StringBuilder();
        String[] tokens = trimmed.split("\\s+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int tokenStart = hex.length();
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (Character.digit(ch, 16) >= 0) {
                    hex.append(ch);
                }
            }
            int tokenLen = hex.length() - tokenStart;
            if (tokenLen == 0) {
                continue;
            }
            if (tokenLen % 2 != 0) {
                hex.insert(tokenStart, '0');
            }
        }
        if (hex.length() == 0) {
            return new byte[0];
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    String rawText(int rowIndex) {
        long start = (long) rowIndex * bytesPerRow;
        if (start >= getDataLength()) {
            return "";
        }
        byte[] row = read(start, bytesPerRow);
        StringBuilder result = new StringBuilder(row.length);
        for (byte b : row) {
            int value = b & 0xFF;
            char ch = (char) value;
            result.append(value < 0x20 || value > 0x7E || Character.isISOControl(ch) ? '.' : ch);
        }
        return result.toString();
    }
}
