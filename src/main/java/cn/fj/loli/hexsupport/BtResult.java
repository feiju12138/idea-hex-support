package cn.fj.loli.hexsupport;

import java.nio.file.Path;
import java.util.List;

record BtResult(
        Path template,
        long documentRevision,
        List<BtNode> nodes,
        List<BtDiagnostic> diagnostics,
        List<String> output
) {
    BtResult {
        nodes = List.copyOf(nodes);
        diagnostics = List.copyOf(diagnostics);
        output = List.copyOf(output);
    }

    boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == BtDiagnostic.Severity.ERROR);
    }
}
