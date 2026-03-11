#include "types.kth"
// codegen_llvm.kt — LLVM IR code generator (self-hosted port)

let LL_MAX_LOCALS: i32 = 128
let LL_MAX_STRUCTS: i32 = 64
let LL_MAX_ENUMS: i32 = 64
let LL_MAX_GLOBALS: i32 = 128
let LL_MAX_FNS: i32 = 256
let LL_MAX_EXTERNS: i32 = 256

// ---- LLVM Codegen context ----
// Layout (all 8 bytes each):
//   0: out (FILE*)
//   1: strings (*&str)
//   2: nstrings
//   3: string_cap
//   4: string_lens (*i32)
//   5: locals_name (*&str)
//   6: locals_id (*i32)
//   7: locals_is_buffer (*i32)
//   8: locals_buffer_size (*i32)
//   9: locals_type_name (*&str)
//  10: nlocals
//  11: reg_count
//  12: label_count
//  13: loop_break_label
//  14: loop_continue_label
//  15: struct_names (*&str)
//  16: struct_fields (**&str)
//  17: struct_field_types (**&str)
//  18: struct_nfields (*i32)
//  19: nstructs
//  20: enum_names (*&str)
//  21: enum_variants (**&str)
//  22: enum_nvariants (*i32)
//  23: nenums
//  24: global_names (*&str)
//  25: global_is_array (*i32)
//  26: nglobals
//  27: fn_names (*&str)
//  28: fn_nparams (*i32)
//  29: nfns
//  30: extern_names (*&str)
//  31: nexterns
// Total: 32 words = 256 bytes

fn llctx_new(out: *u8) -> *u8 {
    let ctx = malloc(256)
    memset(ctx, 0, 256)
    ctx[[0]] = out
    ctx[[1]] = malloc(128)    // strings
    ctx[[2]] = 0              // nstrings
    ctx[[3]] = 16             // string_cap
    ctx[[4]] = malloc(128)    // string_lens
    ctx[[5]] = malloc(LL_MAX_LOCALS * 8)   // locals_name
    ctx[[6]] = malloc(LL_MAX_LOCALS * 8)   // locals_id
    ctx[[7]] = malloc(LL_MAX_LOCALS * 8)   // locals_is_buffer
    ctx[[8]] = malloc(LL_MAX_LOCALS * 8)   // locals_buffer_size
    ctx[[9]] = malloc(LL_MAX_LOCALS * 8)   // locals_type_name
    ctx[[10]] = 0   // nlocals
    ctx[[11]] = 0   // reg_count
    ctx[[12]] = 0   // label_count
    ctx[[13]] = -1  // loop_break_label
    ctx[[14]] = -1  // loop_continue_label
    ctx[[15]] = malloc(LL_MAX_STRUCTS * 8)   // struct_names
    ctx[[16]] = malloc(LL_MAX_STRUCTS * 8)   // struct_fields
    ctx[[17]] = malloc(LL_MAX_STRUCTS * 8)   // struct_field_types
    ctx[[18]] = malloc(LL_MAX_STRUCTS * 8)   // struct_nfields
    ctx[[19]] = 0   // nstructs
    ctx[[20]] = malloc(LL_MAX_ENUMS * 8)     // enum_names
    ctx[[21]] = malloc(LL_MAX_ENUMS * 8)     // enum_variants
    ctx[[22]] = malloc(LL_MAX_ENUMS * 8)     // enum_nvariants
    ctx[[23]] = 0   // nenums
    ctx[[24]] = malloc(LL_MAX_GLOBALS * 8)   // global_names
    ctx[[25]] = malloc(LL_MAX_GLOBALS * 8)   // global_is_array
    ctx[[26]] = 0   // nglobals
    ctx[[27]] = malloc(LL_MAX_FNS * 8)       // fn_names
    ctx[[28]] = malloc(LL_MAX_FNS * 8)       // fn_nparams
    ctx[[29]] = 0   // nfns
    ctx[[30]] = malloc(LL_MAX_EXTERNS * 8)   // extern_names
    ctx[[31]] = 0   // nexterns
    return ctx
}

fn ll_new_reg(ctx: *u8) -> i32 {
    let n = ctx[[11]]
    ctx[[11]] = n + 1
    return n
}

fn ll_new_label(ctx: *u8) -> i32 {
    let n = ctx[[12]]
    ctx[[12]] = n + 1
    return n
}

fn ll_string_literal_len(s: &str) -> i32 {
    let mut len: i32 = 0
    let mut i: i32 = 0
    while s[i] != 0 {
        if s[i] == 92 && s[i + 1] != 0 {  // backslash
            i = i + 1
        }
        len = len + 1
        i = i + 1
    }
    return len + 1  // +1 for null terminator
}

fn ll_add_string(ctx: *u8, s: &str) -> i32 {
    let nstrings: i32 = ctx[[2]]
    let string_cap: i32 = ctx[[3]]
    if nstrings >= string_cap {
        ctx[[3]] = string_cap * 2
        ctx[[1]] = realloc(ctx[[1]], ctx[[3]] * 8)
        ctx[[4]] = realloc(ctx[[4]], ctx[[3]] * 8)
    }
    let strings = ctx[[1]]
    let lens = ctx[[4]]
    strings[[nstrings]] = s
    lens[[nstrings]] = ll_string_literal_len(s)
    ctx[[2]] = nstrings + 1
    return nstrings
}

fn ll_find_local(ctx: *u8, name: &str) -> i32 {
    let nlocals: i32 = ctx[[10]]
    let names = ctx[[5]]
    let mut i: i32 = nlocals - 1
    while i >= 0 {
        if streq(names[[i]], name) { return i }
        i = i - 1
    }
    return -1
}

fn ll_local_id(ctx: *u8, idx: i32) -> i32 {
    let ids = ctx[[6]]
    return ids[[idx]]
}

fn ll_local_is_buffer(ctx: *u8, idx: i32) -> i32 {
    let bufs = ctx[[7]]
    return bufs[[idx]]
}

