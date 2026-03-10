#include "lexer.h"
#include <string.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>

Lexer lexer_new(const char *src, const char *file, Arena *arena) {
    return (Lexer){ .src = src, .file = file, .pos = 0, .line = 1, .col = 1, .arena = arena };
}

static char peek(Lexer *l) { return l->src[l->pos]; }
static char peek2(Lexer *l) { return l->src[l->pos + 1]; }
static char advance(Lexer *l) {
    char c = l->src[l->pos++];
    if (c == '\n') { l->line++; l->col = 1; }
    else { l->col++; }
    return c;
}

static void skip_whitespace(Lexer *l) {
    for (;;) {
        char c = peek(l);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            advance(l);
        } else if (c == '/' && peek2(l) == '/') {
            while (peek(l) && peek(l) != '\n') advance(l);
        } else if (c == '/' && peek2(l) == '*') {
            advance(l); advance(l);
            while (peek(l) && !(peek(l) == '*' && peek2(l) == '/')) advance(l);
            if (peek(l)) { advance(l); advance(l); }
        } else {
            break;
        }
    }
}

static SrcLoc loc(Lexer *l) {
    return (SrcLoc){ .file = l->file, .line = l->line, .col = l->col };
}

static Token make_tok(TokenKind kind, SrcLoc sl, const char *text, int len) {
    return (Token){ .kind = kind, .text = text, .len = len, .loc = sl };
}

typedef struct { const char *word; int len; TokenKind kind; } Keyword;
static const Keyword keywords[] = {
    {"fn",       2, TOK_FN},
    {"let",      3, TOK_LET},
    {"mut",      3, TOK_MUT},
    {"return",   6, TOK_RETURN},
    {"if",       2, TOK_IF},
    {"else",     4, TOK_ELSE},
    {"while",    5, TOK_WHILE},
    {"for",      3, TOK_FOR},
    {"in",       2, TOK_IN},
    {"match",    5, TOK_MATCH},
    {"break",    5, TOK_BREAK},
    {"continue", 8, TOK_CONTINUE},
    {"struct",   6, TOK_STRUCT},
    {"enum",     4, TOK_ENUM},
    {"impl",     4, TOK_IMPL},
    {"type",     4, TOK_TYPE},
    {"true",     4, TOK_TRUE},
    {"false",    5, TOK_FALSE},
    {NULL, 0, TOK_EOF},
};

