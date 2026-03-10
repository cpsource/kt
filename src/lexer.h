#ifndef LEXER_H
#define LEXER_H

#include "token.h"
#include "arena.h"

typedef struct {
    const char *src;
    const char *file;
    int pos;
    int line;
    int col;
    Arena *arena;
} Lexer;

Lexer lexer_new(const char *src, const char *file, Arena *arena);
Token lexer_next(Lexer *l);

#endif
