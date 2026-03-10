#include "check.h"
#include <string.h>

#define MAX_LOCALS 64

static const char *locals[MAX_LOCALS];
static int nlocals;

static void collect_locals(AstNode *block) {
    if (!block || block->kind != NODE_BLOCK) return;
    for (int i = 0; i < block->block.nstmts; i++) {
        AstNode *stmt = block->block.stmts[i];
        if (stmt->kind == NODE_LET && stmt->let.is_buffer) {
            if (nlocals < MAX_LOCALS)
                locals[nlocals++] = stmt->let.name;
        }
    }
}

static int is_local(const char *name) {
    for (int i = 0; i < nlocals; i++) {
        if (strcmp(locals[i], name) == 0)
            return 1;
    }
    return 0;
}

static void check_return_expr(AstNode *expr, SrcLoc ret_loc) {
    if (!expr) return;
    if (expr->kind == NODE_IDENT && is_local(expr->ident.name)) {
        error_at(ret_loc,
            "returning pointer to stack-allocated '%s' — "
            "memory will be invalid after function returns",
            expr->ident.name);
    }
}

static void check_block(AstNode *block) {
    if (!block || block->kind != NODE_BLOCK) return;
    for (int i = 0; i < block->block.nstmts; i++) {
        AstNode *stmt = block->block.stmts[i];
        if (stmt->kind == NODE_RETURN) {
            check_return_expr(stmt->ret.expr, stmt->loc);
        }
    }
}

static void check_fn(AstNode *fn) {
    AstNode *body = fn->fn_def.body;
    if (!body || body->kind != NODE_BLOCK) return;

    nlocals = 0;
    collect_locals(body);
    if (nlocals == 0) return;

    check_block(body);
}

void check_escape(AstNode *program) {
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *decl = program->program.decls[i];
        if (decl->kind == NODE_FN_DEF) {
            check_fn(decl);
        } else if (decl->kind == NODE_ANNOTATION &&
                   decl->annotation.child &&
                   decl->annotation.child->kind == NODE_FN_DEF) {
            check_fn(decl->annotation.child);
        }
    }
}