fn ll_local_buffer_size(ctx: *u8, idx: i32) -> i32 {
    let sizes = ctx[[8]]
    return sizes[[idx]]
}

fn ll_local_type_name(ctx: *u8, idx: i32) -> &str {
    let types = ctx[[9]]
    return types[[idx]]
}

fn ll_find_global(ctx: *u8, name: &str) -> i32 {
    let nglobals: i32 = ctx[[26]]
    let gnames = ctx[[24]]
    let mut i: i32 = 0
    while i < nglobals {
        if streq(gnames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn ll_global_is_array(ctx: *u8, idx: i32) -> i32 {
    let arr = ctx[[25]]
    return arr[[idx]]
}

fn ll_register_global(ctx: *u8, name: &str, is_array: i32) {
    let nglobals: i32 = ctx[[26]]
    if nglobals >= LL_MAX_GLOBALS { return }
    let gnames = ctx[[24]]
    let garr = ctx[[25]]
    gnames[[nglobals]] = name
    garr[[nglobals]] = is_array
    ctx[[26]] = nglobals + 1
}

fn ll_find_fn(ctx: *u8, name: &str) -> i32 {
    let nfns: i32 = ctx[[29]]
    let fnames = ctx[[27]]
    let mut i: i32 = 0
    while i < nfns {
        if streq(fnames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn ll_register_fn(ctx: *u8, name: &str, nparams: i32) {
    let nfns: i32 = ctx[[29]]
    if nfns >= LL_MAX_FNS { return }
    let fnames = ctx[[27]]
    let fnp = ctx[[28]]
    fnames[[nfns]] = name
    fnp[[nfns]] = nparams
    ctx[[29]] = nfns + 1
}

fn ll_add_extern(ctx: *u8, name: &str) {
    let nexterns: i32 = ctx[[31]]
    let enames = ctx[[30]]
    let mut i: i32 = 0
    while i < nexterns {
        if streq(enames[[i]], name) { return }
        i = i + 1
    }
    if nexterns >= LL_MAX_EXTERNS { return }
    enames[[nexterns]] = name
    ctx[[31]] = nexterns + 1
}

fn ll_find_struct(ctx: *u8, name: &str) -> i32 {
    let nstructs: i32 = ctx[[19]]
    let snames = ctx[[15]]
    let mut i: i32 = 0
    while i < nstructs {
        if streq(snames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn ll_struct_field_offset(ctx: *u8, si: i32, field: &str) -> i32 {
    let fields_arr = ctx[[16]]
    let nfields_arr = ctx[[18]]
    let fields = fields_arr[[si]]
    let nf: i32 = nfields_arr[[si]]
    let mut i: i32 = 0
    while i < nf {
        if streq(fields[[i]], field) { return i * 8 }
        i = i + 1
    }
    return -1
}

fn ll_struct_field_type(ctx: *u8, si: i32, field: &str) -> &str {
    let fields_arr = ctx[[16]]
    let ftypes_arr = ctx[[17]]
    let nfields_arr = ctx[[18]]
    let fields = fields_arr[[si]]
    let ftypes = ftypes_arr[[si]]
    let nf: i32 = nfields_arr[[si]]
    let mut i: i32 = 0
    while i < nf {
        if streq(fields[[i]], field) { return ftypes[[i]] }
        i = i + 1
    }
    return 0
}

fn ll_extract_struct_name(type_str: &str) -> &str {
    if type_str == 0 { return 0 }
    let mut p = type_str
    if p[0] == 38 || p[0] == 42 { p = p + 1 }  // & or *
    if starts_with(p, "mut ") { p = p + 4 }
    while p[0] == 32 { p = p + 1 }
    if p[0] >= 65 && p[0] <= 90 { return p }  // uppercase
    return 0
}

fn ll_infer_expr_type(ctx: *u8, node: *AstNode) -> &str {
    if node.kind == NodeKind::IDENT {
        let idx = ll_find_local(ctx, node.d0)
        if idx >= 0 {
            let tn = ll_local_type_name(ctx, idx)
            if tn != 0 { return ll_extract_struct_name(tn) }
        }
        return 0
    }
    if node.kind == NodeKind::MEMBER {
        let obj_type = ll_infer_expr_type(ctx, node.d0)
        if obj_type != 0 {
            let si = ll_find_struct(ctx, obj_type)
            if si >= 0 {
                let ftype = ll_struct_field_type(ctx, si, node.d1)
                if ftype != 0 { return ll_extract_struct_name(ftype) }
            }
        }
        return 0
    }
    return 0
}

fn ll_resolve_member_offset(ctx: *u8, object: *AstNode, field: &str) -> i32 {
    let obj_type = ll_infer_expr_type(ctx, object)
    if obj_type != 0 {
        let si = ll_find_struct(ctx, obj_type)
        if si >= 0 {
            let o = ll_struct_field_offset(ctx, si, field)
            if o >= 0 { return o }
        }
    }
    let nstructs: i32 = ctx[[19]]
    let mut offset: i32 = -1
    let mut i: i32 = 0
    while i < nstructs {
        let o = ll_struct_field_offset(ctx, i, field)
        if o >= 0 {
            if offset == -1 { offset = o }
        }
        i = i + 1
    }
    return offset
}

fn ll_find_enum(ctx: *u8, name: &str) -> i32 {
    let nenums: i32 = ctx[[23]]
    let enames = ctx[[20]]
    let mut i: i32 = 0
    while i < nenums {
        if streq(enames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn ll_enum_variant_value(ctx: *u8, enum_name: &str, variant: &str) -> i32 {
    let ei = ll_find_enum(ctx, enum_name)
    if ei < 0 { return -1 }
    let variants_arr = ctx[[21]]
    let nvariants_arr = ctx[[22]]
    let variants = variants_arr[[ei]]
    let nv: i32 = nvariants_arr[[ei]]
    let mut i: i32 = 0
    while i < nv {
        if streq(variants[[i]], variant) { return i }
        i = i + 1
    }
    return -1
}

fn ll_find_variant_value(ctx: *u8, variant: &str) -> i32 {
    let nenums: i32 = ctx[[23]]
    let variants_arr = ctx[[21]]
    let nvariants_arr = ctx[[22]]
    let mut i: i32 = 0
    while i < nenums {
        let variants = variants_arr[[i]]
        let nv: i32 = nvariants_arr[[i]]
        let mut j: i32 = 0
        while j < nv {
            if streq(variants[[j]], variant) { return j }
            j = j + 1
        }
        i = i + 1
    }
    return -1
}

// ---- Expression emission ----
// Returns SSA register number holding i64 result

fn ll_emit_expr(ctx: *u8, node: *AstNode) -> i32 {
    let out = ctx[[0]]

    if node.kind == NodeKind::INT_LIT {
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = add i64 0, %ld\n", r, node.d0)
        return r
    }

    if node.kind == NodeKind::BOOL_LIT {
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = add i64 0, %ld\n", r, node.d0)
        return r
    }

    if node.kind == NodeKind::STRING_LIT {
        let id = ll_add_string(ctx, node.d0)
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = ptrtoint ptr @.str.%d to i64\n", r, id)
        return r
    }

    if node.kind == NodeKind::IDENT {
        let idx = ll_find_local(ctx, node.d0)
        if idx >= 0 {
            let r = ll_new_reg(ctx)
            if ll_local_is_buffer(ctx, idx) {
                fprintf(out, "  %%%d = ptrtoint ptr %%loc.%d to i64\n", r, ll_local_id(ctx, idx))
            } else {
                fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", r, ll_local_id(ctx, idx))
            }
            return r
        }
        let gi = ll_find_global(ctx, node.d0)
        let r = ll_new_reg(ctx)
        if gi >= 0 && ll_global_is_array(ctx, gi) {
            fprintf(out, "  %%%d = ptrtoint ptr @%s to i64\n", r, node.d0)
        } else {
            fprintf(out, "  %%%d = load i64, ptr @%s\n", r, node.d0)
        }
        return r
    }

    if node.kind == NodeKind::PATH {
        let val = ll_enum_variant_value(ctx, node.d0, node.d1)
        let r = ll_new_reg(ctx)
        if val >= 0 {
            fprintf(out, "  %%%d = add i64 0, %d\n", r, val)
        } else {
            fprintf(out, "  %%%d = load i64, ptr @%s__%s\n", r, node.d0, node.d1)
        }
        return r
    }

    if node.kind == NodeKind::BINOP {
        let lv = ll_emit_expr(ctx, node.d1)  // left
        let rv = ll_emit_expr(ctx, node.d2)  // right
        let r = ll_new_reg(ctx)
        let op: i32 = node.d0
        if op == 0 { fprintf(out, "  %%%d = add i64 %%%d, %%%d\n", r, lv, rv) }        // ADD
        else if op == 1 { fprintf(out, "  %%%d = sub i64 %%%d, %%%d\n", r, lv, rv) }   // SUB
        else if op == 2 { fprintf(out, "  %%%d = mul i64 %%%d, %%%d\n", r, lv, rv) }   // MUL
        else if op == 3 { fprintf(out, "  %%%d = sdiv i64 %%%d, %%%d\n", r, lv, rv) }  // DIV
        else if op == 4 { fprintf(out, "  %%%d = srem i64 %%%d, %%%d\n", r, lv, rv) }  // MOD
        else if op == 5 {  // EQ
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp eq i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 6 {  // NEQ
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp ne i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 7 {  // LT
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp slt i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 8 {  // GT
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp sgt i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 9 {  // LTEQ
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp sle i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 10 {  // GTEQ
            let cmp = r
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp sge i64 %%%d, %%%d\n", cmp, lv, rv)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, cmp)
            return r2
        } else if op == 11 {  // AND
            let t1 = r
            let t2 = ll_new_reg(ctx)
            let t3 = ll_new_reg(ctx)
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t1, lv)
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t2, rv)
            fprintf(out, "  %%%d = and i1 %%%d, %%%d\n", t3, t1, t2)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, t3)
            return r2
        } else if op == 12 {  // OR
            let t1 = r
            let t2 = ll_new_reg(ctx)
            let t3 = ll_new_reg(ctx)
            let r2 = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t1, lv)
            fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", t2, rv)
            fprintf(out, "  %%%d = or i1 %%%d, %%%d\n", t3, t1, t2)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r2, t3)
            return r2
        } else if op == 13 { fprintf(out, "  %%%d = and i64 %%%d, %%%d\n", r, lv, rv) }  // BIT_AND
        else if op == 14 { fprintf(out, "  %%%d = or i64 %%%d, %%%d\n", r, lv, rv) }     // BIT_OR
        else if op == 15 { fprintf(out, "  %%%d = xor i64 %%%d, %%%d\n", r, lv, rv) }    // BIT_XOR
        else if op == 16 { fprintf(out, "  %%%d = shl i64 %%%d, %%%d\n", r, lv, rv) }    // SHL
        else if op == 17 { fprintf(out, "  %%%d = ashr i64 %%%d, %%%d\n", r, lv, rv) }   // SHR
        return r
    }

    if node.kind == NodeKind::UNARY {
        let val = ll_emit_expr(ctx, node.d1)  // operand
        let op: i32 = node.d0
        if op == 0 {  // NEG
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = sub i64 0, %%%d\n", r, val)
            return r
        } else if op == 1 {  // NOT
            let cmp = ll_new_reg(ctx)
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = icmp eq i64 %%%d, 0\n", cmp, val)
            fprintf(out, "  %%%d = zext i1 %%%d to i64\n", r, cmp)
            return r
        } else if op == 2 {  // BIT_NOT
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = xor i64 %%%d, -1\n", r, val)
            return r
        } else if op == 3 {  // DEREF
            let ptr = ll_new_reg(ctx)
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, val)
            fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, ptr)
            return r
        }
        return val
    }

    if node.kind == NodeKind::ADDR_OF {
        let operand: *AstNode = node.d0
        if operand.kind == NodeKind::IDENT {
            let idx = ll_find_local(ctx, operand.d0)
            if idx >= 0 {
                let r = ll_new_reg(ctx)
                fprintf(out, "  %%%d = ptrtoint ptr %%loc.%d to i64\n", r, ll_local_id(ctx, idx))
                return r
            }
        }
        return ll_emit_expr(ctx, operand)
    }

    if node.kind == NodeKind::MEMBER {
        let obj = ll_emit_expr(ctx, node.d0)  // object
        let mut offset = ll_resolve_member_offset(ctx, node.d0, node.d1)
        if offset < 0 { offset = 0 }
        let ptr = ll_new_reg(ctx)
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj)
        let mut fptr = ptr
        if offset > 0 {
            fptr = ll_new_reg(ctx)
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", fptr, ptr, offset)
        }
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, fptr)
        return r
    }

    if node.kind == NodeKind::INDEX {
        let idx = ll_emit_expr(ctx, node.d1)  // index
        let obj = ll_emit_expr(ctx, node.d0)  // object
        let ptr = ll_new_reg(ctx)
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj)
        if node.d2 {  // is_word
            let ep = ll_new_reg(ctx)
            fprintf(out, "  %%%d = getelementptr i64, ptr %%%d, i64 %%%d\n", ep, ptr, idx)
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, ep)
            return r
        } else {
            let bp = ll_new_reg(ctx)
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %%%d\n", bp, ptr, idx)
            let bv = ll_new_reg(ctx)
            fprintf(out, "  %%%d = load i8, ptr %%%d\n", bv, bp)
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = zext i8 %%%d to i64\n", r, bv)
            return r
        }
    }

    if node.kind == NodeKind::CALL {
        let nargs: i32 = node.d2
        let name: &str = node.d0
        let arg_nodes = node.d1
        // Evaluate all arguments
        let arg_regs = malloc(64 * 8)
        let mut i: i32 = 0
        while i < nargs {
            arg_regs[[i]] = ll_emit_expr(ctx, arg_nodes[[i]])
            i = i + 1
        }
        // Track external
        if ll_find_fn(ctx, name) < 0 {
            ll_add_extern(ctx, name)
        }
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = call i64 @%s(", r, name)
        i = 0
        while i < nargs {
            if i > 0 { fprintf(out, ", ") }
            fprintf(out, "i64 %%%d", arg_regs[[i]])
            i = i + 1
        }
        fprintf(out, ")\n")
        free(arg_regs)
        return r
    }

    if node.kind == NodeKind::IF {
        // If expression — use alloca for result
        let tmp = ll_new_reg(ctx)
        fprintf(out, "  %%%d = alloca i64, align 8\n", tmp)
        let cond = ll_emit_expr(ctx, node.d0)
        let cmp = ll_new_reg(ctx)
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond)
        let lthen = ll_new_label(ctx)
        let lelse = ll_new_label(ctx)
        let lend = ll_new_label(ctx)
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lthen, lelse)

        // Then block
        fprintf(out, "L%d:\n", lthen)
        let mut then_val: i32 = -1
        let then_b: *AstNode = node.d1
        if then_b.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            let nstmts: i32 = then_b.d1
            while i < nstmts {
                let s: *AstNode = then_b.d0[[i]]
                if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
                    then_val = ll_emit_expr(ctx, s.d0)
                } else {
                    ll_emit_stmt(ctx, s)
                }
                i = i + 1
            }
            if then_val < 0 {
                then_val = ll_new_reg(ctx)
                fprintf(out, "  %%%d = add i64 0, 0\n", then_val)
            }
        } else {
            then_val = ll_emit_expr(ctx, then_b)
        }
        fprintf(out, "  store i64 %%%d, ptr %%%d\n", then_val, tmp)
        fprintf(out, "  br label %%L%d\n", lend)

        // Else block
        fprintf(out, "L%d:\n", lelse)
        let mut else_val: i32 = -1
        if node.d2 != 0 {
            let else_b: *AstNode = node.d2
            if else_b.kind == NodeKind::BLOCK {
                let mut i: i32 = 0
                let nstmts: i32 = else_b.d1
                while i < nstmts {
                    let s: *AstNode = else_b.d0[[i]]
                    if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
                        else_val = ll_emit_expr(ctx, s.d0)
                    } else {
                        ll_emit_stmt(ctx, s)
                    }
                    i = i + 1
                }
                if else_val < 0 {
                    else_val = ll_new_reg(ctx)
                    fprintf(out, "  %%%d = add i64 0, 0\n", else_val)
                }
            } else if else_b.kind == NodeKind::IF {
                else_val = ll_emit_expr(ctx, else_b)
            } else {
                else_val = ll_emit_expr(ctx, else_b)
            }
        }
        if else_val < 0 {
            else_val = ll_new_reg(ctx)
            fprintf(out, "  %%%d = add i64 0, 0\n", else_val)
        }
        fprintf(out, "  store i64 %%%d, ptr %%%d\n", else_val, tmp)
        fprintf(out, "  br label %%L%d\n", lend)

        // Merge
        fprintf(out, "L%d:\n", lend)
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = load i64, ptr %%%d\n", r, tmp)
        return r
    }

    if node.kind == NodeKind::STRUCT_LIT {
        // d0=name, d1=field_names, d2=field_values, d3=nfields
        let nfields: i32 = node.d3
        let fvals = node.d2
        let size = nfields * 8
        let sz = ll_new_reg(ctx)
        fprintf(out, "  %%%d = add i64 0, %d\n", sz, size)
        if ll_find_fn(ctx, "malloc") < 0 { ll_add_extern(ctx, "malloc") }
        let raw = ll_new_reg(ctx)
        fprintf(out, "  %%%d = call i64 @malloc(i64 %%%d)\n", raw, sz)
        let mut i: i32 = 0
        while i < nfields {
            let fval = ll_emit_expr(ctx, fvals[[i]])
            let ptr = ll_new_reg(ctx)
            fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, raw)
            if i > 0 {
                let fp = ll_new_reg(ctx)
                fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", fp, ptr, i * 8)
                fprintf(out, "  store i64 %%%d, ptr %%%d\n", fval, fp)
            } else {
                fprintf(out, "  store i64 %%%d, ptr %%%d\n", fval, ptr)
            }
            i = i + 1
        }
        return raw
    }

    if node.kind == NodeKind::BLOCK {
        let mut val: i32 = -1
        let mut i: i32 = 0
        let nstmts: i32 = node.d1
        while i < nstmts {
            let s: *AstNode = node.d0[[i]]
            if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
                val = ll_emit_expr(ctx, s.d0)
            } else {
                ll_emit_stmt(ctx, s)
            }
            i = i + 1
        }
        if val < 0 {
            val = ll_new_reg(ctx)
            fprintf(out, "  %%%d = add i64 0, 0\n", val)
        }
        return val
    }

    // Default
    let r = ll_new_reg(ctx)
    fprintf(out, "  %%%d = add i64 0, 0\n", r)
    return r
}

