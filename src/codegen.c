#include "codegen.h"
#include <stdlib.h>
#include <string.h>

/* System V AMD64 ABI argument registers */
static const char *arg_regs[] = { "%rdi", "%rsi", "%rdx", "%rcx", "%r8", "%r9" };
#define MAX_REG_ARGS 6
#define MAX_LOCALS 128
#define MAX_STRUCTS 64
#define MAX_ENUMS 64
#define MAX_GLOBALS 128

typedef struct {
    const char *name;
    int is_array;   /* 1 = array (use leaq), 0 = scalar (use movq) */
} GlobalVar;

typedef struct {
    const char *name;
    int rbp_offset;
    int is_buffer;   /* 1 = stack buffer, 0 = 8-byte value slot */
    const char *type_name;  /* struct type name, if known */
} Local;

typedef struct {
    const char *name;
    const char **fields;
    const char **field_types;  /* type name for each field */
    int nfields;
} StructInfo;

typedef struct {
    const char *name;
    const char **variants;
    int nvariants;
} EnumInfo;

typedef struct {
    FILE *out;
    const char **strings;
    int nstrings;
    int string_cap;
    Local locals[MAX_LOCALS];
    int nlocals;
    int stack_size;
    int label_count;
    int loop_break_label;     /* label for current loop's break target */
    int loop_continue_label;  /* label for current loop's continue target */
    StructInfo structs[MAX_STRUCTS];
    int nstructs;
    EnumInfo enums[MAX_ENUMS];
    int nenums;
    GlobalVar globals[MAX_GLOBALS];
    int nglobals;
} CodegenCtx;

static int new_label(CodegenCtx *ctx) { return ctx->label_count++; }

static int add_string(CodegenCtx *ctx, const char *s) {
    if (ctx->nstrings >= ctx->string_cap) {
        ctx->string_cap *= 2;
        ctx->strings = realloc(ctx->strings, ctx->string_cap * sizeof(char *));
    }
    int id = ctx->nstrings;
    ctx->strings[ctx->nstrings++] = s;
    return id;
}

static Local *find_local(CodegenCtx *ctx, const char *name) {
    for (int i = ctx->nlocals - 1; i >= 0; i--) {
        if (strcmp(ctx->locals[i].name, name) == 0)
            return &ctx->locals[i];
    }
    return NULL;
}

static GlobalVar *find_global(CodegenCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nglobals; i++) {
        if (strcmp(ctx->globals[i].name, name) == 0)
            return &ctx->globals[i];
    }
    return NULL;
}

static void register_global(CodegenCtx *ctx, const char *name, int is_array) {
    if (ctx->nglobals >= MAX_GLOBALS) return;
    ctx->globals[ctx->nglobals].name = name;
    ctx->globals[ctx->nglobals].is_array = is_array;
    ctx->nglobals++;
}

static int struct_field_offset(StructInfo *s, const char *field) {
    for (int i = 0; i < s->nfields; i++) {
        if (strcmp(s->fields[i], field) == 0)
            return i * 8;
    }
    return -1;
}

static StructInfo *find_struct(CodegenCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nstructs; i++) {
        if (strcmp(ctx->structs[i].name, name) == 0)
            return &ctx->structs[i];
    }
    return NULL;
}

/* Extract base type name from type strings like "&Lexer", "&mut Arena", "*AstNode" */
static const char *extract_struct_name(const char *type_str) {
    if (!type_str) return NULL;
    /* Skip leading &, *, &mut */
    const char *p = type_str;
    if (*p == '&' || *p == '*') p++;
    if (strncmp(p, "mut ", 4) == 0) p += 4;
    /* Skip whitespace */
    while (*p == ' ') p++;
    /* What remains should be the struct name (if it starts uppercase, it's likely a struct) */
    if (*p >= 'A' && *p <= 'Z') return p;
    return NULL;
}

/* Given a struct and field name, return the field's type name (or NULL) */
static const char *struct_field_type(StructInfo *s, const char *field) {
    for (int i = 0; i < s->nfields; i++) {
        if (strcmp(s->fields[i], field) == 0)
            return s->field_types[i];
    }
    return NULL;
}

/* Infer the struct type name of an expression (returns extracted base name or NULL) */
static const char *infer_expr_type(CodegenCtx *ctx, AstNode *node) {
    if (node->kind == NODE_IDENT) {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc && loc->type_name)
            return extract_struct_name(loc->type_name);
        return NULL;
    }
    if (node->kind == NODE_MEMBER) {
        /* Determine the type of the object, then look up the field's type */
        const char *obj_type = infer_expr_type(ctx, node->member.object);
        if (obj_type) {
            StructInfo *s = find_struct(ctx, obj_type);
            if (s) {
                const char *ftype = struct_field_type(s, node->member.field);
                if (ftype) return extract_struct_name(ftype);
            }
        }
        return NULL;
    }
    return NULL;
}

