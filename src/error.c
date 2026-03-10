#include "error.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

void error_at(SrcLoc loc, const char *fmt, ...) {
    fprintf(stderr, "%s:%d:%d: error: ", loc.file, loc.line, loc.col);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fprintf(stderr, "\n");
    exit(1);
}

void warn_at(SrcLoc loc, const char *fmt, ...) {
    fprintf(stderr, "%s:%d:%d: warning: ", loc.file, loc.line, loc.col);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fprintf(stderr, "\n");
}