// ---- Lvalue emission ----
// Returns SSA reg holding a ptr (LLVM ptr type)

fn ll_emit_lvalue(ctx: *u8, node: *AstNode) -> i32 {
    let out = ctx[[0]]

    if node.kind == NodeKind::IDENT {
        let idx = ll_find_local(ctx, node.d0)
        if idx >= 0 {
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = getelementptr i8, ptr %%loc.%d, i64 0\n", r, ll_local_id(ctx, idx))
            return r
        }
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = getelementptr i8, ptr @%s, i64 0\n", r, node.d0)
        return r
    }
    if node.kind == NodeKind::MEMBER {
        let obj = ll_emit_expr(ctx, node.d0)
        let mut offset = ll_resolve_member_offset(ctx, node.d0, node.d1)
        if offset < 0 { offset = 0 }
        let ptr = ll_new_reg(ctx)
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj)
        if offset > 0 {
            let r = ll_new_reg(ctx)
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %d\n", r, ptr, offset)
            return r
        }
        return ptr
    }
    if node.kind == NodeKind::INDEX {
        let idx = ll_emit_expr(ctx, node.d1)
        let obj = ll_emit_expr(ctx, node.d0)
        let ptr = ll_new_reg(ctx)
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", ptr, obj)
        let r = ll_new_reg(ctx)
        if node.d2 {  // is_word
            fprintf(out, "  %%%d = getelementptr i64, ptr %%%d, i64 %%%d\n", r, ptr, idx)
        } else {
            fprintf(out, "  %%%d = getelementptr i8, ptr %%%d, i64 %%%d\n", r, ptr, idx)
        }
        return r
    }
    if node.kind == NodeKind::UNARY && node.d0 == 3 {  // DEREF
        let val = ll_emit_expr(ctx, node.d1)
        let r = ll_new_reg(ctx)
        fprintf(out, "  %%%d = inttoptr i64 %%%d to ptr\n", r, val)
        return r
    }
    let r = ll_new_reg(ctx)
    fprintf(out, "  %%%d = inttoptr i64 0 to ptr\n", r)
    return r
}

