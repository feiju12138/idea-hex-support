package cn.fj.loli.hexsupport;

import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Keeps Hex available as an alternate editor for files which the user has not
 * explicitly associated with the Hex file type.
 */
public final class HexSecondaryFileEditorProvider extends HexFileEditorProvider {
    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return isSupportedLocalFile(file) && !isHexFile(file);
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR;
    }
}
