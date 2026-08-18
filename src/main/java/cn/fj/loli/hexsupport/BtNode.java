package cn.fj.loli.hexsupport;

import java.util.List;

record BtNode(
        String name,
        String type,
        String value,
        long offset,
        long size,
        String format,
        String foregroundColor,
        String backgroundColor,
        String comment,
        List<BtNode> children
) {
    BtNode {
        children = List.copyOf(children);
    }

    boolean contains(long byteOffset) {
        return size > 0 && byteOffset >= offset && byteOffset - offset < size;
    }
}
