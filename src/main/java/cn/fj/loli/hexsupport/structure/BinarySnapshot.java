package cn.fj.loli.hexsupport.structure;

/**
 * Read-only view of the current binary document, including unsaved edits.
 * Implementations must return a consistent revision number while an analysis is running.
 */
public interface BinarySnapshot {
    long length();

    long revision();

    byte[] read(long offset, int length);

    default int readUnsignedByte(long offset) {
        byte[] value = read(offset, 1);
        return value.length == 0 ? -1 : value[0] & 0xff;
    }
}
