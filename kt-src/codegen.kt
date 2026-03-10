#include "types.kth"
// codegen.kt — Simplified x86-64 GAS assembly emitter (System V AMD64 ABI)
// Handles the subset needed for: fn main() { puts("Hello, world!") }

let MAX_REG_ARGS: i32 = 6

fn get_arg_reg(i: i32) -> &str {
    if i == 0 { return "%rdi" }
    if i == 1 { return "%rsi" }
    if i == 2 { return "%rdx" }
    if i == 3 { return "%rcx" }
    if i == 4 { return "%r8" }
    return "%r9"
}

fn add_string(ctx: &mut CodegenCtx, s: &str) -> i32 {
    if ctx.nstrings >= ctx.string_cap {
        ctx.string_cap = ctx.string_cap * 2
        ctx.strings = realloc(ctx.strings, ctx.string_cap * 8)
    }
    let id: i32 = ctx.nstrings
    ctx.strings[[ctx.nstrings]] = s
    ctx.nstrings = ctx.nstrings + 1
    return id
}

fn find_local_offset(ctx: &CodegenCtx, name: &str) -> i32 {
    let mut i: i32 = ctx.nlocals - 1
    while i >= 0 {
        if streq(ctx.local_names[[i]], name) {
            return ctx.local_offsets[[i]]
        }
        i = i - 1
    }
    return 0
}

fn has_local(ctx: &CodegenCtx, name: &str) -> i32 {
    let mut i: i32 = ctx.nlocals - 1
    while i >= 0 {
        if streq(ctx.local_names[[i]], name) {
            return 1
        }
        i = i - 1
    }
    return 0
}

fn emit_expr_to_reg(ctx: &mut CodegenCtx, node: *AstNode, reg: &str) {
    if node.kind == NodeKind::STRING_LIT {
        let id: i32 = add_string(ctx, node.d0)
        fprintf(ctx.out, "    leaq    .LC%d(%%rip), %s\n", id, reg)
    } else if node.kind == NodeKind::INT_LIT {
        fprintf(ctx.out, "    movq    $%ld, %s\n", node.d0, reg)
    } else if node.kind == NodeKind::IDENT {
        if has_local(ctx, node.d0) != 0 {
            let off = find_local_offset(ctx, node.d0)
            fprintf(ctx.out, "    leaq    %d(%%rbp), %s\n", off, reg)
        } else {
            fprintf(ctx.out, "    leaq    %s(%%rip), %s\n", node.d0, reg)
        }
    } else if node.kind == NodeKind::CALL {
        emit_call(ctx, node)
        if !streq(reg, "%rax") {
            fprintf(ctx.out, "    movq    %%rax, %s\n", reg)
        }
    }
}

fn emit_call(ctx: &mut CodegenCtx, node: *AstNode) {
    let nargs: i32 = node.d2
    let name: &str = node.d0
    let args: *AstNode = node.d1

    // Evaluate each arg directly to its target register.
    // This works when args are simple literals/idents that
    // do not clobber other argument registers.
    let mut i: i32 = 0
    while i < nargs && i < MAX_REG_ARGS {
        emit_expr_to_reg(ctx, args[[i]], get_arg_reg(i))
        i = i + 1
    }

    // AL = 0 for variadic functions (no SSE args)
    fprintf(ctx.out, "    xorl    %%eax, %%eax\n")
    fprintf(ctx.out, "    call    %s\n", name)
}

fn emit_fn(ctx: &mut CodegenCtx, f: *AstNode) {
    // Reset locals for this function
    ctx.nlocals = 0
    ctx.stack_size = 0

    let name: &str = f.d0
    let body: *AstNode = f.d1
    let stmts: *AstNode = body.d0
    let nstmts: i32 = body.d1

    // First pass: scan for LET stmts to allocate stack space
    let mut i: i32 = 0
    while i < nstmts {
        let stmt: *AstNode = stmts[[i]]
        if stmt.kind == NodeKind::LET {
            let size: i32 = stmt.d1
            // Align size to 16 bytes
            ctx.stack_size = ctx.stack_size + ((size + 15) & ~15)
            ctx.local_names[[ctx.nlocals]] = stmt.d0
            ctx.local_offsets[[ctx.nlocals]] = 0 - ctx.stack_size
            ctx.nlocals = ctx.nlocals + 1
        }
        i = i + 1
    }

    // Round total stack to 16-byte alignment
    ctx.stack_size = (ctx.stack_size + 15) & ~15

    // Prologue
    fprintf(ctx.out, "    .globl %s\n", name)
    fprintf(ctx.out, "    .type %s, @function\n", name)
    fprintf(ctx.out, "%s:\n", name)
    fprintf(ctx.out, "    pushq   %%rbp\n")
    fprintf(ctx.out, "    movq    %%rsp, %%rbp\n")
    if ctx.stack_size > 0 {
        fprintf(ctx.out, "    subq    $%d, %%rsp\n", ctx.stack_size)
    }

    // Second pass: emit statements
    i = 0
    while i < nstmts {
        let stmt: *AstNode = stmts[[i]]
        if stmt.kind == NodeKind::EXPR_STMT {
            let expr: *AstNode = stmt.d0
            if expr.kind == NodeKind::CALL {
                emit_call(ctx, expr)
            } else {
                emit_expr_to_reg(ctx, expr, "%rax")
            }
        } else if stmt.kind == NodeKind::RETURN {
            if stmt.d0 != 0 {
                emit_expr_to_reg(ctx, stmt.d0, "%rax")
            } else {
                fprintf(ctx.out, "    xorl    %%eax, %%eax\n")
            }
            fprintf(ctx.out, "    leave\n")
            fprintf(ctx.out, "    ret\n")
        }
        i = i + 1
    }

    // Epilogue: implicit return 0
    fprintf(ctx.out, "    xorl    %%eax, %%eax\n")
    fprintf(ctx.out, "    leave\n")
    fprintf(ctx.out, "    ret\n")
}

fn codegen(program: *AstNode, out: *File) {
    let ctx: *CodegenCtx = malloc(64)
    ctx.out = out
    ctx.strings = malloc(128)
    ctx.nstrings = 0
    ctx.string_cap = 16
    ctx.local_names = malloc(512)
    ctx.local_offsets = malloc(512)
    ctx.nlocals = 0
    ctx.stack_size = 0

    fprintf(out, "    .text\n")

    // Emit functions from top-level declarations
    let decls: *AstNode = program.d0
    let ndecls: i32 = program.d1
    let mut i: i32 = 0
    while i < ndecls {
        let decl: *AstNode = decls[[i]]
        if decl.kind == NodeKind::FN_DEF {
            emit_fn(ctx, decl)
        } else if decl.kind == NodeKind::ANNOTATION {
            // annotation.d2 = child node
            if decl.d2 != 0 {
                let child: *AstNode = decl.d2
                if child.kind == NodeKind::FN_DEF {
                    emit_fn(ctx, child)
                }
            }
        }
        i = i + 1
    }

    // Emit string constants in .rodata
    if ctx.nstrings > 0 {
        fprintf(out, "\n    .section .rodata\n")
        let mut j: i32 = 0
        while j < ctx.nstrings {
            fprintf(out, ".LC%d:\n", j)
            fprintf(out, "    .string \"%s\"\n", ctx.strings[[j]])
            j = j + 1
        }
    }

    // Mark stack as non-executable
    fprintf(out, "\n    .section .note.GNU-stack,\"\",@progbits\n")
    free(ctx.strings)
    free(ctx.local_names)
    free(ctx.local_offsets)
    free(ctx)
}
