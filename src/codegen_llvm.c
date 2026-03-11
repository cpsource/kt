#include "codegen_llvm.h"
#include <stdlib.h>
#include <string.h>

#define MAX_LOCALS 128
#define MAX_STRUCTS 64
#define MAX_ENUMS 64
#define MAX_GLOBALS 128
#define MAX_FNS 256
#define MAX_EXTERNS 256

typedef struct {
    const char *name;
    int is_array;
} GlobalVar;

typedef struct {
    const char *name;
    int id;
    int is_buffer;
    int buffer_size;
    const char *type_name;
} Local;

typedef struct {
    const char *name;
    const char **fields;
    const char **field_types;
    int nfields;
} StructInfo;

typedef struct {
    const char *name;
    const char **variants;
    int nvariants;
} EnumInfo;

typedef struct {
    const char *name;
    int nparams;
} FnInfo;

typedef struct {
    FILE *out;
    const char **strings;
    int *string_lens;
    int nstrings;
    int string_cap;
    Local locals[MAX_LOCALS];
    int nlocals;
    int reg_count;
    int label_count;
    int loop_break_label;
    int loop_continue_label;
    StructInfo structs[MAX_STRUCTS];
    int nstructs;
    EnumInfo enums[MAX_ENUMS];
    int nenums;
    GlobalVar globals[MAX_GLOBALS];
    int nglobals;
    FnInfo fns[MAX_FNS];
    int nfns;
    const char *externs[MAX_EXTERNS];
    int nexterns;
} LLVMCtx;

static int new_reg(LLVMCtx *ctx) { return ctx->reg_count++; }
static int new_label(LLVMCtx *ctx) { return ctx->label_count++; }

static int string_literal_len(const char *s) {
    int len = 0;
    for (const char *p = s; *p; p++) {
        if (*p == '\\' && *(p+1)) p++;
        len++;
    }
    return len + 1;
}

static int add_string(LLVMCtx *ctx, const char *s) {
    if (ctx->nstrings >= ctx->string_cap) {
        ctx->string_cap *= 2;
        ctx->strings = realloc(ctx->strings, ctx->string_cap * sizeof(char *));
        ctx->string_lens = realloc(ctx->string_lens, ctx->string_cap * sizeof(int));
    }
    int id = ctx->nstrings;
    ctx->strings[id] = s;
    ctx->string_lens[id] = string_literal_len(s);
    ctx->nstrings++;
    return id;
}

static Local *find_local(LLVMCtx *ctx, const char *name) {
    for (int i = ctx->nlocals - 1; i >= 0; i--)
        if (strcmp(ctx->locals[i].name, name) == 0)
            return &ctx->locals[i];
    return NULL;
}

static GlobalVar *find_global(LLVMCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nglobals; i++)
        if (strcmp(ctx->globals[i].name, name) == 0)
            return &ctx->globals[i];
    return NULL;
}

static void register_global(LLVMCtx *ctx, const char *name, int is_array) {
    if (ctx->nglobals >= MAX_GLOBALS) return;
    ctx->globals[ctx->nglobals].name = name;
    ctx->globals[ctx->nglobals].is_array = is_array;
    ctx->nglobals++;
}

static FnInfo *find_fn(LLVMCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nfns; i++)
        if (strcmp(ctx->fns[i].name, name) == 0)
            return &ctx->fns[i];
    return NULL;
}

static void register_fn(LLVMCtx *ctx, const char *name, int nparams) {
    if (ctx->nfns >= MAX_FNS) return;
    ctx->fns[ctx->nfns].name = name;
    ctx->fns[ctx->nfns].nparams = nparams;
    ctx->nfns++;
}

static void add_extern(LLVMCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nexterns; i++)
        if (strcmp(ctx->externs[i], name) == 0) return;
    if (ctx->nexterns >= MAX_EXTERNS) return;
    ctx->externs[ctx->nexterns++] = name;
}

static StructInfo *find_struct(LLVMCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nstructs; i++)
        if (strcmp(ctx->structs[i].name, name) == 0)
            return &ctx->structs[i];
    return NULL;
}

static int struct_field_offset(StructInfo *s, const char *field) {
    for (int i = 0; i < s->nfields; i++)
        if (strcmp(s->fields[i], field) == 0)
            return i * 8;
    return -1;
}

static const char *struct_field_type(StructInfo *s, const char *field) {
    for (int i = 0; i < s->nfields; i++)
        if (strcmp(s->fields[i], field) == 0)
            return s->field_types[i];
    return NULL;
}

