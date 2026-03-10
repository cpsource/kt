#ifndef ERROR_H
#define ERROR_H

typedef struct {
    const char *file;
    int line;
    int col;
} SrcLoc;

void error_at(SrcLoc loc, const char *fmt, ...)
    __attribute__((format(printf, 2, 3), noreturn));
void warn_at(SrcLoc loc, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

#endif
