package cn.fj.loli.hexsupport;

import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Adds a single-pane, unified hexadecimal viewer to the IntelliJ diff window. */
public final class HexUnifiedDiffTool implements FrameDiffTool {
    @Override public @NotNull String getName() { return HexEditorBundle.message("diff.unified.viewer.name"); }

    @Override
    public boolean canShow(@NotNull DiffContext context, @NotNull DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contentRequest)) return false;
        List<DiffContent> contents = contentRequest.getContents();
        return contents.size() == 2 && contents.stream().allMatch(content ->
                content instanceof FileContent || content instanceof DocumentContent || content instanceof EmptyContent);
    }

    @Override
    public @NotNull DiffViewer createComponent(@NotNull DiffContext context, @NotNull DiffRequest request) {
        return new HexUnifiedDiffViewer(context, (ContentDiffRequest) request);
    }
}