/* Resolve field offset with type awareness */
static int resolve_member_offset(CodegenCtx *ctx, AstNode *object, const char *field) {
    /* Try to determine the struct type from the object */
    const char *obj_type = infer_expr_type(ctx, object);
    if (obj_type) {
        StructInfo *s = find_struct(ctx, obj_type);
        if (s) {
            int o = struct_field_offset(s, field);
            if (o >= 0) return o;
        }
    }

    /* Fallback: search all structs for the field, but prefer unique matches */
    int offset = -1;
    int match_count = 0;
    for (int i = 0; i < ctx->nstructs; i++) {
        int o = struct_field_offset(&ctx->structs[i], field);
        if (o >= 0) {
            if (match_count == 0) offset = o;
            match_count++;
        }
    }
    if (match_count > 0) return offset;
    return -1;
}

static EnumInfo *find_enum(CodegenCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nenums; i++) {
        if (strcmp(ctx->enums[i].name, name) == 0)
            return &ctx->enums[i];
    }
    return NULL;
}

static int enum_variant_value(CodegenCtx *ctx, const char *enum_name, const char *variant) {
    EnumInfo *e = find_enum(ctx, enum_name);
    if (!e) return -1;
    for (int i = 0; i < e->nvariants; i++) {
        if (strcmp(e->variants[i], variant) == 0) return i;
    }
    return -1;
}

/* Find which enum a variant belongs to (for unqualified match arms) */
static int find_variant_value(CodegenCtx *ctx, const char *variant, const char **out_enum) {
    for (int i = 0; i < ctx->nenums; i++) {
        for (int j = 0; j < ctx->enums[i].nvariants; j++) {
            if (strcmp(ctx->enums[i].variants[j], variant) == 0) {
                if (out_enum) *out_enum = ctx->enums[i].name;
                return j;
            }
        }
    }
    return -1;
}

/* Forward declarations */
static void emit_expr(CodegenCtx *ctx, AstNode *node);
static void emit_stmt(CodegenCtx *ctx, AstNode *stmt);