static const char *extract_struct_name(const char *type_str) {
    if (!type_str) return NULL;
    const char *p = type_str;
    if (*p == '&' || *p == '*') p++;
    if (strncmp(p, "mut ", 4) == 0) p += 4;
    while (*p == ' ') p++;
    if (*p >= 'A' && *p <= 'Z') return p;
    return NULL;
}

static const char *infer_expr_type(LLVMCtx *ctx, AstNode *node) {
    if (node->kind == NODE_IDENT) {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc && loc->type_name)
            return extract_struct_name(loc->type_name);
        return NULL;
    }
    if (node->kind == NODE_MEMBER) {
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

static int resolve_member_offset(LLVMCtx *ctx, AstNode *object, const char *field) {
    const char *obj_type = infer_expr_type(ctx, object);
    if (obj_type) {
        StructInfo *s = find_struct(ctx, obj_type);
        if (s) {
            int o = struct_field_offset(s, field);
            if (o >= 0) return o;
        }
    }
    int offset = -1, count = 0;
    for (int i = 0; i < ctx->nstructs; i++) {
        int o = struct_field_offset(&ctx->structs[i], field);
        if (o >= 0) { if (count == 0) offset = o; count++; }
    }
    return count > 0 ? offset : -1;
}

static EnumInfo *find_enum(LLVMCtx *ctx, const char *name) {
    for (int i = 0; i < ctx->nenums; i++)
        if (strcmp(ctx->enums[i].name, name) == 0)
            return &ctx->enums[i];
    return NULL;
}

static int enum_variant_value(LLVMCtx *ctx, const char *enum_name, const char *variant) {
    EnumInfo *e = find_enum(ctx, enum_name);
    if (!e) return -1;
    for (int i = 0; i < e->nvariants; i++)
        if (strcmp(e->variants[i], variant) == 0) return i;
    return -1;
}

static int find_variant_value(LLVMCtx *ctx, const char *variant, const char **out_enum) {
    for (int i = 0; i < ctx->nenums; i++)
        for (int j = 0; j < ctx->enums[i].nvariants; j++)
            if (strcmp(ctx->enums[i].variants[j], variant) == 0) {
                if (out_enum) *out_enum = ctx->enums[i].name;
                return j;
            }
    return -1;
}

/* Forward declarations */
static int emit_expr(LLVMCtx *ctx, AstNode *node);
static void emit_stmt(LLVMCtx *ctx, AstNode *stmt);

/* Emit expression — returns SSA register number holding i64 result */
static int emit_expr(LLVMCtx *ctx, AstNode *node) {
    FILE *out = ctx->out;
    int r;

    switch (node->kind) {
    case NODE_INT_LIT:
        r = new_reg(ctx);
        fprintf(out, "  %%%d = add i64 0, %ld\n", r, node->int_lit.value);
        return r;

    case NODE_BOOL_LIT:
        r = new_reg(ctx);
        fprintf(out, "  %%%d = add i64 0, %d\n", r, node->bool_lit.value);
        return r;

    case NODE_STRING_LIT: {
        int id = add_string(ctx, node->string_lit.value);
        r = new_reg(ctx);
        fprintf(out, "  %%%d = ptrtoint ptr @.str.%d to i64\n", r, id);
        return r;
    }

    case NODE_IDENT: {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc) {
            r = new_reg(ctx);
            if (loc->is_buffer) {
                fprintf(out, "  %%%d = ptrtoint ptr %%loc.%d to i64\n", r, loc->id);
            } else {
                fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", r, loc->id);
            }
            return r;
        }
        GlobalVar *gv = find_global(ctx, node->ident.name);
        r = new_reg(ctx);
        if (gv && gv->is_array) {
            fprintf(out, "  %%%d = ptrtoint ptr @%s to i64\n", r, node->ident.name);
        } else {
            fprintf(out, "  %%%d = load i64, ptr @%s\n", r, node->ident.name);
        }
        return r;
    }

    case NODE_PATH: {
        int val = enum_variant_value(ctx, node->path.base, node->path.member);
        r = new_reg(ctx);
        if (val >= 0) {
            fprintf(out, "  %%%d = add i64 0, %d\n", r, val);
        } else {
            fprintf(out, "  %%%d = load i64, ptr @%s__%s\n", r, node->path.base, node->path.member);
        }
        return r;
    }

    case NODE_BINOP: {
        int lv = emit_expr(ctx, node->binop.left);
        int rv = emit_expr(ctx, node->binop.right);
        r = new_reg(ctx);
        switch (node->binop.op) {
        case OP_ADD: fprintf(out, "  %%%d = add i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_SUB: fprintf(out, "  %%%d = sub i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_MUL: fprintf(out, "  %%%d = mul i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_DIV: fprintf(out, "  %%%d = sdiv i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_MOD: fprintf(out, "  %%%d = srem i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_EQ: case OP_NEQ: case OP_LT: case OP_GT: case OP_LTEQ: case OP_GTEQ: {
            int cmp = r; r = new_reg(ctx);
            const char *pred = "eq";
            switch (node->binop.op) {
                case OP_EQ: pred = "eq"; break;
                case OP_NEQ: pred = "ne"; break;
                case OP_LT: pred = "slt"; break;
                case OP_GT: pred = "sgt"; break;
                case OP_LTEQ: pred = "sle"; break;
                case OP_GTEQ: pred = "sge"; break;
                default: break;
            }
            fprintf(out, "  %%%d = icmp %s i64 %%%d, %%%d\n", cmp, pred, lv, rv);
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r, cmp);
            break;
        }
        case OP_AND: {
            int t1 = r, t2 = new_reg(ctx), t3 = new_reg(ctx);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t1, lv);
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t2, rv);
            fprintf(out, "  %%%d = and i1 %%%d, %%%d\n", t3, t1, t2);
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r, t3);
            break;
        }
        case OP_OR: {
            int t1 = r, t2 = new_reg(ctx), t3 = new_reg(ctx);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t1, lv);
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t2, rv);
            fprintf(out, "  %%%d = or i1 %%%d, %%%d\n", t3, t1, t2);
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r, t3);
            break;
        }
        case OP_BIT_AND: fprintf(out, "  %%%d = and i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_BIT_OR:  fprintf(out, "  %%%d = or i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_BIT_XOR: fprintf(out, "  %%%d = xor i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_SHL: fprintf(out, "  %%%d = shl i64 %%%d, %%%d\n", r, lv, rv); break;
        case OP_SHR: fprintf(out, "  %%%d = ashr i64 %%%d, %%%d\n", r, lv, rv); break;
        }
        return r;
    }

    case NODE_UNARY: {
        int val = emit_expr(ctx, node->unary.operand);
        switch (node->unary.op) {
        case UNOP_NEG:
            r = new_reg(ctx);
            fprintf(out, "  %%%d = sub i64 0, %%%d\n", r, val);
            return r;
        case UNOP_NOT: {
            int cmp = new_reg(ctx);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = icmp eq i64 %%%d, 0\n", cmp, val);
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r, cmp);
            return r;
        }
        case UNOP_BIT_NOT:
            r = new_reg(ctx);
            fprintf(out, "  %%%d = xor i64 %%%d, -1\n", r, val);
            return r;
        case UNOP_DEREF: {
            int ptr = new_reg(ctx);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, val);
            fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, ptr);
            return r;
        }
        }
        break;
    }

    case NODE_ADDR_OF:
        if (node->addr_of.operand->kind == NODE_IDENT) {
            Local *loc = find_local(ctx, node->addr_of.operand->ident.name);
            if (loc) {
                r = new_reg(ctx);
                fprintf(out, "  %%%d = ptrtoint ptr %%loc.%d to i64\n", r, loc->id);
                return r;
            }
        }
        return emit_expr(ctx, node->addr_of.operand);

    case NODE_MEMBER: {
        int obj = emit_expr(ctx, node->member.object);
        int offset = resolve_member_offset(ctx, node->member.object, node->member.field);
        if (offset < 0) offset = 0;
        int ptr = new_reg(ctx);
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj);
        int fptr = ptr;
        if (offset > 0) {
            fptr = new_reg(ctx);
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", fptr, ptr, offset);
        }
        r = new_reg(ctx);
        fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, fptr);
        return r;
    }

    case NODE_INDEX: {
        int idx = emit_expr(ctx, node->index_.index);
        int obj = emit_expr(ctx, node->index_.object);
        int ptr = new_reg(ctx);
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj);
        if (node->index_.is_word) {
            int ep = new_reg(ctx);
            fprintf(out, "  %%%d = getelementptr i64, ptr %%%d, i64 %%%d\n", ep, ptr, idx);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, ep);
        } else {
            int bp = new_reg(ctx);
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %%%d\n", bp, ptr, idx);
            int bv = new_reg(ctx);
            fprintf(out, "  %%%d = load i8, ptr %%%d\n", bv, bp);
            r = new_reg(ctx);
            fprintf(out, "  %%%d = zext i8 %%%d to i64\n", r, bv);
        }
        return r;
    }

    case NODE_CALL: {
        int nargs = node->call.nargs;
        int args[64];
        for (int i = 0; i < nargs; i++)
            args[i] = emit_expr(ctx, node->call.args[i]);

        /* Track external function usage */
        if (!find_fn(ctx, node->call.name))
            add_extern(ctx, node->call.name);

        r = new_reg(ctx);
        fprintf(out, "  %%%d = call i64 @%s(", r, node->call.name);
        for (int i = 0; i < nargs; i++) {
            if (i > 0) fprintf(out, ", ");
            fprintf(out, "i64 %%%d", args[i]);
        }
        fprintf(out, ")\n");
        return r;
    }

    case NODE_IF: {
        /* If expression — use alloca for result */
        int tmp = new_reg(ctx);
        fprintf(out, "  %%%d = alloca i64, align 8\n", tmp);

        int cond = emit_expr(ctx, node->if_.cond);
        int cmp = new_reg(ctx);
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond);
        int lthen = new_label(ctx);
        int lelse = new_label(ctx);
        int lend = new_label(ctx);
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lthen, lelse);

        /* Then */
        fprintf(out, "L%d:\n", lthen);
        int then_val;
        if (node->if_.then_b->kind == NODE_BLOCK) {
            then_val = -1;
            for (int i = 0; i < node->if_.then_b->block.nstmts; i++) {
                AstNode *s = node->if_.then_b->block.stmts[i];
                if (i == node->if_.then_b->block.nstmts - 1 && s->kind == NODE_EXPR_STMT)
                    then_val = emit_expr(ctx, s->expr_stmt.expr);
                else
                    emit_stmt(ctx, s);
            }
            if (then_val < 0) { then_val = new_reg(ctx); fprintf(out, "  %%%d = add i64 0, 0\n", then_val); }
        } else {
            then_val = emit_expr(ctx, node->if_.then_b);
        }
        fprintf(out, "  store i64 %%%d, ptr %%%d\n", then_val, tmp);
        fprintf(out, "  br label %%L%d\n", lend);

        /* Else */
        fprintf(out, "L%d:\n", lelse);
        int else_val;
        if (node->if_.else_b) {
            if (node->if_.else_b->kind == NODE_BLOCK) {
                else_val = -1;
                for (int i = 0; i < node->if_.else_b->block.nstmts; i++) {
                    AstNode *s = node->if_.else_b->block.stmts[i];
                    if (i == node->if_.else_b->block.nstmts - 1 && s->kind == NODE_EXPR_STMT)
                        else_val = emit_expr(ctx, s->expr_stmt.expr);
                    else
                        emit_stmt(ctx, s);
                }
                if (else_val < 0) { else_val = new_reg(ctx); fprintf(out, "  %%%d = add i64 0, 0\n", else_val); }
            } else if (node->if_.else_b->kind == NODE_IF) {
                /* else-if chain: emit as nested if expression */
                else_val = emit_expr(ctx, node->if_.else_b);
            } else {
                else_val = emit_expr(ctx, node->if_.else_b);
            }
        } else {
            else_val = new_reg(ctx);
            fprintf(out, "  %%%d = add i64 0, 0\n", else_val);
        }
        fprintf(out, "  store i64 %%%d, ptr %%%d\n", else_val, tmp);
        fprintf(out, "  br label %%L%d\n", lend);

        /* Merge */
        fprintf(out, "L%d:\n", lend);
        r = new_reg(ctx);
        fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, tmp);
        return r;
    }

    case NODE_STRUCT_LIT: {
        int size = node->struct_lit.nfields * 8;
        int sz = new_reg(ctx);
        fprintf(out, "  %%%d = add i64 0, %d\n", sz, size);

        /* Track malloc as external */
        if (!find_fn(ctx, "malloc")) add_extern(ctx, "malloc");

        int raw = new_reg(ctx);
        fprintf(out, "  %%%d = call i64 @malloc(i64 %%%d)\n", raw, sz);

        for (int i = 0; i < node->struct_lit.nfields; i++) {
            int fval = emit_expr(ctx, node->struct_lit.field_values[i]);
            int ptr = new_reg(ctx);
            fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, raw);
            if (i > 0) {
                int fp = new_reg(ctx);
                fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", fp, ptr, i * 8);
                fprintf(out, "  store i64 %%%d, ptr %%%d\n", fval, fp);
            } else {
                fprintf(out, "  store i64 %%%d, ptr %%%d\n", fval, ptr);
            }
        }
        return raw;
    }

    case NODE_BLOCK: {
        int val = -1;
        for (int i = 0; i < node->block.nstmts; i++) {
            AstNode *s = node->block.stmts[i];
            if (i == node->block.nstmts - 1 && s->kind == NODE_EXPR_STMT)
                val = emit_expr(ctx, s->expr_stmt.expr);
            else
                emit_stmt(ctx, s);
        }
        if (val < 0) { val = new_reg(ctx); fprintf(out, "  %%%d = add i64 0, 0\n", val); }
        return val;
    }

    default:
        r = new_reg(ctx);
        fprintf(out, "  %%%d = add i64 0, 0\n", r);
        return r;
    }

    /* unreachable, but satisfy compiler */
    r = new_reg(ctx);
    fprintf(out, "  %%%d = add i64 0, 0\n", r);
    return r;
}

