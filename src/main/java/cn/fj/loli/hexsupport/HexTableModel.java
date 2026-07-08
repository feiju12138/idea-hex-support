package cn.fj.loli.hexsupport;

import javax.swing.table.AbstractTableModel;
import java.nio.charset.Charset;
import java.util.Arrays;

final class HexTableModel extends AbstractTableModel {
    private static final int OFFSET_COLUMN = 0;
    private byte[] data;
    private int bytesPerRow;
    private Charset charset;
    private Runnable byteChangeListener = () -> {
    };

    HexTableModel(byte[] data, int bytesPerRow, Charset charset) {
        this.data = data;
        this.bytesPerRow = bytesPerRow;
        this.charset = charset;
    }

    void setBytesPerRow(int bytesPerRow) {
        this.bytesPerRow = bytesPerRow;
        fireTableStructureChanged();
    }

    void setCharset(Charset charset) {
        this.charset = charset;
        fireTableDataChanged();
    }

    void setByteChangeListener(Runnable byteChangeListener) {
        this.byteChangeListener = byteChangeListener == null ? () -> {
        } : byteChangeListener;
    }

    byte[] copyData() {
        return Arrays.copyOf(data, data.length);
    }

    int getBytesPerRow() {
        return bytesPerRow;
    }

    void replaceData(byte[] data) {
        this.data = data;
        fireTableDataChanged();
    }

