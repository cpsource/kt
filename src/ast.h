#ifndef AST_H
#define AST_H

#include "error.h"
#include "arena.h"

typedef enum {
    /* Top-level */
    NODE_PROGRAM,
    NODE_FN_DEF,
    NODE_STRUCT_DEF,
    NODE_ENUM_DEF,

    /* Statements */
    NODE_BLOCK,
    NODE_EXPR_STMT,
    NODE_RETURN,
    NODE_LET,
    NODE_ASSIGN,
    NODE_IF,
    NODE_WHILE,
    NODE_FOR_RANGE,
    NODE_MATCH,
    NODE_BREAK,
    NODE_CONTINUE,

    /* Expressions */
    NODE_CALL,
    NODE_BINOP,
    NODE_UNARY,
    NODE_MEMBER,
    NODE_INDEX,
    NODE_STRING_LIT,
    NODE_INT_LIT,
    NODE_BOOL_LIT,
    NODE_IDENT,
    NODE_PATH,
    NODE_STRUCT_LIT,
    NODE_ADDR_OF,      /* &expr or &mut expr */

    /* Annotation */
    NODE_ANNOTATION,
} NodeKind;

typedef enum {
    OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD,
    OP_EQ, OP_NEQ, OP_LT, OP_GT, OP_LTEQ, OP_GTEQ,
    OP_AND, OP_OR,
    OP_BIT_AND, OP_BIT_OR, OP_BIT_XOR, OP_SHL, OP_SHR,
} BinOp;

typedef enum {
    UNOP_NEG,       /* - */
    UNOP_NOT,       /* ! */
    UNOP_BIT_NOT,   /* ~ */
    UNOP_DEREF,     /* * */
} UnaryOp;

/* Function parameter */
typedef struct {
    const char *name;
    const char *type_name;  /* raw type string for now */
    int is_mut_ref;         /* &mut T */
    int is_ref;             /* &T */
    int is_ptr;             /* *T */
} Param;

/* Struct field */
typedef struct {
    const char *name;
    const char *type_name;
} Field;

/* Enum variant */
typedef struct {
    const char *name;
    const char **field_types;  /* optional data types */
    int nfields;
} Variant;

typedef struct AstNode AstNode;

/* Match arm */
typedef struct {
    const char *pattern;       /* variant name or literal */
    const char *enum_name;     /* optional Enum:: prefix */
    AstNode *body;
} MatchArm;

struct AstNode {
    NodeKind kind;
    SrcLoc loc;
    union {
        struct { AstNode **decls; int ndecls; } program;

        struct {
            const char *name;
            Param *params; int nparams;
            const char *ret_type;  /* NULL if void */
            AstNode *body;
        } fn_def;

        struct {
            const char *name;
            Field *fields; int nfields;
        } struct_def;

        struct {
            const char *name;
            Variant *variants; int nvariants;
        } enum_def;

        struct { AstNode **stmts; int nstmts; } block;
        struct { AstNode *expr; } expr_stmt;
        struct { AstNode *expr; } ret;

        struct {
            const char *name;
            int is_mut;
            int is_buffer;       /* let x = [N] */
            int buffer_size;
            const char *type_name; /* optional type annotation */
            AstNode *init;       /* init expression (NULL for buffers) */
        } let;

        struct { AstNode *target; AstNode *value; } assign;

        struct { AstNode *cond; AstNode *then_b; AstNode *else_b; } if_; /* else_b may be NULL */

        struct { AstNode *cond; AstNode *body; } while_;

        struct {
            const char *var;   /* loop variable name */
            AstNode *start;    /* range start */
            AstNode *end;      /* range end (exclusive) */
            AstNode *body;
        } for_range;

        struct {
            AstNode *expr;
            MatchArm *arms; int narms;
        } match_;

        struct { const char *name; AstNode **args; int nargs; } call;

        struct { BinOp op; AstNode *left; AstNode *right; } binop;
        struct { UnaryOp op; AstNode *operand; } unary;
        struct { AstNode *object; const char *field; } member;
        struct { AstNode *object; AstNode *index; int is_word; } index_;
        struct { const char *value; } string_lit;
        struct { long value; const char *text; } int_lit;
        struct { int value; } bool_lit;
        struct { const char *name; } ident;
        struct { const char *base; const char *member; } path;  /* Enum::Variant */

        struct {
            const char *name;  /* struct name */
            const char **field_names;
            AstNode **field_values;
            int nfields;
        } struct_lit;

        struct { AstNode *operand; int is_mut; } addr_of;

        struct { const char *name; const char *prompt; AstNode *child; } annotation;
    };
};

AstNode *ast_new(Arena *a, NodeKind kind, SrcLoc loc);

#endif
