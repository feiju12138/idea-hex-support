package cn.fj.loli.hexsupport;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Assignable binary file type used to make the Hex editor the default editor
 * for user-selected filename patterns in Settings | Editor | File Types.
 */
public final class HexFileType implements FileType {
    public static final HexFileType INSTANCE = new HexFileType();
    public static final String NAME = "HEX";

    private HexFileType() {
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        return HexEditorBundle.message("fileType.displayName");
    }

    @Override
    public @NotNull String getDescription() {
        return HexEditorBundle.message("fileType.description");
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.BinaryData;
    }

    @Override
    public boolean isBinary() {
        return true;
    }
}
