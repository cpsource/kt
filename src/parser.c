#include "parser.h"
#include <stdlib.h>
#include <string.h>

typedef struct {
    Lexer *lexer;
    Arena *arena;
    Token cur;
} Parser;

static void next(Parser *p) { p->cur = lexer_next(p->lexer); }

static Token expect(Parser *p, TokenKind kind, const char *what) {
    if (p->cur.kind != kind)
        error_at(p->cur.loc, "expected %s, got '%.*s'", what, p->cur.len, p->cur.text);
    Token t = p->cur;
    next(p);
    return t;
}

static AstNode *parse_expr(Parser *p);

static AstNode *parse_expr(Parser *p) {
    if (p->cur.kind == TOK_STRING) {
        AstNode *n = ast_new(p->arena, NODE_STRING_LIT, p->cur.loc);
        n->string_lit.value = arena_strndup(p->arena, p->cur.text, p->cur.len);
        next(p);
        return n;
    }
    if (p->cur.kind == TOK_INT) {
        AstNode *n = ast_new(p->arena, NODE_INT_LIT, p->cur.loc);
        n->int_lit.text = arena_strndup(p->arena, p->cur.text, p->cur.len);
        n->int_lit.value = strtol(n->int_lit.text, NULL, 10);
        next(p);
        return n;
    }
    if (p->cur.kind == TOK_IDENT) {
        Token name = p->cur;
        next(p);
        if (p->cur.kind == TOK_LPAREN) {
            /* Call expression */
            next(p); /* skip ( */
            AstNode *n = ast_new(p->arena, NODE_CALL, name.loc);
            n->call.name = arena_strndup(p->arena, name.text, name.len);
            /* Parse args */
            int cap = 4, nargs = 0;
            AstNode **args = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));
            while (p->cur.kind != TOK_RPAREN) {
                if (nargs > 0) expect(p, TOK_COMMA, "','");
                if (nargs >= cap) {
                    int newcap = cap * 2;
                    AstNode **newargs = arena_alloc(p->arena, newcap * sizeof(AstNode *), _Alignof(AstNode *));
                    memcpy(newargs, args, nargs * sizeof(AstNode *));
                    args = newargs;
                    cap = newcap;
                }
                args[nargs++] = parse_expr(p);
            }
            expect(p, TOK_RPAREN, "')'");
            n->call.args = args;
            n->call.nargs = nargs;
            return n;
        }
        /* Plain identifier */
        AstNode *n = ast_new(p->arena, NODE_IDENT, name.loc);
        n->ident.name = arena_strndup(p->arena, name.text, name.len);
        return n;
    }
    error_at(p->cur.loc, "unexpected token '%.*s'", p->cur.len, p->cur.text);
}

static AstNode *parse_block(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_LBRACE, "'{'");
    int cap = 8, nstmts = 0;
    AstNode **stmts = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));
    while (p->cur.kind != TOK_RBRACE) {
        AstNode *stmt;
        if (p->cur.kind == TOK_RETURN) {
            SrcLoc ret_loc = p->cur.loc;
            next(p); /* consume 'return' */
            AstNode *expr = parse_expr(p);
            stmt = ast_new(p->arena, NODE_RETURN, ret_loc);
            stmt->ret.expr = expr;
        } else if (p->cur.kind == TOK_LET) {
            /* let name = [size] */
            SrcLoc let_loc = p->cur.loc;
            next(p); /* consume 'let' */
            Token name = expect(p, TOK_IDENT, "variable name");
            expect(p, TOK_EQ, "'='");
            expect(p, TOK_LBRACKET, "'['");
            Token size_tok = expect(p, TOK_INT, "buffer size");
            expect(p, TOK_RBRACKET, "']'");
            stmt = ast_new(p->arena, NODE_LET, let_loc);
            stmt->let.name = arena_strndup(p->arena, name.text, name.len);
            stmt->let.size = (int)strtol(size_tok.text, NULL, 10);
        } else {
            AstNode *expr = parse_expr(p);
            stmt = ast_new(p->arena, NODE_EXPR_STMT, expr->loc);
            stmt->expr_stmt.expr = expr;
        }
        if (nstmts >= cap) {
            int newcap = cap * 2;
            AstNode **ns = arena_alloc(p->arena, newcap * sizeof(AstNode *), _Alignof(AstNode *));
            memcpy(ns, stmts, nstmts * sizeof(AstNode *));
            stmts = ns;
            cap = newcap;
        }
        stmts[nstmts++] = stmt;
    }
    expect(p, TOK_RBRACE, "'}'");
    AstNode *block = ast_new(p->arena, NODE_BLOCK, sl);
    block->block.stmts = stmts;
    block->block.nstmts = nstmts;
    return block;
}

static AstNode *parse_annotation(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_AT, "'@'");
    Token name = expect(p, TOK_IDENT, "annotation name");
    expect(p, TOK_LPAREN, "'('");
    Token prompt = expect(p, TOK_STRING, "annotation string");
    expect(p, TOK_RPAREN, "')'");
    AstNode *ann = ast_new(p->arena, NODE_ANNOTATION, sl);
    ann->annotation.name = arena_strndup(p->arena, name.text, name.len);
    ann->annotation.prompt = arena_strndup(p->arena, prompt.text, prompt.len);
    return ann;
}

static AstNode *parse_fn_def(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_FN, "'fn'");
    Token name = expect(p, TOK_IDENT, "function name");
    expect(p, TOK_LPAREN, "'('");
    expect(p, TOK_RPAREN, "')'");
    AstNode *body = parse_block(p);
    AstNode *fn = ast_new(p->arena, NODE_FN_DEF, sl);
    fn->fn_def.name = arena_strndup(p->arena, name.text, name.len);
    fn->fn_def.body = body;
    return fn;
}

AstNode *parse(Lexer *l, Arena *a) {
    Parser p = { .lexer = l, .arena = a };
    next(&p);

    int cap = 8, ndecls = 0;
    AstNode **decls = arena_alloc(a, cap * sizeof(AstNode *), _Alignof(AstNode *));

    while (p.cur.kind != TOK_EOF) {
        AstNode *ann = NULL;
        if (p.cur.kind == TOK_AT) {
            ann = parse_annotation(&p);
        }
        AstNode *fn = parse_fn_def(&p);
        if (ann) {
            ann->annotation.child = fn;
            if (ndecls >= cap) {
                int newcap = cap * 2;
                AstNode **nd = arena_alloc(a, newcap * sizeof(AstNode *), _Alignof(AstNode *));
                memcpy(nd, decls, ndecls * sizeof(AstNode *));
                decls = nd;
                cap = newcap;
            }
            decls[ndecls++] = ann;
        } else {
            if (ndecls >= cap) {
                int newcap = cap * 2;
                AstNode **nd = arena_alloc(a, newcap * sizeof(AstNode *), _Alignof(AstNode *));
                memcpy(nd, decls, ndecls * sizeof(AstNode *));
                decls = nd;
                cap = newcap;
            }
            decls[ndecls++] = fn;
        }
    }

    AstNode *prog = ast_new(a, NODE_PROGRAM, (SrcLoc){ l->file, 1, 1 });
    prog->program.decls = decls;
    prog->program.ndecls = ndecls;
    return prog;
}
