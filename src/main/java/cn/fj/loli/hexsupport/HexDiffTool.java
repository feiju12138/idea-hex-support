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

/** Adds a selectable, two-sided hexadecimal viewer to the IntelliJ diff window. */
public final class HexDiffTool implements FrameDiffTool {
    @Override
    public @NotNull String getName() {
        return HexEditorBundle.message("diff.viewer.name");
    }

    @Override
    public boolean canShow(@NotNull DiffContext context, @NotNull DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contentRequest)) {
            return false;
        }
        List<DiffContent> contents = contentRequest.getContents();
        return contents.size() == 2 && contents.stream().allMatch(HexDiffTool::isSupportedContent);
    }

    @Override
    public @NotNull DiffViewer createComponent(@NotNull DiffContext context, @NotNull DiffRequest request) {
        return new HexDiffViewer(context, (ContentDiffRequest) request);
    }

    private static boolean isSupportedContent(DiffContent content) {
        return content instanceof FileContent || content instanceof DocumentContent || content instanceof EmptyContent;
    }
}