/* Emit expression — result always in %rax */
static void emit_expr(CodegenCtx *ctx, AstNode *node) {
    FILE *out = ctx->out;

    switch (node->kind) {
    case NODE_INT_LIT:
        fprintf(out, "    movq    $%ld, %%rax\n", node->int_lit.value);
        break;

    case NODE_BOOL_LIT:
        fprintf(out, "    movq    $%d, %%rax\n", node->bool_lit.value);
        break;

    case NODE_STRING_LIT: {
        int id = add_string(ctx, node->string_lit.value);
        fprintf(out, "    leaq    .LC%d(%%rip), %%rax\n", id);
        break;
    }

    case NODE_IDENT: {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc) {
            if (loc->is_buffer) {
                fprintf(out, "    leaq    %d(%%rbp), %%rax\n", loc->rbp_offset);
            } else {
                fprintf(out, "    movq    %d(%%rbp), %%rax\n", loc->rbp_offset);
            }
        } else {
            /* External symbol or global */
            GlobalVar *gv = find_global(ctx, node->ident.name);
            if (gv && gv->is_array) {
                fprintf(out, "    leaq    %s(%%rip), %%rax\n", node->ident.name);
            } else {
                /* Scalar global: load value */
                fprintf(out, "    movq    %s(%%rip), %%rax\n", node->ident.name);
            }
        }
        break;
    }

    case NODE_PATH: {
        /* Enum::Variant → integer tag */
        int val = enum_variant_value(ctx, node->path.base, node->path.member);
        if (val >= 0) {
            fprintf(out, "    movq    $%d, %%rax\n", val);
        } else {
            /* Unresolved enum: use qualified symbol Base__Member */
            fprintf(out, "    movq    %s__%s(%%rip), %%rax\n",
                    node->path.base, node->path.member);
        }
        break;
    }

    case NODE_BINOP: {
        /* Evaluate right first, save, then left into %rax */
        emit_expr(ctx, node->binop.right);
        fprintf(out, "    subq    $16, %%rsp\n");
        fprintf(out, "    movq    %%rax, (%%rsp)\n");
        emit_expr(ctx, node->binop.left);
        fprintf(out, "    movq    (%%rsp), %%rcx\n");
        fprintf(out, "    addq    $16, %%rsp\n");
        /* %rax = left, %rcx = right */
        switch (node->binop.op) {
        case OP_ADD:
            fprintf(out, "    addq    %%rcx, %%rax\n"); break;
        case OP_SUB:
            fprintf(out, "    subq    %%rcx, %%rax\n"); break;
        case OP_MUL:
            fprintf(out, "    imulq   %%rcx, %%rax\n"); break;
        case OP_DIV:
            fprintf(out, "    cqto\n");
            fprintf(out, "    idivq   %%rcx\n"); break;
        case OP_MOD:
            fprintf(out, "    cqto\n");
            fprintf(out, "    idivq   %%rcx\n");
            fprintf(out, "    movq    %%rdx, %%rax\n"); break;
        case OP_EQ:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    sete    %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_NEQ:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    setne   %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_LT:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    setl    %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_GT:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    setg    %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_LTEQ:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    setle   %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_GTEQ:
            fprintf(out, "    cmpq    %%rcx, %%rax\n");
            fprintf(out, "    setge   %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case OP_AND: {
            int lf = new_label(ctx), le = new_label(ctx);
            /* left is in %rax, right in %rcx */
            fprintf(out, "    testq   %%rax, %%rax\n");
            fprintf(out, "    je      .L%d\n", lf);
            fprintf(out, "    testq   %%rcx, %%rcx\n");
            fprintf(out, "    je      .L%d\n", lf);
            fprintf(out, "    movq    $1, %%rax\n");
            fprintf(out, "    jmp     .L%d\n", le);
            fprintf(out, ".L%d:\n", lf);
            fprintf(out, "    xorq    %%rax, %%rax\n");
            fprintf(out, ".L%d:\n", le);
            break;
        }
        case OP_OR: {
            int lt = new_label(ctx), le = new_label(ctx);
            fprintf(out, "    testq   %%rax, %%rax\n");
            fprintf(out, "    jne     .L%d\n", lt);
            fprintf(out, "    testq   %%rcx, %%rcx\n");
            fprintf(out, "    jne     .L%d\n", lt);
            fprintf(out, "    xorq    %%rax, %%rax\n");
            fprintf(out, "    jmp     .L%d\n", le);
            fprintf(out, ".L%d:\n", lt);
            fprintf(out, "    movq    $1, %%rax\n");
            fprintf(out, ".L%d:\n", le);
            break;
        }
        case OP_BIT_AND:
            fprintf(out, "    andq    %%rcx, %%rax\n"); break;
        case OP_BIT_OR:
            fprintf(out, "    orq     %%rcx, %%rax\n"); break;
        case OP_BIT_XOR:
            fprintf(out, "    xorq    %%rcx, %%rax\n"); break;
        case OP_SHL:
            fprintf(out, "    shlq    %%cl, %%rax\n"); break;
        case OP_SHR:
            fprintf(out, "    sarq    %%cl, %%rax\n"); break;
        }
        break;
    }

    case NODE_UNARY:
        emit_expr(ctx, node->unary.operand);
        switch (node->unary.op) {
        case UNOP_NEG:
            fprintf(out, "    negq    %%rax\n"); break;
        case UNOP_NOT:
            fprintf(out, "    testq   %%rax, %%rax\n");
            fprintf(out, "    sete    %%al\n");
            fprintf(out, "    movzbq  %%al, %%rax\n"); break;
        case UNOP_BIT_NOT:
            fprintf(out, "    notq    %%rax\n"); break;
        case UNOP_DEREF:
            fprintf(out, "    movq    (%%rax), %%rax\n"); break;
        }
        break;

    case NODE_ADDR_OF:
        /* For local variables, compute address */
        if (node->addr_of.operand->kind == NODE_IDENT) {
            Local *loc = find_local(ctx, node->addr_of.operand->ident.name);
            if (loc) {
                fprintf(out, "    leaq    %d(%%rbp), %%rax\n", loc->rbp_offset);
                break;
            }
        }
        /* Fallthrough: evaluate operand (might already be a pointer) */
        emit_expr(ctx, node->addr_of.operand);
        break;

    case NODE_MEMBER: {
        /* Evaluate object (pointer to struct), then access field */
        emit_expr(ctx, node->member.object);
        int offset = resolve_member_offset(ctx, node->member.object, node->member.field);
        if (offset >= 0) {
            fprintf(out, "    movq    %d(%%rax), %%rax\n", offset);
        } else {
            /* Unknown field — might be offset 0 or an error, just load */
            fprintf(out, "    movq    (%%rax), %%rax\n");
        }
        break;
    }

    case NODE_INDEX:
        emit_expr(ctx, node->index_.index);
        fprintf(out, "    subq    $16, %%rsp\n");
        fprintf(out, "    movq    %%rax, (%%rsp)\n");
        emit_expr(ctx, node->index_.object);
        fprintf(out, "    movq    (%%rsp), %%rcx\n");
        fprintf(out, "    addq    $16, %%rsp\n");
        if (node->index_.is_word) {
            /* [[i]] word access: 8-byte elements (pointer/value arrays) */
            fprintf(out, "    movq    (%%rax,%%rcx,8), %%rax\n");
        } else {
            /* [i] byte access: string/buffer indexing */
            fprintf(out, "    movzbq  (%%rax,%%rcx), %%rax\n");
        }
        break;

    case NODE_CALL: {
        int nargs = node->call.nargs;
        int stack_args = nargs > MAX_REG_ARGS ? nargs - MAX_REG_ARGS : 0;

        /* Pre-allocate 16-byte aligned space for all arg temporaries + stack args */
        int total_slots = nargs + stack_args + (stack_args % 2 != 0 ? 1 : 0);
        int frame_size = ((total_slots * 8) + 15) & ~15;
        if (frame_size > 0)
            fprintf(out, "    subq    $%d, %%rsp\n", frame_size);

        /* Evaluate each arg into temp slot at offset [0..nargs*8) */
        for (int i = 0; i < nargs; i++) {
            emit_expr(ctx, node->call.args[i]);
            fprintf(out, "    movq    %%rax, %d(%%rsp)\n", i * 8);
        }

        /* Copy stack args to their call positions (after temp area) */
        if (stack_args > 0) {
            int call_area = nargs * 8;
            if (stack_args % 2 != 0) call_area += 8; /* alignment pad */
            for (int i = MAX_REG_ARGS; i < nargs; i++) {
                int src_off = i * 8;
                int dst_off = call_area + (i - MAX_REG_ARGS) * 8;
                fprintf(out, "    movq    %d(%%rsp), %%r10\n", src_off);
                fprintf(out, "    movq    %%r10, %d(%%rsp)\n", dst_off);
            }
        }

        /* Load register args from temp slots */
        int reg_args = nargs < MAX_REG_ARGS ? nargs : MAX_REG_ARGS;
        for (int i = 0; i < reg_args; i++) {
            fprintf(out, "    movq    %d(%%rsp), %s\n", i * 8, arg_regs[i]);
        }

        if (stack_args == 0) {
            /* Register-only: remove entire frame before call */
            if (frame_size > 0)
                fprintf(out, "    addq    $%d, %%rsp\n", frame_size);
            fprintf(out, "    xorl    %%eax, %%eax\n");
            fprintf(out, "    call    %s\n", node->call.name);
        } else {
            /* Has stack args: remove only temp area, leave stack args */
            int temp_remove = nargs * 8;
            if (temp_remove > 0)
                fprintf(out, "    addq    $%d, %%rsp\n", temp_remove);
            fprintf(out, "    xorl    %%eax, %%eax\n");
            fprintf(out, "    call    %s\n", node->call.name);
            int remaining = frame_size - temp_remove;
            if (remaining > 0)
                fprintf(out, "    addq    $%d, %%rsp\n", remaining);
        }
        /* Result in %rax */
        break;
    }

    case NODE_IF: {
        int lelse = new_label(ctx);
        int lend = new_label(ctx);
        emit_expr(ctx, node->if_.cond);
        fprintf(out, "    testq   %%rax, %%rax\n");
        fprintf(out, "    je      .L%d\n", lelse);
        if (node->if_.then_b->kind == NODE_BLOCK) {
            for (int i = 0; i < node->if_.then_b->block.nstmts; i++)
                emit_stmt(ctx, node->if_.then_b->block.stmts[i]);
        } else {
            emit_expr(ctx, node->if_.then_b);
        }
        fprintf(out, "    jmp     .L%d\n", lend);
        fprintf(out, ".L%d:\n", lelse);
        if (node->if_.else_b) {
            if (node->if_.else_b->kind == NODE_BLOCK) {
                for (int i = 0; i < node->if_.else_b->block.nstmts; i++)
                    emit_stmt(ctx, node->if_.else_b->block.stmts[i]);
            } else {
                emit_expr(ctx, node->if_.else_b);
            }
        }
        fprintf(out, ".L%d:\n", lend);
        break;
    }

    case NODE_STRUCT_LIT: {
        /* Allocate struct via malloc so it persists past function return */
        int size = node->struct_lit.nfields * 8;
        fprintf(out, "    movq    $%d, %%rdi\n", size);
        fprintf(out, "    xorl    %%eax, %%eax\n");
        fprintf(out, "    call    malloc\n");
        /* Save struct pointer on stack with 16-byte alignment preserved */
        fprintf(out, "    subq    $16, %%rsp\n");
        fprintf(out, "    movq    %%rax, (%%rsp)\n");
        for (int i = 0; i < node->struct_lit.nfields; i++) {
            emit_expr(ctx, node->struct_lit.field_values[i]);
            fprintf(out, "    movq    %%rax, %%rcx\n"); /* field value */
            fprintf(out, "    movq    (%%rsp), %%rax\n"); /* struct ptr */
            fprintf(out, "    movq    %%rcx, %d(%%rax)\n", i * 8);
        }
        fprintf(out, "    movq    (%%rsp), %%rax\n");
        fprintf(out, "    addq    $16, %%rsp\n");
        break;
    }

    case NODE_BLOCK: {
        /* Block expression — value is last expression */
        for (int i = 0; i < node->block.nstmts; i++) {
            AstNode *s = node->block.stmts[i];
            if (i == node->block.nstmts - 1 && s->kind == NODE_EXPR_STMT) {
                emit_expr(ctx, s->expr_stmt.expr);
            } else {
                emit_stmt(ctx, s);
            }
        }
        break;
    }

    default:
        fprintf(out, "    xorq    %%rax, %%rax\n"); /* 0 for unhandled */
        break;
    }
}

