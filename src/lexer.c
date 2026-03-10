#include "lexer.h"
#include <string.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>

Lexer lexer_new(const char *src, const char *file, Arena *arena) {
    return (Lexer){ .src = src, .file = file, .pos = 0, .line = 1, .col = 1, .arena = arena };
}

static char peek(Lexer *l) { return l->src[l->pos]; }
static char advance(Lexer *l) {
    char c = l->src[l->pos++];
    if (c == '\n') { l->line++; l->col = 1; }
    else { l->col++; }
    return c;
}

static void skip_whitespace(Lexer *l) {
    while (peek(l) && (peek(l) == ' ' || peek(l) == '\t' || peek(l) == '\n' || peek(l) == '\r'))
        advance(l);
}

static SrcLoc loc(Lexer *l) {
    return (SrcLoc){ .file = l->file, .line = l->line, .col = l->col };
}

static Token make_tok(TokenKind kind, SrcLoc sl, const char *text, int len) {
    return (Token){ .kind = kind, .text = text, .len = len, .loc = sl };
}

Token lexer_next(Lexer *l) {
    skip_whitespace(l);
    SrcLoc sl = loc(l);
    char c = peek(l);

    if (c == '\0') return make_tok(TOK_EOF, sl, "", 0);

    /* Skip semicolons (optional statement terminators) */
    if (c == ';') { advance(l); return lexer_next(l); }

    /* Skip line comments */
    if (c == '/' && l->src[l->pos + 1] == '/') {
        while (peek(l) && peek(l) != '\n') advance(l);
        return lexer_next(l);
    }

    /* Single-character tokens */
    if (c == '(') { advance(l); return make_tok(TOK_LPAREN, sl, "(", 1); }
    if (c == ')') { advance(l); return make_tok(TOK_RPAREN, sl, ")", 1); }
    if (c == '{') { advance(l); return make_tok(TOK_LBRACE, sl, "{", 1); }
    if (c == '}') { advance(l); return make_tok(TOK_RBRACE, sl, "}", 1); }
    if (c == ',') { advance(l); return make_tok(TOK_COMMA, sl, ",", 1); }
    if (c == ':') { advance(l); return make_tok(TOK_COLON, sl, ":", 1); }
    if (c == '=') { advance(l); return make_tok(TOK_EQ, sl, "=", 1); }
    if (c == '[') { advance(l); return make_tok(TOK_LBRACKET, sl, "[", 1); }
    if (c == ']') { advance(l); return make_tok(TOK_RBRACKET, sl, "]", 1); }
    if (c == '@') { advance(l); return make_tok(TOK_AT, sl, "@", 1); }

    /* String literal */
    if (c == '"') {
        advance(l); /* skip opening quote */
        /* First pass: compute length */
        int save_pos = l->pos, save_line = l->line, save_col = l->col;
        size_t len = 0;
        while (peek(l) && peek(l) != '"') {
            if (peek(l) == '\\') { advance(l); advance(l); }
            else { advance(l); }
            len++;
        }
        /* Restore and do second pass to build string */
        l->pos = save_pos; l->line = save_line; l->col = save_col;
        char *buf = arena_alloc(l->arena, len + 1, 1);
        size_t i = 0;
        while (peek(l) && peek(l) != '"') {
            if (peek(l) == '\\') {
                advance(l);
                char esc = advance(l);
                switch (esc) {
                    case 'n': buf[i++] = '\n'; break;
                    case '\\': buf[i++] = '\\'; break;
                    case '"': buf[i++] = '"'; break;
                    case 't': buf[i++] = '\t'; break;
                    default: buf[i++] = esc; break;
                }
            } else {
                buf[i++] = advance(l);
            }
        }
        buf[i] = '\0';
        if (peek(l) == '"') advance(l); /* skip closing quote */
        return make_tok(TOK_STRING, sl, buf, (int)i);
    }

    /* Integer literal */
    if (isdigit(c)) {
        const char *start = l->src + l->pos;
        int n = 0;
        while (isdigit(peek(l))) { advance(l); n++; }
        char *text = arena_strndup(l->arena, start, n);
        return make_tok(TOK_INT, sl, text, n);
    }

    /* Identifier / keyword */
    if (isalpha(c) || c == '_') {
        const char *start = l->src + l->pos;
        int n = 0;
        while (isalnum(peek(l)) || peek(l) == '_') { advance(l); n++; }
        char *text = arena_strndup(l->arena, start, n);
        if (n == 2 && memcmp(text, "fn", 2) == 0)
            return make_tok(TOK_FN, sl, text, n);
        if (n == 3 && memcmp(text, "let", 3) == 0)
            return make_tok(TOK_LET, sl, text, n);
        if (n == 6 && memcmp(text, "return", 6) == 0)
            return make_tok(TOK_RETURN, sl, text, n);
        return make_tok(TOK_IDENT, sl, text, n);
    }

    fprintf(stderr, "%s:%d:%d: error: unexpected character '%c'\n", sl.file, sl.line, sl.col, c);
    exit(1);
}