/* Emit lvalue: returns SSA reg holding a ptr (LLVM ptr type) */
static int emit_lvalue(LLVMCtx *ctx, AstNode *node) {
    FILE *out = ctx->out;
    int r;

    if (node->kind == NODE_IDENT) {
        Local *loc = find_local(ctx, node->ident.name);
        if (loc) {
            /* Alloca is already a ptr. Return a copy via no-op GEP */
            r = new_reg(ctx);
            fprintf(out, "  %%%d = getelementptr i8, ptr %%loc.%d, i64 0\n", r, loc->id);
            return r;
        }
        r = new_reg(ctx);
        fprintf(out, "  %%%d = getelementptr i8, ptr @%s, i64 0\n", r, node->ident.name);
        return r;
    }
    if (node->kind == NODE_MEMBER) {
        int obj = emit_expr(ctx, node->member.object);
        int offset = resolve_member_offset(ctx, node->member.object, node->member.field);
        if (offset < 0) offset = 0;
        int ptr = new_reg(ctx);
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj);
        if (offset > 0) {
            r = new_reg(ctx);
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", r, ptr, offset);
            return r;
        }
        return ptr;
    }
    if (node->kind == NODE_INDEX) {
        int idx = emit_expr(ctx, node->index_.index);
        int obj = emit_expr(ctx, node->index_.object);
        int ptr = new_reg(ctx);
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj);
        r = new_reg(ctx);
        if (node->index_.is_word)
            fprintf(out, "  %%%d = getelementptr i64, ptr %%%d, i64 %%%d\n", r, ptr, idx);
        else
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %%%d\n", r, ptr, idx);
        return r;
    }
    if (node->kind == NODE_UNARY && node->unary.op == UNOP_DEREF) {
        int val = emit_expr(ctx, node->unary.operand);
        r = new_reg(ctx);
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", r, val);
        return r;
    }
    r = new_reg(ctx);
    fprintf(out, "  %%%d = inttoptr i64 0 to ptr\n", r);
    return r;
}