/* Emit lvalue address into %rax */
static void emit_lvalue(CodegenCtx *ctx, AstNode *node) {
    FILE *out = ctx->out;
    if (node->kind == NODE_IDENT) {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc) {
            fprintf(out, "    leaq    %d(%%rbp), %%rax\n", loc->rbp_offset);
        } else {
            /* Global variable — get its address for store */
            fprintf(out, "    leaq    %s(%%rip), %%rax\n", node->ident.name);
        }
    } else if (node->kind == NODE_MEMBER) {
        emit_expr(ctx, node->member.object);
        /* Object pointer in %rax, add field offset */
        int o = resolve_member_offset(ctx, node->member.object, node->member.field);
        if (o > 0) fprintf(out, "    addq    $%d, %%rax\n", o);
    } else if (node->kind == NODE_INDEX) {
        emit_expr(ctx, node->index_.index);
        fprintf(out, "    subq    $16, %%rsp\n");
        fprintf(out, "    movq    %%rax, (%%rsp)\n");
        emit_expr(ctx, node->index_.object);
        fprintf(out, "    movq    (%%rsp), %%rcx\n");
        fprintf(out, "    addq    $16, %%rsp\n");
        if (node->index_.is_word) {
            fprintf(out, "    leaq    (%%rax,%%rcx,8), %%rax\n");
        } else {
            fprintf(out, "    addq    %%rcx, %%rax\n"); /* byte addressing */
        }
    } else if (node->kind == NODE_UNARY && node->unary.op == UNOP_DEREF) {
        emit_expr(ctx, node->unary.operand);
    }
}