// ---- Statement emission ----

fn ll_emit_stmt(ctx: *u8, stmt: *AstNode) {
    let out = ctx[[0]]

    if stmt.kind == NodeKind::EXPR_STMT {
        ll_emit_expr(ctx, stmt.d0)
        return
    }

    if stmt.kind == NodeKind::RETURN {
        if stmt.d0 != 0 {
            let val = ll_emit_expr(ctx, stmt.d0)
            fprintf(out, "  ret i64 %%%d\n", val)
        } else {
            fprintf(out, "  ret i64 0\n")
        }
        fprintf(out, "L%d:\n", ll_new_label(ctx))
        return
    }

    if stmt.kind == NodeKind::LET {
        let idx = ll_find_local(ctx, stmt.d0)
        // d1=init, d2=buffer_size
        if idx >= 0 && stmt.d1 != 0 && stmt.d2 == 0 {
            let val = ll_emit_expr(ctx, stmt.d1)
            fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", val, ll_local_id(ctx, idx))
        }
        return
    }

    if stmt.kind == NodeKind::ASSIGN {
        let val = ll_emit_expr(ctx, stmt.d1)
        let addr = ll_emit_lvalue(ctx, stmt.d0)
        let target: *AstNode = stmt.d0
        if target.kind == NodeKind::INDEX && target.d2 == 0 {
            // Byte store
            let trunc = ll_new_reg(ctx)
            fprintf(out, "  %%%d = trunc i64 %%%d to i8\n", trunc, val)
            fprintf(out, "  store i8 %%%d, ptr %%%d\n", trunc, addr)
        } else {
            fprintf(out, "  store i64 %%%d, ptr %%%d\n", val, addr)
        }
        return
    }

    if stmt.kind == NodeKind::IF {
        let cond = ll_emit_expr(ctx, stmt.d0)
        let cmp = ll_new_reg(ctx)
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond)
        let lthen = ll_new_label(ctx)
        let lelse = ll_new_label(ctx)
        let lend = ll_new_label(ctx)
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lthen, lelse)

        fprintf(out, "L%d:\n", lthen)
        let then_b: *AstNode = stmt.d1
        if then_b.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < then_b.d1 {
                ll_emit_stmt(ctx, then_b.d0[[i]])
                i = i + 1
            }
        } else {
            ll_emit_expr(ctx, then_b)
        }
        fprintf(out, "  br label %%L%d\n", lend)

        fprintf(out, "L%d:\n", lelse)
        if stmt.d2 != 0 {
            let else_b: *AstNode = stmt.d2
            if else_b.kind == NodeKind::BLOCK {
                let mut i: i32 = 0
                while i < else_b.d1 {
                    ll_emit_stmt(ctx, else_b.d0[[i]])
                    i = i + 1
                }
            } else if else_b.kind == NodeKind::IF {
                ll_emit_stmt(ctx, else_b)
            } else {
                ll_emit_expr(ctx, else_b)
            }
        }
        fprintf(out, "  br label %%L%d\n", lend)

        fprintf(out, "L%d:\n", lend)
        return
    }

    if stmt.kind == NodeKind::WHILE {
        let ltop = ll_new_label(ctx)
        let lbody = ll_new_label(ctx)
        let lend = ll_new_label(ctx)
        let save_break = ctx[[13]]
        let save_continue = ctx[[14]]
        ctx[[13]] = lend
        ctx[[14]] = ltop

        fprintf(out, "  br label %%L%d\n", ltop)
        fprintf(out, "L%d:\n", ltop)
        let cond = ll_emit_expr(ctx, stmt.d0)
        let cmp = ll_new_reg(ctx)
        fprintf(out, "  %%%d = icmp ne i64 %%%d, 0\n", cmp, cond)
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lbody, lend)

        fprintf(out, "L%d:\n", lbody)
        let body: *AstNode = stmt.d1
        if body.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < body.d1 {
                ll_emit_stmt(ctx, body.d0[[i]])
                i = i + 1
            }
        }
        fprintf(out, "  br label %%L%d\n", ltop)

        fprintf(out, "L%d:\n", lend)
        ctx[[13]] = save_break
        ctx[[14]] = save_continue
        return
    }

    if stmt.kind == NodeKind::FOR_RANGE {
        // d0=var, d1=start, d2=end, d3=body
        let var_name: &str = stmt.d0
        let idx = ll_find_local(ctx, var_name)
        if idx < 0 { return }
        let start = ll_emit_expr(ctx, stmt.d1)
        fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", start, ll_local_id(ctx, idx))

        let ltop = ll_new_label(ctx)
        let lbody = ll_new_label(ctx)
        let lcont = ll_new_label(ctx)
        let lend = ll_new_label(ctx)
        let save_break = ctx[[13]]
        let save_continue = ctx[[14]]
        ctx[[13]] = lend
        ctx[[14]] = lcont

        fprintf(out, "  br label %%L%d\n", ltop)
        fprintf(out, "L%d:\n", ltop)
        let end_val = ll_emit_expr(ctx, stmt.d2)
        let cur = ll_new_reg(ctx)
        fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", cur, ll_local_id(ctx, idx))
        let cmp = ll_new_reg(ctx)
        fprintf(out, "  %%%d = icmp slt i64 %%%d, %%%d\n", cmp, cur, end_val)
        fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, lbody, lend)

        fprintf(out, "L%d:\n", lbody)
        let body: *AstNode = stmt.d3
        if body.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < body.d1 {
                ll_emit_stmt(ctx, body.d0[[i]])
                i = i + 1
            }
        }
        fprintf(out, "  br label %%L%d\n", lcont)

        fprintf(out, "L%d:\n", lcont)
        let cur2 = ll_new_reg(ctx)
        fprintf(out, "  %%%d = load i64, ptr %%loc.%d\n", cur2, ll_local_id(ctx, idx))
        let next = ll_new_reg(ctx)
        fprintf(out, "  %%%d = add i64 %%%d, 1\n", next, cur2)
        fprintf(out, "  store i64 %%%d, ptr %%loc.%d\n", next, ll_local_id(ctx, idx))
        fprintf(out, "  br label %%L%d\n", ltop)

        fprintf(out, "L%d:\n", lend)
        ctx[[13]] = save_break
        ctx[[14]] = save_continue
        return
    }

    if stmt.kind == NodeKind::MATCH {
        let lend = ll_new_label(ctx)
        let match_val = ll_emit_expr(ctx, stmt.d0)
        let arms = stmt.d1
        let narms: i32 = stmt.d2
        let mut i: i32 = 0
        while i < narms {
            let lnext = ll_new_label(ctx)
            let arm_ptr = arms + i * 24
            let pattern: &str = arm_ptr[[0]]
            let enum_name: &str = arm_ptr[[1]]
            let body: *AstNode = arm_ptr[[2]]

            let mut val: i32 = -1
            if enum_name != 0 {
                val = ll_enum_variant_value(ctx, enum_name, pattern)
            } else {
                val = ll_find_variant_value(ctx, pattern)
                if val < 0 {
                    val = kt_strtol(pattern)
                    if is_digit(pattern[0]) == 0 && pattern[0] != 45 {
                        val = -1
                    }
                }
            }

            if val >= 0 {
                let cmp = ll_new_reg(ctx)
                let larm = ll_new_label(ctx)
                fprintf(out, "  %%%d = icmp eq i64 %%%d, %d\n", cmp, match_val, val)
                fprintf(out, "  br i1 %%%d, label %%L%d, label %%L%d\n", cmp, larm, lnext)
                fprintf(out, "L%d:\n", larm)
            }

            if body.kind == NodeKind::BLOCK {
                let mut j: i32 = 0
                while j < body.d1 {
                    ll_emit_stmt(ctx, body.d0[[j]])
                    j = j + 1
                }
            } else {
                ll_emit_expr(ctx, body)
            }
            fprintf(out, "  br label %%L%d\n", lend)
            fprintf(out, "L%d:\n", lnext)
            i = i + 1
        }
        fprintf(out, "  br label %%L%d\n", lend)
        fprintf(out, "L%d:\n", lend)
        return
    }

    if stmt.kind == NodeKind::BREAK {
        let brk: i32 = ctx[[13]]
        if brk >= 0 {
            fprintf(out, "  br label %%L%d\n", brk)
            fprintf(out, "L%d:\n", ll_new_label(ctx))
        }
        return
    }

    if stmt.kind == NodeKind::CONTINUE {
        let cont: i32 = ctx[[14]]
        if cont >= 0 {
            fprintf(out, "  br label %%L%d\n", cont)
            fprintf(out, "L%d:\n", ll_new_label(ctx))
        }
        return
    }
}

