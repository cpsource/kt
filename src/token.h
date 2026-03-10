#ifndef TOKEN_H
#define TOKEN_H

#include "error.h"

typedef enum {
    /* Keywords */
    TOK_FN, TOK_LET, TOK_MUT, TOK_RETURN,
    TOK_IF, TOK_ELSE, TOK_WHILE, TOK_FOR, TOK_IN,
    TOK_MATCH, TOK_BREAK, TOK_CONTINUE,
    TOK_STRUCT, TOK_ENUM, TOK_IMPL, TOK_TYPE,
    TOK_TRUE, TOK_FALSE,

    /* Literals / identifiers */
    TOK_IDENT, TOK_STRING, TOK_INT,

    /* Delimiters */
    TOK_LPAREN, TOK_RPAREN,
    TOK_LBRACE, TOK_RBRACE,
    TOK_LBRACKET, TOK_RBRACKET,

    /* Punctuation */
    TOK_COMMA, TOK_COLON, TOK_DOT, TOK_AT,
    TOK_ARROW,       /* -> */
    TOK_FAT_ARROW,   /* => */
    TOK_DOTDOT,      /* .. */
    TOK_COLONCOLON,  /* :: */

    /* Assignment */
    TOK_EQ,          /* = */

    /* Arithmetic operators */
    TOK_PLUS, TOK_MINUS, TOK_STAR, TOK_SLASH, TOK_PERCENT,

    /* Comparison operators */
    TOK_EQEQ, TOK_NEQ,   /* == != */
    TOK_LT, TOK_GT,      /* < > */
    TOK_LTEQ, TOK_GTEQ,  /* <= >= */

    /* Logical operators */
    TOK_AND, TOK_OR, TOK_NOT,  /* && || ! */

    /* Bitwise operators */
    TOK_AMP, TOK_PIPE, TOK_CARET, TOK_TILDE,  /* & | ^ ~ */
    TOK_SHL, TOK_SHR,                          /* << >> */

    TOK_EOF,
} TokenKind;

typedef struct {
    TokenKind kind;
    const char *text;
    int len;
    SrcLoc loc;
} Token;

#endif