static void emit_stmt(CodegenCtx *ctx, AstNode *stmt) {
    FILE *out = ctx->out;

    switch (stmt->kind) {
    case NODE_EXPR_STMT:
        emit_expr(ctx, stmt->expr_stmt.expr);
        break;

    case NODE_RETURN:
        if (stmt->ret.expr)
            emit_expr(ctx, stmt->ret.expr);
        else
            fprintf(out, "    xorl    %%eax, %%eax\n");
        fprintf(out, "    leave\n");
        fprintf(out, "    ret\n");
        break;

    case NODE_LET: {
        /* Already allocated in stack frame scan. Just initialize. */
        Local *loc = find_local(ctx, stmt->let.name);
        if (loc && stmt->let.init) {
            emit_expr(ctx, stmt->let.init);
            fprintf(out, "    movq    %%rax, %d(%%rbp)\n", loc->rbp_offset);
        }
        break;
    }

    case NODE_ASSIGN:
        emit_expr(ctx, stmt->assign.value);
        fprintf(out, "    subq    $16, %%rsp\n");
        fprintf(out, "    movq    %%rax, (%%rsp)\n");
        emit_lvalue(ctx, stmt->assign.target);
        fprintf(out, "    movq    (%%rsp), %%rcx\n");
        fprintf(out, "    addq    $16, %%rsp\n");
        /* Check if byte store (byte index) vs word store (word index) */
        if (stmt->assign.target->kind == NODE_INDEX &&
            !stmt->assign.target->index_.is_word) {
            fprintf(out, "    movb    %%cl, (%%rax)\n");
        } else {
            fprintf(out, "    movq    %%rcx, (%%rax)\n");
        }
        break;

    case NODE_IF: {
        int lelse = new_label(ctx);
        int lend = new_label(ctx);
        emit_expr(ctx, stmt->if_.cond);
        fprintf(out, "    testq   %%rax, %%rax\n");
        fprintf(out, "    je      .L%d\n", lelse);
        if (stmt->if_.then_b->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->if_.then_b->block.nstmts; i++)
                emit_stmt(ctx, stmt->if_.then_b->block.stmts[i]);
        } else {
            emit_expr(ctx, stmt->if_.then_b);
        }
        fprintf(out, "    jmp     .L%d\n", lend);
        fprintf(out, ".L%d:\n", lelse);
        if (stmt->if_.else_b) {
            if (stmt->if_.else_b->kind == NODE_BLOCK) {
                for (int i = 0; i < stmt->if_.else_b->block.nstmts; i++)
                    emit_stmt(ctx, stmt->if_.else_b->block.stmts[i]);
            } else if (stmt->if_.else_b->kind == NODE_IF) {
                emit_stmt(ctx, stmt->if_.else_b);
            } else {
                emit_expr(ctx, stmt->if_.else_b);
            }
        }
        fprintf(out, ".L%d:\n", lend);
        break;
    }

    case NODE_WHILE: {
        int ltop = new_label(ctx);
        int lend = new_label(ctx);
        int save_break = ctx->loop_break_label;
        int save_continue = ctx->loop_continue_label;
        ctx->loop_break_label = lend;
        ctx->loop_continue_label = ltop;
        fprintf(out, ".L%d:\n", ltop);
        emit_expr(ctx, stmt->while_.cond);
        fprintf(out, "    testq   %%rax, %%rax\n");
        fprintf(out, "    je      .L%d\n", lend);
        if (stmt->while_.body->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->while_.body->block.nstmts; i++)
                emit_stmt(ctx, stmt->while_.body->block.stmts[i]);
        }
        fprintf(out, "    jmp     .L%d\n", ltop);
        fprintf(out, ".L%d:\n", lend);
        ctx->loop_break_label = save_break;
        ctx->loop_continue_label = save_continue;
        break;
    }

    case NODE_FOR_RANGE: {
        /* for var in start..end { body }
         * Implemented as: var = start; while (var < end) { body; var++ } */
        Local *loc = find_local(ctx, stmt->for_range.var);
        if (!loc) break;
        emit_expr(ctx, stmt->for_range.start);
        fprintf(out, "    movq    %%rax, %d(%%rbp)\n", loc->rbp_offset);

        int ltop = new_label(ctx);
        int lend = new_label(ctx);
        int lcont = new_label(ctx);
        int save_break = ctx->loop_break_label;
        int save_continue = ctx->loop_continue_label;
        ctx->loop_break_label = lend;
        ctx->loop_continue_label = lcont;

        fprintf(out, ".L%d:\n", ltop);
        /* Compare var < end */
        emit_expr(ctx, stmt->for_range.end);
        fprintf(out, "    movq    %%rax, %%rcx\n");
        fprintf(out, "    movq    %d(%%rbp), %%rax\n", loc->rbp_offset);
        fprintf(out, "    cmpq    %%rcx, %%rax\n");
        fprintf(out, "    jge     .L%d\n", lend);

        if (stmt->for_range.body->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->for_range.body->block.nstmts; i++)
                emit_stmt(ctx, stmt->for_range.body->block.stmts[i]);
        }

        fprintf(out, ".L%d:\n", lcont);
        /* Increment loop var */
        fprintf(out, "    incq    %d(%%rbp)\n", loc->rbp_offset);
        fprintf(out, "    jmp     .L%d\n", ltop);
        fprintf(out, ".L%d:\n", lend);
        ctx->loop_break_label = save_break;
        ctx->loop_continue_label = save_continue;
        break;
    }

    case NODE_MATCH: {
        int lend = new_label(ctx);
        emit_expr(ctx, stmt->match_.expr);
        /* Save match value */
        fprintf(out, "    movq    %%rax, %%r10\n");

        for (int i = 0; i < stmt->match_.narms; i++) {
            int lnext = new_label(ctx);
            MatchArm *arm = &stmt->match_.arms[i];

            /* Determine the comparison value */
            int val = -1;
            if (arm->enum_name) {
                val = enum_variant_value(ctx, arm->enum_name, arm->pattern);
            } else {
                /* Try as enum variant (unqualified) */
                val = find_variant_value(ctx, arm->pattern, NULL);
                if (val < 0) {
                    /* Try as integer literal */
                    char *end;
                    long lval = strtol(arm->pattern, &end, 10);
                    if (*end == '\0') val = (int)lval;
                }
            }

            if (val >= 0) {
                fprintf(out, "    cmpq    $%d, %%r10\n", val);
                fprintf(out, "    jne     .L%d\n", lnext);
            }
            /* Emit arm body */
            if (arm->body->kind == NODE_BLOCK) {
                for (int j = 0; j < arm->body->block.nstmts; j++)
                    emit_stmt(ctx, arm->body->block.stmts[j]);
            } else {
                emit_expr(ctx, arm->body);
            }
            fprintf(out, "    jmp     .L%d\n", lend);
            fprintf(out, ".L%d:\n", lnext);
        }
        fprintf(out, ".L%d:\n", lend);
        break;
    }

    case NODE_BREAK:
        if (ctx->loop_break_label >= 0)
            fprintf(out, "    jmp     .L%d\n", ctx->loop_break_label);
        break;

    case NODE_CONTINUE:
        if (ctx->loop_continue_label >= 0)
            fprintf(out, "    jmp     .L%d\n", ctx->loop_continue_label);
        break;

    default:
        break;
    }
}