    void setByteAt(int index, int value) {
        ensureLength(index + 1);
        data[index] = (byte) value;
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void setBytesAt(int startIndex, byte[] values, boolean overwriteSelectionLength, int selectionLength) {
        if (values.length == 0 || startIndex < 0) {
            return;
        }
        int length = overwriteSelectionLength ? Math.min(values.length, selectionLength) : values.length;
        ensureLength(startIndex + length);
        System.arraycopy(values, 0, data, startIndex, length);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void fillRange(int startIndex, int length, int value) {
        if (startIndex < 0 || length <= 0) {
            return;
        }
        ensureLength(startIndex + length);
        Arrays.fill(data, startIndex, startIndex + length, (byte) value);
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void deleteRangeOrZero(int startIndex, int length) {
        if (startIndex < 0 || length <= 0 || data.length == 0) {
            return;
        }
        int endExclusive = Math.min(data.length, startIndex + length);
        if (endExclusive >= data.length) {
            data = Arrays.copyOf(data, startIndex);
        } else {
            Arrays.fill(data, startIndex, endExclusive, (byte) 0);
        }
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void deleteRange(int startIndex, int length) {
        if (startIndex < 0 || length <= 0 || startIndex >= data.length) {
            return;
        }
        int endExclusive = Math.min(data.length, startIndex + length);
        byte[] updated = new byte[data.length - (endExclusive - startIndex)];
        System.arraycopy(data, 0, updated, 0, startIndex);
        System.arraycopy(data, endExclusive, updated, startIndex, data.length - endExclusive);
        data = updated;
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void insertZeros(int index, int count) {
        if (count <= 0) {
            return;
        }
        int insertion = Math.max(0, Math.min(index, data.length));
        byte[] updated = new byte[data.length + count];
        System.arraycopy(data, 0, updated, 0, insertion);
        System.arraycopy(data, insertion, updated, insertion + count, data.length - insertion);
        data = updated;
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void insertBytes(int index, byte[] values) {
        if (values.length == 0) {
            return;
        }
        int insertion = Math.max(0, Math.min(index, data.length));
        byte[] updated = new byte[data.length + values.length];
        System.arraycopy(data, 0, updated, 0, insertion);
        System.arraycopy(values, 0, updated, insertion, values.length);
        System.arraycopy(data, insertion, updated, insertion + values.length, data.length - insertion);
        data = updated;
        byteChangeListener.run();
        fireTableDataChanged();
    }

    void ensureLength(int length) {
        if (length > data.length) {
            data = Arrays.copyOf(data, length);
        }
    }

    int getDataLength() {
        return data.length;
    }

    int unsignedAt(int index) {
        if (index < 0 || index >= data.length) {
            return 0;
        }
        return data[index] & 0xFF;
    }

    int byteIndexAt(int row, int column) {
        if (column <= OFFSET_COLUMN || column > bytesPerRow) {
            return -1;
        }
        int index = row * bytesPerRow + (column - 1);
        return index < data.length ? index : -1;
    }

    int prospectiveByteIndexAt(int row, int column) {
        if (column <= OFFSET_COLUMN || column > bytesPerRow || row < 0) {
            return -1;
        }
        return row * bytesPerRow + (column - 1);
    }

    int rowForOffset(int offset) {
        return offset / bytesPerRow;
    }

    int columnForOffset(int offset) {
        return offset % bytesPerRow + 1;
    }

    int find(byte[] pattern, int fromOffset) {
        if (pattern.length == 0 || pattern.length > data.length) {
            return -1;
        }
        int start = Math.max(0, fromOffset);
        for (int i = start; i <= data.length - pattern.length; i++) {
            boolean matched = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    NumberInfo numberInfoAt(int offset) {
        if (offset < 0 || offset >= data.length) {
            return NumberInfo.empty();
        }
        int b0 = unsigned(offset);
        Integer u16le = has(offset, 2) ? b0 | (unsigned(offset + 1) << 8) : null;
        Integer u16be = has(offset, 2) ? (b0 << 8) | unsigned(offset + 1) : null;
        Long u32le = has(offset, 4) ? ((long) b0) | ((long) unsigned(offset + 1) << 8) | ((long) unsigned(offset + 2) << 16) | ((long) unsigned(offset + 3) << 24) : null;
        Long u32be = has(offset, 4) ? ((long) b0 << 24) | ((long) unsigned(offset + 1) << 16) | ((long) unsigned(offset + 2) << 8) | (long) unsigned(offset + 3) : null;
        Float f32le = has(offset, 4) ? Float.intBitsToFloat(u32le.intValue()) : null;
        Float f32be = has(offset, 4) ? Float.intBitsToFloat(u32be.intValue()) : null;
        return new NumberInfo(offset, b0, (byte) b0, u16le, u16be, u32le, u32be, f32le, f32be);
    }

    private boolean has(int offset, int byteCount) {
        return offset >= 0 && offset + byteCount <= data.length;
    }

    private int unsigned(int offset) {
        return data[offset] & 0xFF;
    }

    @Override
    public int getRowCount() {
        if (data.length == 0) {
            return 1;
        }
        return (data.length + bytesPerRow - 1) / bytesPerRow;
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
            return String.format("%08X", rowIndex * bytesPerRow);
        }
        if (columnIndex == bytesPerRow + 1) {
            return rawText(rowIndex);
        }
        int index = byteIndexAt(rowIndex, columnIndex);
        return index >= 0 ? String.format("%02X", data[index] & 0xFF) : "";
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        int index = byteIndexAt(rowIndex, columnIndex);
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
        data[index] = (byte) parsed;
        byteChangeListener.run();
        fireTableRowsUpdated(rowIndex, rowIndex);
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
        int start = rowIndex * bytesPerRow;
        if (start >= data.length) {
            return "";
        }
        int length = Math.min(bytesPerRow, data.length - start);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int value = data[start + i] & 0xFF;
            char ch = (char) value;
            result.append(value < 0x20 || value > 0x7E || Character.isISOControl(ch) ? '.' : ch);
        }
        return result.toString();
    }

    record NumberInfo(int offset, int uint8, byte int8, Integer uint16Le, Integer uint16Be, Long uint32Le,
                      Long uint32Be, Float float32Le, Float float32Be) {
        static NumberInfo empty() {
            return new NumberInfo(-1, 0, (byte) 0, null, null, null, null, null, null);
        }
    }
}