static void emit_stmt(LLVMCtx *ctx, AstNode *stmt) {
    FILE *out = ctx->out;

    switch (stmt->kind) {
    case NODE_EXPR_STMT:
        emit_expr(ctx, stmt->expr_stmt.expr);
        break;

    case NODE_RETURN:
        if (stmt->ret.expr) {
            int val = emit_expr(ctx, stmt->ret.expr);
            fprintf(out, "  ret i64 %%%d\n", val);
        } else {
            fprintf(out, "  ret i64 0\n");
        }
        /* Dead block for any code after return */
        fprintf(out, "L%d:\n", new_label(ctx));
        break;

    case NODE_LET: {
        Local *loc = find_local(ctx, stmt->let.name);
        if (loc && stmt->let.init) {
            int val = emit_expr(ctx, stmt->let.init);
            fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", val, loc->id);
        }
        break;
    }

    case NODE_ASSIGN: {
        int val = emit_expr(ctx, stmt->assign.value);
        int addr = emit_lvalue(ctx, stmt->assign.target);
        if (stmt->assign.target->kind == NODE_INDEX &&
            !stmt->assign.target->index_.is_word) {
            int trunc = new_reg(ctx);
            fprintf(out, "  %%%d = trunc i64 %%%d to i8\n", trunc, val);
            fprintf(out, "  store i8 %%%d, ptr %%%d\n", trunc, addr);
        } else {
            fprintf(out, "  store i64 %%%d, ptr %%%d\n", val, addr);
        }
        break;
    }

    case NODE_IF: {
        int cond = emit_expr(ctx, stmt->if_.cond);
        int cmp = new_reg(ctx);
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond);
        int lthen = new_label(ctx);
        int lelse = new_label(ctx);
        int lend = new_label(ctx);
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lthen, lelse);

        fprintf(out, "L%d:\n", lthen);
        if (stmt->if_.then_b->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->if_.then_b->block.nstmts; i++)
                emit_stmt(ctx, stmt->if_.then_b->block.stmts[i]);
        } else {
            emit_expr(ctx, stmt->if_.then_b);
        }
        fprintf(out, "  br label %%L%d\n", lend);

        fprintf(out, "L%d:\n", lelse);
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
        fprintf(out, "  br label %%L%d\n", lend);

        fprintf(out, "L%d:\n", lend);
        break;
    }

    case NODE_WHILE: {
        int ltop = new_label(ctx);
        int lbody = new_label(ctx);
        int lend = new_label(ctx);
        int save_break = ctx->loop_break_label;
        int save_continue = ctx->loop_continue_label;
        ctx->loop_break_label = lend;
        ctx->loop_continue_label = ltop;

        fprintf(out, "  br label %%L%d\n", ltop);
        fprintf(out, "L%d:\n", ltop);
        int cond = emit_expr(ctx, stmt->while_.cond);
        int cmp = new_reg(ctx);
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond);
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lbody, lend);

        fprintf(out, "L%d:\n", lbody);
        if (stmt->while_.body->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->while_.body->block.nstmts; i++)
                emit_stmt(ctx, stmt->while_.body->block.stmts[i]);
        }
        fprintf(out, "  br label %%L%d\n", ltop);

        fprintf(out, "L%d:\n", lend);
        ctx->loop_break_label = save_break;
        ctx->loop_continue_label = save_continue;
        break;
    }

    case NODE_FOR_RANGE: {
        Local *loc = find_local(ctx, stmt->for_range.var);
        if (!loc) break;
        int start = emit_expr(ctx, stmt->for_range.start);
        fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", start, loc->id);

        int ltop = new_label(ctx);
        int lbody = new_label(ctx);
        int lcont = new_label(ctx);
        int lend = new_label(ctx);
        int save_break = ctx->loop_break_label;
        int save_continue = ctx->loop_continue_label;
        ctx->loop_break_label = lend;
        ctx->loop_continue_label = lcont;

        fprintf(out, "  br label %%L%d\n", ltop);
        fprintf(out, "L%d:\n", ltop);
        int end_val = emit_expr(ctx, stmt->for_range.end);
        int cur = new_reg(ctx);
        fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", cur, loc->id);
        int cmp = new_reg(ctx);
        fprintf(out, "  %%%d = icmp slt i64 %%%d, %%%d\n", cmp, cur, end_val);
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lbody, lend);

        fprintf(out, "L%d:\n", lbody);
        if (stmt->for_range.body->kind == NODE_BLOCK) {
            for (int i = 0; i < stmt->for_range.body->block.nstmts; i++)
                emit_stmt(ctx, stmt->for_range.body->block.stmts[i]);
        }
        fprintf(out, "  br label %%L%d\n", lcont);

        fprintf(out, "L%d:\n", lcont);
        int cur2 = new_reg(ctx);
        fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", cur2, loc->id);
        int next = new_reg(ctx);
        fprintf(out, "  %%%d = add i64 %%%d, 1\n", next, cur2);
        fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", next, loc->id);
        fprintf(out, "  br label %%L%d\n", ltop);

        fprintf(out, "L%d:\n", lend);
        ctx->loop_break_label = save_break;
        ctx->loop_continue_label = save_continue;
        break;
    }

    case NODE_MATCH: {
        int lend = new_label(ctx);
        int match_val = emit_expr(ctx, stmt->match_.expr);

        for (int i = 0; i < stmt->match_.narms; i++) {
            int lnext = new_label(ctx);
            MatchArm *arm = &stmt->match_.arms[i];

            int val = -1;
            if (arm->enum_name) {
                val = enum_variant_value(ctx, arm->enum_name, arm->pattern);
            } else {
                val = find_variant_value(ctx, arm->pattern, NULL);
                if (val < 0) {
                    char *end;
                    long lval = strtol(arm->pattern, &end, 10);
                    if (*end == '\0') val = (int)lval;
                }
            }

            if (val >= 0) {
                int cmp = new_reg(ctx);
                int larm = new_label(ctx);
                fprintf(out, "  %%%d = icmp eq i64 %%%d, %d\n", cmp, match_val, val);
                fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, larm, lnext);
                fprintf(out, "L%d:\n", larm);
            }

            if (arm->body->kind == NODE_BLOCK) {
                for (int j = 0; j < arm->body->block.nstmts; j++)
                    emit_stmt(ctx, arm->body->block.stmts[j]);
            } else {
                emit_expr(ctx, arm->body);
            }
            fprintf(out, "  br label %%L%d\n", lend);
            fprintf(out, "L%d:\n", lnext);
        }
        fprintf(out, "  br label %%L%d\n", lend);
        fprintf(out, "L%d:\n", lend);
        break;
    }

    case NODE_BREAK:
        if (ctx->loop_break_label >= 0) {
            fprintf(out, "  br label %%L%d\n", ctx->loop_break_label);
            fprintf(out, "L%d:\n", new_label(ctx));
        }
        break;

    case NODE_CONTINUE:
        if (ctx->loop_continue_label >= 0) {
            fprintf(out, "  br label %%L%d\n", ctx->loop_continue_label);
            fprintf(out, "L%d:\n", new_label(ctx));
        }
        break;

    default:
        break;
    }
}