/* Scan a function body for local variable declarations and allocate stack slots */
static void scan_locals(CodegenCtx *ctx, AstNode *block) {
    if (!block) return;
    /* Handle non-block nodes that contain nested blocks */
    if (block->kind == NODE_IF) {
        scan_locals(ctx, block->if_.then_b);
        if (block->if_.else_b) scan_locals(ctx, block->if_.else_b);
        return;
    }
    if (block->kind != NODE_BLOCK) return;
    for (int i = 0; i < block->block.nstmts; i++) {
        AstNode *stmt = block->block.stmts[i];
        if (stmt->kind == NODE_LET) {
            if (stmt->let.is_buffer) {
                int size = stmt->let.buffer_size;
                ctx->stack_size += (size + 15) & ~15;
            } else {
                ctx->stack_size += 8;
            }
            ctx->locals[ctx->nlocals].name = stmt->let.name;
            ctx->locals[ctx->nlocals].rbp_offset = -ctx->stack_size;
            ctx->locals[ctx->nlocals].is_buffer = stmt->let.is_buffer;
            ctx->locals[ctx->nlocals].type_name = stmt->let.type_name;
            ctx->nlocals++;
        }
        /* Scan for-range loop variables */
        if (stmt->kind == NODE_FOR_RANGE) {
            ctx->stack_size += 8;
            ctx->locals[ctx->nlocals].name = stmt->for_range.var;
            ctx->locals[ctx->nlocals].rbp_offset = -ctx->stack_size;
            ctx->locals[ctx->nlocals].is_buffer = 0;
            ctx->locals[ctx->nlocals].type_name = NULL;
            ctx->nlocals++;
            /* Recurse into loop body */
            scan_locals(ctx, stmt->for_range.body);
        }
        /* Recurse into if/while/match bodies */
        if (stmt->kind == NODE_IF) {
            scan_locals(ctx, stmt->if_.then_b);
            if (stmt->if_.else_b) scan_locals(ctx, stmt->if_.else_b);
        }
        if (stmt->kind == NODE_WHILE) {
            scan_locals(ctx, stmt->while_.body);
        }
        if (stmt->kind == NODE_MATCH) {
            for (int j = 0; j < stmt->match_.narms; j++) {
                if (stmt->match_.arms[j].body->kind == NODE_BLOCK)
                    scan_locals(ctx, stmt->match_.arms[j].body);
            }
        }
    }
}

