package cn.fj.loli.hexsupport.structure;

import java.nio.file.Path;
import java.util.List;

public record StructureAnalysisResult(
        Path template,
        long documentRevision,
        List<StructureNode> nodes,
        List<StructureDiagnostic> diagnostics,
        List<String> output
) {
    public StructureAnalysisResult {
        nodes = List.copyOf(nodes);
        diagnostics = List.copyOf(diagnostics);
        output = List.copyOf(output);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() == StructureDiagnostic.Severity.ERROR);
    }
}
