package cn.fj.loli.hexsupport;

record BtDiagnostic(Severity severity, int line, int column, String message) {
    enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
