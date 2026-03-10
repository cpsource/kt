#include "codegen.h"
#include <stdlib.h>
#include <string.h>

/* System V AMD64 ABI argument registers */
static const char *arg_regs[] = { "%rdi", "%rsi", "%rdx", "%rcx", "%r8", "%r9" };
#define MAX_REG_ARGS 6

typedef struct {
    const char *name;
    int rbp_offset;  /* negative offset from %rbp */
} Local;

#define MAX_LOCALS 64

typedef struct {
    FILE *out;
    const char **strings;
    int nstrings;
    int string_cap;
    Local locals[MAX_LOCALS];
    int nlocals;
    int stack_size;  /* total bytes allocated below %rbp for locals */
} CodegenCtx;

static int add_string(CodegenCtx *ctx, const char *s) {
    if (ctx->nstrings >= ctx->string_cap) {
        ctx->string_cap *= 2;
        ctx->strings = realloc(ctx->strings, ctx->string_cap * sizeof(char *));
    }
    int id = ctx->nstrings;
    ctx->strings[ctx->nstrings++] = s;
    return id;
}

/* Find a local variable by name, returns NULL if not found */
static Local *find_local(CodegenCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nlocals; i++) {
        if (strcmp(ctx->locals[i].name, name) == 0)
            return &ctx->locals[i];
    }
    return NULL;
}

/* Forward declarations */
static void emit_expr_to_reg(CodegenCtx *ctx, AstNode *node, const char *reg);
static void emit_call(CodegenCtx *ctx, AstNode *node);

static void emit_expr_to_reg(CodegenCtx *ctx, AstNode *node, const char *reg) {
    switch (node->kind) {
    case NODE_STRING_LIT: {
        int id = add_string(ctx, node->string_lit.value);
        fprintf(ctx->out, "    leaq    .LC%d(%%rip), %s\n", id, reg);
        break;
    }
    case NODE_INT_LIT:
        fprintf(ctx->out, "    movq    $%ld, %s\n", node->int_lit.value, reg);
        break;
    case NODE_IDENT: {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc) {
            fprintf(ctx->out, "    leaq    %d(%%rbp), %s\n", loc->rbp_offset, reg);
        } else {
            fprintf(ctx->out, "    leaq    %s(%%rip), %s\n", node->ident.name, reg);
        }
        break;
    }
    case NODE_CALL:
        emit_call(ctx, node);
        /* Result is in %rax, move to target register if needed */
        if (strcmp(reg, "%rax") != 0) {
            fprintf(ctx->out, "    movq    %%rax, %s\n", reg);
        }
        break;
    default:
        break;
    }
}

/* Emit a call expression with full System V ABI support (stack args for 7+) */
static void emit_call(CodegenCtx *ctx, AstNode *node) {
    int nargs = node->call.nargs;
    int stack_args = nargs > MAX_REG_ARGS ? nargs - MAX_REG_ARGS : 0;

    /* Push stack args right-to-left */
    if (stack_args > 0) {
        /* Ensure 16-byte alignment: stack_args pushes = stack_args * 8 bytes.
         * After pushq %rbp, %rsp is 16-byte aligned.
         * After subq for locals, still aligned (we round up).
         * Each push is 8 bytes, so if stack_args is odd, add 8 bytes padding. */
        if (stack_args % 2 != 0) {
            fprintf(ctx->out, "    subq    $8, %%rsp\n");
        }
        for (int i = nargs - 1; i >= MAX_REG_ARGS; i--) {
            AstNode *arg = node->call.args[i];
            /* Load into %rax, then push */
            emit_expr_to_reg(ctx, arg, "%rax");
            fprintf(ctx->out, "    pushq   %%rax\n");
        }
    }

    /* Load register args */
    for (int i = 0; i < nargs && i < MAX_REG_ARGS; i++) {
        emit_expr_to_reg(ctx, node->call.args[i], arg_regs[i]);
    }

    /* AL = 0 for variadic functions (no SSE args) */
    fprintf(ctx->out, "    xorl    %%eax, %%eax\n");
    fprintf(ctx->out, "    call    %s\n", node->call.name);

    /* Clean up stack args */
    if (stack_args > 0) {
        int cleanup = stack_args * 8;
        if (stack_args % 2 != 0) cleanup += 8; /* padding */
        fprintf(ctx->out, "    addq    $%d, %%rsp\n", cleanup);
    }
}

