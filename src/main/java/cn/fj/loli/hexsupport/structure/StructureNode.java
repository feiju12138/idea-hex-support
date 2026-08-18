package cn.fj.loli.hexsupport.structure;

import java.util.List;

public record StructureNode(
        String name,
        String type,
        String value,
        long offset,
        long size,
        String format,
        String foregroundColor,
        String backgroundColor,
        String comment,
        List<StructureNode> children
) {
    public StructureNode {
        children = List.copyOf(children);
    }

    public boolean contains(long byteOffset) {
        return size > 0 && byteOffset >= offset && byteOffset - offset < size;
    }
}