static void scan_locals(LLVMCtx *ctx, AstNode *block) {
    if (!block) return;
    if (block->kind == NODE_IF) {
        scan_locals(ctx, block->if_.then_b);
        if (block->if_.else_b) scan_locals(ctx, block->if_.else_b);
        return;
    }
    if (block->kind != NODE_BLOCK) return;
    for (int i = 0; i < block->block.nstmts; i++) {
        AstNode *s = block->block.stmts[i];
        if (s->kind == NODE_LET) {
            ctx->locals[ctx->nlocals].name = s->let.name;
            ctx->locals[ctx->nlocals].id = ctx->nlocals;
            ctx->locals[ctx->nlocals].is_buffer = s->let.is_buffer;
            ctx->locals[ctx->nlocals].buffer_size = s->let.buffer_size;
            ctx->locals[ctx->nlocals].type_name = s->let.type_name;
            ctx->nlocals++;
        }
        if (s->kind == NODE_FOR_RANGE) {
            ctx->locals[ctx->nlocals].name = s->for_range.var;
            ctx->locals[ctx->nlocals].id = ctx->nlocals;
            ctx->locals[ctx->nlocals].is_buffer = 0;
            ctx->locals[ctx->nlocals].buffer_size = 0;
            ctx->locals[ctx->nlocals].type_name = NULL;
            ctx->nlocals++;
            scan_locals(ctx, s->for_range.body);
        }
        if (s->kind == NODE_IF) {
            scan_locals(ctx, s->if_.then_b);
            if (s->if_.else_b) scan_locals(ctx, s->if_.else_b);
        }
        if (s->kind == NODE_WHILE)
            scan_locals(ctx, s->while_.body);
        if (s->kind == NODE_MATCH) {
            for (int j = 0; j < s->match_.narms; j++)
                if (s->match_.arms[j].body->kind == NODE_BLOCK)
                    scan_locals(ctx, s->match_.arms[j].body);
        }
    }
}