static void emit_expr(CodegenCtx *ctx, AstNode *node, int arg_index) {
    if (node->kind == NODE_CALL) {
        emit_call(ctx, node);
    } else {
        emit_expr_to_reg(ctx, node, arg_regs[arg_index]);
    }
}

static void emit_fn(CodegenCtx *ctx, AstNode *fn) {
    /* Reset locals for this function */
    ctx->nlocals = 0;
    ctx->stack_size = 0;

    AstNode *body = fn->fn_def.body;

    /* First pass: scan for let statements to compute stack frame size */
    for (int i = 0; i < body->block.nstmts; i++) {
        AstNode *stmt = body->block.stmts[i];
        if (stmt->kind == NODE_LET) {
            int size = stmt->let.size;
            /* Align each allocation to 16 bytes */
            ctx->stack_size += (size + 15) & ~15;
            ctx->locals[ctx->nlocals].name = stmt->let.name;
            ctx->locals[ctx->nlocals].rbp_offset = -ctx->stack_size;
            ctx->nlocals++;
        }
    }

    /* Round total stack size up to 16-byte alignment */
    ctx->stack_size = (ctx->stack_size + 15) & ~15;

    /* Emit prologue */
    fprintf(ctx->out, "    .globl %s\n", fn->fn_def.name);
    fprintf(ctx->out, "    .type %s, @function\n", fn->fn_def.name);
    fprintf(ctx->out, "%s:\n", fn->fn_def.name);
    fprintf(ctx->out, "    pushq   %%rbp\n");
    fprintf(ctx->out, "    movq    %%rsp, %%rbp\n");
    if (ctx->stack_size > 0) {
        fprintf(ctx->out, "    subq    $%d, %%rsp\n", ctx->stack_size);
    }

    /* Second pass: emit code (skip let statements, they're already handled) */
    for (int i = 0; i < body->block.nstmts; i++) {
        AstNode *stmt = body->block.stmts[i];
        if (stmt->kind == NODE_EXPR_STMT) {
            emit_expr(ctx, stmt->expr_stmt.expr, 0);
        } else if (stmt->kind == NODE_RETURN) {
            emit_expr_to_reg(ctx, stmt->ret.expr, "%rax");
            fprintf(ctx->out, "    leave\n");
            fprintf(ctx->out, "    ret\n");
        }
        /* NODE_LET: no runtime code needed, space already reserved */
    }

    /* Epilogue */
    fprintf(ctx->out, "    xorl    %%eax, %%eax\n");
    fprintf(ctx->out, "    leave\n");
    fprintf(ctx->out, "    ret\n");
}

void codegen(AstNode *program, FILE *out) {
    CodegenCtx ctx = {
        .out = out,
        .strings = malloc(16 * sizeof(char *)),
        .nstrings = 0,
        .string_cap = 16,
    };

    fprintf(out, "    .text\n");

    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *decl = program->program.decls[i];
        if (decl->kind == NODE_FN_DEF) {
            emit_fn(&ctx, decl);
        } else if (decl->kind == NODE_ANNOTATION) {
            /* For now, just emit the child fn */
            if (decl->annotation.child && decl->annotation.child->kind == NODE_FN_DEF) {
                emit_fn(&ctx, decl->annotation.child);
            }
        }
    }

    /* Emit string constants */
    if (ctx.nstrings > 0) {
        fprintf(out, "\n    .section .rodata\n");
        for (int i = 0; i < ctx.nstrings; i++) {
            fprintf(out, ".LC%d:\n", i);
            fprintf(out, "    .string \"");
            /* Emit with proper escaping */
            for (const char *p = ctx.strings[i]; *p; p++) {
                switch (*p) {
                case '\n': fprintf(out, "\\n"); break;
                case '\t': fprintf(out, "\\t"); break;
                case '\\': fprintf(out, "\\\\"); break;
                case '"': fprintf(out, "\\\""); break;
                default: fputc(*p, out); break;
                }
            }
            fprintf(out, "\"\n");
        }
    }

    /* Mark stack as non-executable */
    fprintf(out, "\n    .section .note.GNU-stack,\"\",@progbits\n");

    free(ctx.strings);
}