static void emit_fn(CodegenCtx *ctx, AstNode *fn) {
    ctx->nlocals = 0;
    ctx->stack_size = 0;

    AstNode *body = fn->fn_def.body;

    /* Allocate stack slots for function parameters */
    for (int i = 0; i < fn->fn_def.nparams; i++) {
        ctx->stack_size += 8;
        ctx->locals[ctx->nlocals].name = fn->fn_def.params[i].name;
        ctx->locals[ctx->nlocals].rbp_offset = -ctx->stack_size;
        ctx->locals[ctx->nlocals].is_buffer = 0;
        ctx->locals[ctx->nlocals].type_name = fn->fn_def.params[i].type_name;
        ctx->nlocals++;
    }

    /* Scan for locals in body */
    scan_locals(ctx, body);

    /* Round up to 16-byte alignment */
    ctx->stack_size = (ctx->stack_size + 15) & ~15;

    /* Prologue */
    fprintf(ctx->out, "    .globl %s\n", fn->fn_def.name);
    fprintf(ctx->out, "    .type %s, @function\n", fn->fn_def.name);
    fprintf(ctx->out, "%s:\n", fn->fn_def.name);
    fprintf(ctx->out, "    pushq   %%rbp\n");
    fprintf(ctx->out, "    movq    %%rsp, %%rbp\n");
    if (ctx->stack_size > 0)
        fprintf(ctx->out, "    subq    $%d, %%rsp\n", ctx->stack_size);

    /* Copy parameters from registers to stack slots */
    for (int i = 0; i < fn->fn_def.nparams && i < MAX_REG_ARGS; i++) {
        fprintf(ctx->out, "    movq    %s, %d(%%rbp)\n",
                arg_regs[i], ctx->locals[i].rbp_offset);
    }

    /* Emit body */
    int last_is_expr = 0;
    for (int i = 0; i < body->block.nstmts; i++) {
        AstNode *s = body->block.stmts[i];
        if (i == body->block.nstmts - 1 && s->kind == NODE_EXPR_STMT) {
            /* Last statement is an expression — use as implicit return */
            emit_expr(ctx, s->expr_stmt.expr);
            last_is_expr = 1;
        } else {
            emit_stmt(ctx, s);
        }
    }

    /* Epilogue */
    if (!last_is_expr)
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
        .nlocals = 0,
        .stack_size = 0,
        .label_count = 0,
        .loop_break_label = -1,
        .loop_continue_label = -1,
        .nstructs = 0,
        .nenums = 0,
    };

    /* First pass: register structs and enums */
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *d = program->program.decls[i];
        if (d->kind == NODE_STRUCT_DEF && ctx.nstructs < MAX_STRUCTS) {
            StructInfo *s = &ctx.structs[ctx.nstructs++];
            s->name = d->struct_def.name;
            s->nfields = d->struct_def.nfields;
            s->fields = malloc(s->nfields * sizeof(char *));
            s->field_types = malloc(s->nfields * sizeof(char *));
            for (int j = 0; j < s->nfields; j++) {
                s->fields[j] = d->struct_def.fields[j].name;
                s->field_types[j] = d->struct_def.fields[j].type_name;
            }
        }
        if (d->kind == NODE_ENUM_DEF && ctx.nenums < MAX_ENUMS) {
            EnumInfo *e = &ctx.enums[ctx.nenums++];
            e->name = d->enum_def.name;
            e->nvariants = d->enum_def.nvariants;
            e->variants = malloc(e->nvariants * sizeof(char *));
            for (int j = 0; j < e->nvariants; j++)
                e->variants[j] = d->enum_def.variants[j].name;
        }
    }

    /* Register top-level let declarations as globals */
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *d = program->program.decls[i];
        if (d->kind == NODE_LET) {
            int is_arr = d->let.is_buffer ||
                (d->let.type_name && d->let.type_name[0] == '[');
            register_global(&ctx, d->let.name, is_arr);
        }
    }

    fprintf(out, "    .text\n");

    /* Second pass: emit functions */
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *d = program->program.decls[i];
        if (d->kind == NODE_FN_DEF) {
            emit_fn(&ctx, d);
        } else if (d->kind == NODE_ANNOTATION) {
            if (d->annotation.child && d->annotation.child->kind == NODE_FN_DEF)
                emit_fn(&ctx, d->annotation.child);
        }
    }

    /* Emit string constants */
    if (ctx.nstrings > 0) {
        fprintf(out, "\n    .section .rodata\n");
        for (int i = 0; i < ctx.nstrings; i++) {
            fprintf(out, ".LC%d:\n", i);
            fprintf(out, "    .string \"");
            for (const char *p = ctx.strings[i]; *p; p++) {
                switch (*p) {
                case '\n': fprintf(out, "\\n"); break;
                case '\t': fprintf(out, "\\t"); break;
                case '\r': fprintf(out, "\\r"); break;
                case '\\': fprintf(out, "\\\\"); break;
                case '"': fprintf(out, "\\\""); break;
                default: fputc(*p, out); break;
                }
            }
            fprintf(out, "\"\n");
        }
    }

    /* Emit global variables */
    int has_globals = 0;
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *d = program->program.decls[i];
        if (d->kind == NODE_LET && d->let.init) {
            if (!has_globals) {
                fprintf(out, "\n    .data\n");
                has_globals = 1;
            }
            fprintf(out, "    .globl %s\n", d->let.name);
            fprintf(out, "%s:\n", d->let.name);
            if (d->let.init->kind == NODE_INT_LIT) {
                fprintf(out, "    .quad %ld\n", d->let.init->int_lit.value);
            } else if (d->let.init->kind == NODE_STRING_LIT) {
                int id = add_string(&ctx, d->let.init->string_lit.value);
                fprintf(out, "    .quad .LC%d\n", id);
            } else {
                fprintf(out, "    .quad 0\n");
            }
        }
    }

    fprintf(out, "\n    .section .note.GNU-stack,\"\",@progbits\n");

    /* Cleanup */
    for (int i = 0; i < ctx.nstructs; i++) {
        free(ctx.structs[i].fields);
        free(ctx.structs[i].field_types);
    }
    for (int i = 0; i < ctx.nenums; i++) free(ctx.enums[i].variants);
    free(ctx.strings);
}
