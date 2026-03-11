#include "types.kth"
// error.kt — Compiler diagnostics

fn error_at(loc: SrcLoc, msg: &str) {
    fprintf(stderr, "%s:%ld:%ld: error: %s\n", loc.file, loc.line, loc.col, msg)
    exit(1)
}

fn warn_at(loc: SrcLoc, msg: &str) {
    fprintf(stderr, "%s:%ld:%ld: warning: %s\n", loc.file, loc.line, loc.col, msg)
}
