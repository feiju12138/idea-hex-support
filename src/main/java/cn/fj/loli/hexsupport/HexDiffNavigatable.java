package cn.fj.loli.hexsupport;

import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import org.jetbrains.annotations.Nullable;

/** Opens a diff source with this plugin's editor instead of IDEA's default text editor. */
final class HexDiffNavigatable implements Navigatable {
    private final Project project;
    private final VirtualFile file;
    private final long offset;

    private HexDiffNavigatable(Project project, VirtualFile file, long offset) {
        this.project = project;
        this.file = file;
        this.offset = offset;
    }

    static @Nullable Navigatable forContent(@Nullable Project project, DiffContent content, long offset) {
        if (project == null) return null;

        VirtualFile file = content instanceof FileContent fileContent ? fileContent.getFile()
                : content instanceof DocumentContent documentContent ? documentContent.getHighlightFile() : null;
        if (file == null) {
            Navigatable source = content.getNavigatable();
            if (source instanceof com.intellij.openapi.fileEditor.OpenFileDescriptor descriptor) {
                file = descriptor.getFile();
            }
        }
        return file != null && file.isValid() && !file.isDirectory() && file.isInLocalFileSystem()
                ? new HexDiffNavigatable(project, file, offset) : null;
    }

    @Override
    public void navigate(boolean requestFocus) {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        manager.openFile(file, requestFocus);
        manager.setSelectedEditor(file, HexFileEditorProvider.EDITOR_TYPE_ID);
        revealOffset(manager);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!canNavigate()) return;
            manager.setSelectedEditor(file, HexFileEditorProvider.EDITOR_TYPE_ID);
            revealOffset(manager);
        }, ModalityState.any());
    }

    private void revealOffset(FileEditorManager manager) {
        if (offset < 0) return;
        for (FileEditor editor : manager.getEditors(file)) {
            if (editor instanceof HexFileEditor hexEditor) {
                hexEditor.navigateToOffset(offset);
                return;
            }
        }
    }

    @Override
    public boolean canNavigate() {
        return !project.isDisposed() && file.isValid();
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }
}