// ---- Local variable scanning ----

fn ll_add_local(ctx: *u8, name: &str, is_buffer: i32, buf_size: i32, type_name: &str) {
    let nlocals: i32 = ctx[[10]]
    let names = ctx[[5]]
    let ids = ctx[[6]]
    let bufs = ctx[[7]]
    let sizes = ctx[[8]]
    let types = ctx[[9]]

    names[[nlocals]] = name
    ids[[nlocals]] = nlocals
    bufs[[nlocals]] = is_buffer
    sizes[[nlocals]] = buf_size
    types[[nlocals]] = type_name
    ctx[[10]] = nlocals + 1
}

fn ll_scan_locals(ctx: *u8, block: *AstNode) {
    if block == 0 { return }
    if block.kind == NodeKind::IF {
        ll_scan_locals(ctx, block.d1)
        if block.d2 != 0 { ll_scan_locals(ctx, block.d2) }
        return
    }
    if block.kind != NodeKind::BLOCK { return }
    let mut i: i32 = 0
    let nstmts: i32 = block.d1
    while i < nstmts {
        let stmt: *AstNode = block.d0[[i]]
        if stmt.kind == NodeKind::LET {
            let mut is_buffer: i32 = 0
            if stmt.d2 > 0 { is_buffer = 1 }
            let type_name: &str = stmt.d4
            if is_buffer {
                ll_add_local(ctx, stmt.d0, 1, stmt.d2, type_name)
            } else {
                ll_add_local(ctx, stmt.d0, 0, 0, type_name)
            }
        }
        if stmt.kind == NodeKind::FOR_RANGE {
            ll_add_local(ctx, stmt.d0, 0, 0, 0)
            ll_scan_locals(ctx, stmt.d3)
        }
        if stmt.kind == NodeKind::IF {
            ll_scan_locals(ctx, stmt.d1)
            if stmt.d2 != 0 { ll_scan_locals(ctx, stmt.d2) }
        }
        if stmt.kind == NodeKind::WHILE {
            ll_scan_locals(ctx, stmt.d1)
        }
        if stmt.kind == NodeKind::MATCH {
            let arms = stmt.d1
            let narms: i32 = stmt.d2
            let mut j: i32 = 0
            while j < narms {
                let arm_ptr = arms + j * 24
                let body: *AstNode = arm_ptr[[2]]
                if body.kind == NodeKind::BLOCK {
                    ll_scan_locals(ctx, body)
                }
                j = j + 1
            }
        }
        i = i + 1
    }
}

