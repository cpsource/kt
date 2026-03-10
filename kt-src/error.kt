#include "types.kth"
// error.kt — Compiler diagnostics in file:line:col format

fn error_at(loc: *SrcLoc, msg: &str) {
    fprintf(stderr, "%s:%d:%d: error: %s\n", loc.file, loc.line, loc.col, msg)
    exit(1)
}

fn warn_at(loc: *SrcLoc, msg: &str) {
    fprintf(stderr, "%s:%d:%d: warning: %s\n", loc.file, loc.line, loc.col, msg)
}
