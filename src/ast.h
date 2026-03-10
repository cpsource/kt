#ifndef AST_H
#define AST_H

#include "error.h"
#include "arena.h"

typedef enum {
    NODE_PROGRAM,
    NODE_FN_DEF,
    NODE_BLOCK,
    NODE_EXPR_STMT,
    NODE_RETURN,
    NODE_LET,
    NODE_CALL,
    NODE_STRING_LIT,
    NODE_INT_LIT,
    NODE_IDENT,
    NODE_ANNOTATION,
} NodeKind;

typedef struct AstNode AstNode;

struct AstNode {
    NodeKind kind;
    SrcLoc loc;
    union {
        struct { AstNode **decls; int ndecls; } program;
        struct { const char *name; AstNode *body; } fn_def;
        struct { AstNode **stmts; int nstmts; } block;
        struct { AstNode *expr; } expr_stmt;
        struct { AstNode *expr; } ret;
        struct { const char *name; int size; } let;
        struct { const char *name; AstNode **args; int nargs; } call;
        struct { const char *value; } string_lit;
        struct { long value; const char *text; } int_lit;
        struct { const char *name; } ident;
        struct { const char *name; const char *prompt; AstNode *child; } annotation;
    };
};

AstNode *ast_new(Arena *a, NodeKind kind, SrcLoc loc);

#endif