static void emit_fn(LLVMCtx *ctx, AstNode *fn) {
    FILE *out = ctx->out;
    ctx->nlocals = 0;
    ctx->reg_count = 0;
    ctx->label_count = 0;

    /* Params become first locals */
    for (int i = 0; i < fn->fn_def.nparams; i++) {
        ctx->locals[ctx->nlocals].name = fn->fn_def.params[i].name;
        ctx->locals[ctx->nlocals].id = ctx->nlocals;
        ctx->locals[ctx->nlocals].is_buffer = 0;
        ctx->locals[ctx->nlocals].buffer_size = 0;
        ctx->locals[ctx->nlocals].type_name = fn->fn_def.params[i].type_name;
        ctx->nlocals++;
    }
    scan_locals(ctx, fn->fn_def.body);

    /* Function header */
    fprintf(out, "\ndefine i64 @%s(", fn->fn_def.name);
    for (int i = 0; i < fn->fn_def.nparams; i++) {
        if (i > 0) fprintf(out, ", ");
        fprintf(out, "i64 %%arg.%d", i);
    }
    fprintf(out, ") {\n");
    fprintf(out, "entry:\n");

    /* Allocas for all locals */
    for (int i = 0; i < ctx->nlocals; i++) {
        if (ctx->locals[i].is_buffer) {
            int sz = ctx->locals[i].buffer_size;
            if (sz <= 0) sz = 8;
            fprintf(out, "  %%loc.%d = alloca [%d x i8], align 8\n", i, sz);
        } else {
            fprintf(out, "  %%loc.%d = alloca i64, align 8\n", i);
        }
    }

    /* Store params */
    for (int i = 0; i < fn->fn_def.nparams; i++)
        fprintf(out, "  store i64 %%arg.%d, ptr %%loc.%d\n", i, i);

    /* Reset reg counter (named regs don't conflict with numbered ones) */
    ctx->reg_count = 0;

    /* Emit body */
    AstNode *body = fn->fn_def.body;
    int last_val = -1;
    int last_is_expr = 0;
    for (int i = 0; i < body->block.nstmts; i++) {
        AstNode *s = body->block.stmts[i];
        if (i == body->block.nstmts - 1 && s->kind == NODE_EXPR_STMT) {
            last_val = emit_expr(ctx, s->expr_stmt.expr);
            last_is_expr = 1;
        } else {
            emit_stmt(ctx, s);
        }
    }

    if (last_is_expr)
        fprintf(out, "  ret i64 %%%d\n", last_val);
    else
        fprintf(out, "  ret i64 0\n");

    fprintf(out, "}\n");
}