Token lexer_next(Lexer *l) {
    skip_whitespace(l);
    SrcLoc sl = loc(l);
    char c = peek(l);

    if (c == '\0') return make_tok(TOK_EOF, sl, "", 0);

    /* Skip semicolons */
    if (c == ';') { advance(l); return lexer_next(l); }

    /* Two-character operators (check before single-char) */
    char c2 = peek2(l);
    if (c == '-' && c2 == '>') { advance(l); advance(l); return make_tok(TOK_ARROW, sl, "->", 2); }
    if (c == '=' && c2 == '>') { advance(l); advance(l); return make_tok(TOK_FAT_ARROW, sl, "=>", 2); }
    if (c == '=' && c2 == '=') { advance(l); advance(l); return make_tok(TOK_EQEQ, sl, "==", 2); }
    if (c == '!' && c2 == '=') { advance(l); advance(l); return make_tok(TOK_NEQ, sl, "!=", 2); }
    if (c == '<' && c2 == '=') { advance(l); advance(l); return make_tok(TOK_LTEQ, sl, "<=", 2); }
    if (c == '>' && c2 == '=') { advance(l); advance(l); return make_tok(TOK_GTEQ, sl, ">=", 2); }
    if (c == '&' && c2 == '&') { advance(l); advance(l); return make_tok(TOK_AND, sl, "&&", 2); }
    if (c == '|' && c2 == '|') { advance(l); advance(l); return make_tok(TOK_OR, sl, "||", 2); }
    if (c == '<' && c2 == '<') { advance(l); advance(l); return make_tok(TOK_SHL, sl, "<<", 2); }
    if (c == '>' && c2 == '>') { advance(l); advance(l); return make_tok(TOK_SHR, sl, ">>", 2); }
    if (c == '.' && c2 == '.') { advance(l); advance(l); return make_tok(TOK_DOTDOT, sl, "..", 2); }
    if (c == ':' && c2 == ':') { advance(l); advance(l); return make_tok(TOK_COLONCOLON, sl, "::", 2); }

    /* Single-character tokens */
    if (c == '(')  { advance(l); return make_tok(TOK_LPAREN, sl, "(", 1); }
    if (c == ')')  { advance(l); return make_tok(TOK_RPAREN, sl, ")", 1); }
    if (c == '{')  { advance(l); return make_tok(TOK_LBRACE, sl, "{", 1); }
    if (c == '}')  { advance(l); return make_tok(TOK_RBRACE, sl, "}", 1); }
    if (c == '[')  { advance(l); return make_tok(TOK_LBRACKET, sl, "[", 1); }
    if (c == ']')  { advance(l); return make_tok(TOK_RBRACKET, sl, "]", 1); }
    if (c == ',')  { advance(l); return make_tok(TOK_COMMA, sl, ",", 1); }
    if (c == ':')  { advance(l); return make_tok(TOK_COLON, sl, ":", 1); }
    if (c == '.')  { advance(l); return make_tok(TOK_DOT, sl, ".", 1); }
    if (c == '@')  { advance(l); return make_tok(TOK_AT, sl, "@", 1); }
    if (c == '=')  { advance(l); return make_tok(TOK_EQ, sl, "=", 1); }
    if (c == '+')  { advance(l); return make_tok(TOK_PLUS, sl, "+", 1); }
    if (c == '-')  { advance(l); return make_tok(TOK_MINUS, sl, "-", 1); }
    if (c == '*')  { advance(l); return make_tok(TOK_STAR, sl, "*", 1); }
    if (c == '/')  { advance(l); return make_tok(TOK_SLASH, sl, "/", 1); }
    if (c == '%')  { advance(l); return make_tok(TOK_PERCENT, sl, "%", 1); }
    if (c == '<')  { advance(l); return make_tok(TOK_LT, sl, "<", 1); }
    if (c == '>')  { advance(l); return make_tok(TOK_GT, sl, ">", 1); }
    if (c == '!')  { advance(l); return make_tok(TOK_NOT, sl, "!", 1); }
    if (c == '&')  { advance(l); return make_tok(TOK_AMP, sl, "&", 1); }
    if (c == '|')  { advance(l); return make_tok(TOK_PIPE, sl, "|", 1); }
    if (c == '^')  { advance(l); return make_tok(TOK_CARET, sl, "^", 1); }
    if (c == '~')  { advance(l); return make_tok(TOK_TILDE, sl, "~", 1); }

    /* String literal */
    if (c == '"') {
        advance(l);
        int save_pos = l->pos, save_line = l->line, save_col = l->col;
        size_t len = 0;
        while (peek(l) && peek(l) != '"') {
            if (peek(l) == '\\') { advance(l); advance(l); }
            else { advance(l); }
            len++;
        }
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
                    case 'r': buf[i++] = '\r'; break;
                    case '0': buf[i++] = '\0'; break;
                    default: buf[i++] = esc; break;
                }
            } else {
                buf[i++] = advance(l);
            }
        }
        buf[i] = '\0';
        if (peek(l) == '"') advance(l);
        return make_tok(TOK_STRING, sl, buf, (int)i);
    }

    /* Integer literal */
    if (isdigit(c)) {
        const char *start = l->src + l->pos;
        int n = 0;
        /* Handle 0x, 0b, 0o prefixes */
        if (c == '0' && (peek2(l) == 'x' || peek2(l) == 'b' || peek2(l) == 'o')) {
            advance(l); advance(l); n = 2;
        }
        while (isalnum(peek(l)) || peek(l) == '_') { advance(l); n++; }
        char *text = arena_strndup(l->arena, start, n);
        return make_tok(TOK_INT, sl, text, n);
    }

    /* Identifier / keyword */
    if (isalpha(c) || c == '_') {
        const char *start = l->src + l->pos;
        int n = 0;
        while (isalnum(peek(l)) || peek(l) == '_') { advance(l); n++; }
        char *text = arena_strndup(l->arena, start, n);
        for (const Keyword *kw = keywords; kw->word; kw++) {
            if (kw->len == n && memcmp(text, kw->word, n) == 0)
                return make_tok(kw->kind, sl, text, n);
        }
        return make_tok(TOK_IDENT, sl, text, n);
    }

    fprintf(stderr, "%s:%d:%d: error: unexpected character '%c' (0x%02x)\n",
            sl.file, sl.line, sl.col, c, (unsigned char)c);
    exit(1);
}
