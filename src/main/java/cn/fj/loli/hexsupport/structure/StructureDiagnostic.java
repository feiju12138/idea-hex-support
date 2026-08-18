package cn.fj.loli.hexsupport.structure;

public record StructureDiagnostic(Severity severity, int line, int column, String message) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