fn ll_emit_fn(ctx: *u8, f: *AstNode) {
    let out = ctx[[0]]

    // Reset per-function state
    ctx[[10]] = 0   // nlocals
    ctx[[11]] = 0   // reg_count
    ctx[[12]] = 0   // label_count

    let name: &str = f.d0
    let body: *AstNode = f.d1
    let params = f.d2
    let nparams: i32 = f.d3

    // Params become first locals
    let mut i: i32 = 0
    while i < nparams {
        let param = params[[i]]
        let param_name: &str = param[[0]]
        let param_type: &str = param[[1]]
        ll_add_local(ctx, param_name, 0, 0, param_type)
        i = i + 1
    }

    // Scan body for locals
    ll_scan_locals(ctx, body)

    // Function header
    fprintf(out, "\ndefine i64 @%s(", name)
    i = 0
    while i < nparams {
        if i > 0 { fprintf(out, ", ") }
        fprintf(out, "i64 %%arg.%d", i)
        i = i + 1
    }
    fprintf(out, ") {\n")
    fprintf(out, "entry:\n")

    // Emit allocas for all locals
    let nlocals: i32 = ctx[[10]]
    i = 0
    while i < nlocals {
        if ll_local_is_buffer(ctx, i) {
            let mut sz = ll_local_buffer_size(ctx, i)
            if sz <= 0 { sz = 8 }
            fprintf(out, "  %%loc.%d = alloca [%d x i8], align 8\n", i, sz)
        } else {
            fprintf(out, "  %%loc.%d = alloca i64, align 8\n", i)
        }
        i = i + 1
    }

    // Store params
    i = 0
    while i < nparams {
        fprintf(out, "  store i64 %%arg.%d, ptr %%loc.%d\n", i, i)
        i = i + 1
    }

    // Reset reg counter
    ctx[[11]] = 0

    // Emit body
    let nstmts: i32 = body.d1
    let mut last_val: i32 = -1
    let mut last_is_expr: i32 = 0
    i = 0
    while i < nstmts {
        let s: *AstNode = body.d0[[i]]
        if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
            last_val = ll_emit_expr(ctx, s.d0)
            last_is_expr = 1
        } else {
            ll_emit_stmt(ctx, s)
        }
        i = i + 1
    }

    if last_is_expr {
        fprintf(out, "  ret i64 %%%d\n", last_val)
    } else {
        fprintf(out, "  ret i64 0\n")
    }

    fprintf(out, "}\n")
}

