#ifndef TOKEN_H
#define TOKEN_H

#include "error.h"

typedef enum {
    TOK_FN,
    TOK_LET,
    TOK_RETURN,
    TOK_IDENT,
    TOK_STRING,
    TOK_INT,
    TOK_LPAREN,
    TOK_RPAREN,
    TOK_LBRACE,
    TOK_RBRACE,
    TOK_LBRACKET,
    TOK_RBRACKET,
    TOK_COMMA,
    TOK_COLON,
    TOK_EQ,
    TOK_AT,
    TOK_EOF,
} TokenKind;

typedef struct {
    TokenKind kind;
    const char *text;
    int len;
    SrcLoc loc;
} Token;

#endif