static void emit_string_constant(FILE *out, int id, const char *s, int total_len) {
    fprintf(out, "@.str.%d = private unnamed_addr constant [%d x i8] c\"", id, total_len);
    for (const char *p = s; *p; p++) {
        unsigned char ch;
        if (*p == '\\' && *(p+1)) {
            p++;
            switch (*p) {
            case 'n': ch = '\n'; break;
            case 't': ch = '\t'; break;
            case 'r': ch = '\r'; break;
            case '\\': ch = '\\'; break;
            case '"': ch = '"'; break;
            case '0': ch = '\0'; break;
            default: ch = (unsigned char)*p; break;
            }
        } else {
            ch = (unsigned char)*p;
        }
        if (ch < 32 || ch > 126 || ch == '\\' || ch == '"')
            fprintf(out, "\\%02X", ch);
        else
            fputc(ch, out);
    }
    fprintf(out, "\\00\"\n");
}

void codegen_llvm(AstNode *program, FILE *out) {
    LLVMCtx ctx = {
        .out = out,
        .strings = malloc(16 * sizeof(char *)),
        .string_lens = malloc(16 * sizeof(int)),
        .nstrings = 0,
        .string_cap = 16,
        .nlocals = 0,
        .reg_count = 0,
        .label_count = 0,
        .loop_break_label = -1,
        .loop_continue_label = -1,
        .nstructs = 0,
        .nenums = 0,
        .nglobals = 0,
        .nfns = 0,
        .nexterns = 0,
    };

    /* First pass: register structs, enums, globals, function defs */
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
        if (d->kind == NODE_FN_DEF) {
            register_fn(&ctx, d->fn_def.name, d->fn_def.nparams);
        }
        if (d->kind == NODE_ANNOTATION && d->annotation.child &&
            d->annotation.child->kind == NODE_FN_DEF) {
            register_fn(&ctx, d->annotation.child->fn_def.name,
                        d->annotation.child->fn_def.nparams);
        }
        if (d->kind == NODE_LET) {
            int is_arr = d->let.is_buffer ||
                (d->let.type_name && d->let.type_name[0] == '[');
            register_global(&ctx, d->let.name, is_arr);
        }
    }

    /* Header */
    fprintf(out, "target triple = \"x86_64-unknown-linux-musl\"\n\n");

    /* Emit global variables */
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *d = program->program.decls[i];
        if (d->kind == NODE_LET) {
            if (d->let.init) {
                if (d->let.init->kind == NODE_INT_LIT) {
                    fprintf(out, "@%s = global i64 %ld\n", d->let.name, d->let.init->int_lit.value);
                } else if (d->let.init->kind == NODE_STRING_LIT) {
                    int id = add_string(&ctx, d->let.init->string_lit.value);
                    /* Store as i64 (ptrtoint'd pointer). Use initializer with a
                       constant expression. LLVM supports ptrtoint in constant exprs. */
                    fprintf(out, "@%s = global i64 ptrtoint (ptr @.str.%d to i64)\n",
                            d->let.name, id);
                } else {
                    fprintf(out, "@%s = global i64 0\n", d->let.name);
                }
            } else {
                fprintf(out, "@%s = global i64 0\n", d->let.name);
            }
        }
    }
    fprintf(out, "\n");

    /* Emit function definitions */
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
        fprintf(out, "\n; String constants\n");
        for (int i = 0; i < ctx.nstrings; i++)
            emit_string_constant(out, i, ctx.strings[i], ctx.string_lens[i]);
    }

    /* Emit external function declarations */
    if (ctx.nexterns > 0) {
        fprintf(out, "\n; External function declarations\n");
        for (int i = 0; i < ctx.nexterns; i++) {
            /* Skip if it's a defined function */
            if (find_fn(&ctx, ctx.externs[i])) continue;
            fprintf(out, "declare i64 @%s(...)\n", ctx.externs[i]);
        }
    }

    /* Cleanup */
    for (int i = 0; i < ctx.nstructs; i++) {
        free(ctx.structs[i].fields);
        free(ctx.structs[i].field_types);
    }
    for (int i = 0; i < ctx.nenums; i++) free(ctx.enums[i].variants);
    free(ctx.strings);
    free(ctx.string_lens);
}