fn ll_emit_string_constant(out: *u8, id: i32, s: &str, total_len: i32) {
    fprintf(out, "@.str.%d = private unnamed_addr constant [%d x i8] c\"", id, total_len)
    let mut i: i32 = 0
    while s[i] != 0 {
        let mut ch: i32 = s[i]
        if s[i] == 92 && s[i + 1] != 0 {  // backslash
            i = i + 1
            if s[i] == 110 { ch = 10 }       // \n
            else if s[i] == 116 { ch = 9 }   // \t
            else if s[i] == 114 { ch = 13 }  // \r
            else if s[i] == 92 { ch = 92 }   // \\
            else if s[i] == 34 { ch = 34 }   // \"
            else if s[i] == 48 { ch = 0 }    // \0
            else { ch = s[i] }
        }
        if ch < 32 || ch > 126 || ch == 92 || ch == 34 {
            fprintf(out, "\\%02X", ch)
        } else {
            fputc(ch, out)
        }
        i = i + 1
    }
    fprintf(out, "\\00\"\n")
}

fn codegen_llvm(program: *AstNode, out: *u8) {
    let ctx = llctx_new(out)

    let decls = program.d0
    let ndecls: i32 = program.d1

    // First pass: register structs, enums, globals, function defs
    let mut i: i32 = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::STRUCT_DEF {
            let nstructs: i32 = ctx[[19]]
            if nstructs < LL_MAX_STRUCTS {
                let snames = ctx[[15]]
                let sfields = ctx[[16]]
                let sftypes = ctx[[17]]
                let snfields = ctx[[18]]
                snames[[nstructs]] = d.d0
                let nf: i32 = d.d2
                snfields[[nstructs]] = nf
                let field_names = malloc(nf * 8)
                let field_types = malloc(nf * 8)
                let mut j: i32 = 0
                while j < nf {
                    let fp = d.d1 + j * 16
                    field_names[[j]] = fp[[0]]
                    field_types[[j]] = fp[[1]]
                    j = j + 1
                }
                sfields[[nstructs]] = field_names
                sftypes[[nstructs]] = field_types
                ctx[[19]] = nstructs + 1
            }
        }
        if d.kind == NodeKind::ENUM_DEF {
            let nenums: i32 = ctx[[23]]
            if nenums < LL_MAX_ENUMS {
                let enames = ctx[[20]]
                let evariants = ctx[[21]]
                let envariants = ctx[[22]]
                enames[[nenums]] = d.d0
                let nv: i32 = d.d2
                envariants[[nenums]] = nv
                let vars = malloc(nv * 8)
                let mut j: i32 = 0
                while j < nv {
                    let src = d.d1
                    vars[[j]] = src[[j]]
                    j = j + 1
                }
                evariants[[nenums]] = vars
                ctx[[23]] = nenums + 1
            }
        }
        if d.kind == NodeKind::FN_DEF {
            ll_register_fn(ctx, d.d0, d.d3)
        }
        if d.kind == NodeKind::ANNOTATION {
            if d.d2 != 0 {
                let child: *AstNode = d.d2
                if child.kind == NodeKind::FN_DEF {
                    ll_register_fn(ctx, child.d0, child.d3)
                }
            }
        }
        if d.kind == NodeKind::LET {
            let mut is_buffer: i32 = 0
            if d.d2 > 0 { is_buffer = 1 }
            let type_name: &str = d.d4
            let mut is_arr: i32 = is_buffer
            if type_name != 0 && type_name[0] == 91 { is_arr = 1 }  // '['
            ll_register_global(ctx, d.d0, is_arr)
        }
        i = i + 1
    }

    // Header
    fprintf(out, "target triple = \"x86_64-unknown-linux-musl\"\n\n")

    // Emit global variables
    i = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::LET {
            if d.d1 != 0 && d.d2 == 0 {
                let init: *AstNode = d.d1
                if init.kind == NodeKind::INT_LIT {
                    fprintf(out, "@%s = global i64 %ld\n", d.d0, init.d0)
                } else if init.kind == NodeKind::STRING_LIT {
                    let id = ll_add_string(ctx, init.d0)
                    fprintf(out, "@%s = global i64 ptrtoint (ptr @.str.%d to i64)\n", d.d0, id)
                } else {
                    fprintf(out, "@%s = global i64 0\n", d.d0)
                }
            } else {
                fprintf(out, "@%s = global i64 0\n", d.d0)
            }
        }
        i = i + 1
    }
    fprintf(out, "\n")

    // Emit function definitions
    i = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::FN_DEF {
            ll_emit_fn(ctx, d)
        } else if d.kind == NodeKind::ANNOTATION {
            if d.d2 != 0 {
                let child: *AstNode = d.d2
                if child.kind == NodeKind::FN_DEF {
                    ll_emit_fn(ctx, child)
                }
            }
        }
        i = i + 1
    }

    // Emit string constants
    let nstrings: i32 = ctx[[2]]
    if nstrings > 0 {
        fprintf(out, "\n; String constants\n")
        let strings = ctx[[1]]
        let lens = ctx[[4]]
        let mut j: i32 = 0
        while j < nstrings {
            ll_emit_string_constant(out, j, strings[[j]], lens[[j]])
            j = j + 1
        }
    }

    // Emit external function declarations
    let nexterns: i32 = ctx[[31]]
    if nexterns > 0 {
        fprintf(out, "\n; External function declarations\n")
        let enames = ctx[[30]]
        let mut j: i32 = 0
        while j < nexterns {
            if ll_find_fn(ctx, enames[[j]]) < 0 {
                fprintf(out, "declare i64 @%s(...)\n", enames[[j]])
            }
            j = j + 1
        }
    }

    // Cleanup
    free(ctx[[1]])   // strings
    free(ctx[[4]])   // string_lens
    free(ctx[[5]])   // locals_name
    free(ctx[[6]])   // locals_id
    free(ctx[[7]])   // locals_is_buffer
    free(ctx[[8]])   // locals_buffer_size
    free(ctx[[9]])   // locals_type_name
    free(ctx[[15]])  // struct_names
    free(ctx[[16]])  // struct_fields
    free(ctx[[17]])  // struct_field_types
    free(ctx[[18]])  // struct_nfields
    free(ctx[[20]])  // enum_names
    free(ctx[[21]])  // enum_variants
    free(ctx[[22]])  // enum_nvariants
    free(ctx[[24]])  // global_names
    free(ctx[[25]])  // global_is_array
    free(ctx[[27]])  // fn_names
    free(ctx[[28]])  // fn_nparams
    free(ctx[[30]])  // extern_names
    free(ctx)
}
