#include "types.kth"
// codegen.kt — x86-64 GAS assembly code generator

let MAX_REG_ARGS: i32 = 6
let MAX_LOCALS: i32 = 128
let MAX_STRUCTS: i32 = 64
let MAX_ENUMS: i32 = 64
let MAX_GLOBALS: i32 = 128

fn get_arg_reg(i: i32) -> &str {
    if i == 0 { return "%rdi" }
    if i == 1 { return "%rsi" }
    if i == 2 { return "%rdx" }
    if i == 3 { return "%rcx" }
    if i == 4 { return "%r8" }
    return "%r9"
}

// ---- Codegen context ----
// Layout (all 8 bytes each):
//   0: out (FILE*)
//   1: strings (*&str)
//   2: nstrings (i32)
//   3: string_cap (i32)
//   4: locals_name (*&str)   — array of MAX_LOCALS names
//   5: locals_offset (*i32)  — array of MAX_LOCALS rbp offsets
//   6: locals_is_buffer (*i32)
//   7: locals_type_name (*&str)
//   8: nlocals (i32)
//   9: stack_size (i32)
//  10: label_count (i32)
//  11: loop_break_label (i32)
//  12: loop_continue_label (i32)
//  13: struct_names (*&str)     — MAX_STRUCTS
//  14: struct_fields (**&str)   — MAX_STRUCTS arrays of field names
//  15: struct_field_types (**&str) — MAX_STRUCTS arrays of field types
//  16: struct_nfields (*i32)
//  17: nstructs (i32)
//  18: enum_names (*&str)       — MAX_ENUMS
//  19: enum_variants (**&str)   — MAX_ENUMS arrays of variant names
//  20: enum_nvariants (*i32)
//  21: nenums (i32)
//  22: global_names (*&str)     — MAX_GLOBALS
//  23: global_is_array (*i32)
//  24: nglobals (i32)
// Total: 25 words = 200 bytes

fn ctx_new(out: *u8) -> *u8 {
    let ctx = malloc(200)
    memset(ctx, 0, 200)
    ctx[[0]] = out
    ctx[[1]] = malloc(128)  // strings
    ctx[[2]] = 0  // nstrings
    ctx[[3]] = 16  // string_cap
    ctx[[4]] = malloc(MAX_LOCALS * 8)  // locals_name
    ctx[[5]] = malloc(MAX_LOCALS * 8)  // locals_offset
    ctx[[6]] = malloc(MAX_LOCALS * 8)  // locals_is_buffer
    ctx[[7]] = malloc(MAX_LOCALS * 8)  // locals_type_name
    ctx[[8]] = 0  // nlocals
    ctx[[9]] = 0  // stack_size
    ctx[[10]] = 0  // label_count
    ctx[[11]] = -1  // loop_break_label
    ctx[[12]] = -1  // loop_continue_label
    ctx[[13]] = malloc(MAX_STRUCTS * 8)
    ctx[[14]] = malloc(MAX_STRUCTS * 8)
    ctx[[15]] = malloc(MAX_STRUCTS * 8)
    ctx[[16]] = malloc(MAX_STRUCTS * 8)
    ctx[[17]] = 0  // nstructs
    ctx[[18]] = malloc(MAX_ENUMS * 8)
    ctx[[19]] = malloc(MAX_ENUMS * 8)
    ctx[[20]] = malloc(MAX_ENUMS * 8)
    ctx[[21]] = 0  // nenums
    ctx[[22]] = malloc(MAX_GLOBALS * 8)
    ctx[[23]] = malloc(MAX_GLOBALS * 8)
    ctx[[24]] = 0  // nglobals
    return ctx
}

fn new_label(ctx: *u8) -> i32 {
    let n = ctx[[10]]
    ctx[[10]] = n + 1
    return n
}

fn add_string(ctx: *u8, s: &str) -> i32 {
    let nstrings: i32 = ctx[[2]]
    let string_cap: i32 = ctx[[3]]
    if nstrings >= string_cap {
        ctx[[3]] = string_cap * 2
        ctx[[1]] = realloc(ctx[[1]], ctx[[3]] * 8)
    }
    let strings = ctx[[1]]
    strings[[nstrings]] = s
    ctx[[2]] = nstrings + 1
    return nstrings
}

fn find_local(ctx: *u8, name: &str) -> i32 {
    // Returns index or -1
    let nlocals: i32 = ctx[[8]]
    let names = ctx[[4]]
    let mut i: i32 = nlocals - 1
    while i >= 0 {
        if streq(names[[i]], name) { return i }
        i = i - 1
    }
    return -1
}

fn local_offset(ctx: *u8, idx: i32) -> i32 {
    let offsets = ctx[[5]]
    return offsets[[idx]]
}

fn local_is_buffer(ctx: *u8, idx: i32) -> i32 {
    let bufs = ctx[[6]]
    return bufs[[idx]]
}

fn local_type_name(ctx: *u8, idx: i32) -> &str {
    let types = ctx[[7]]
    return types[[idx]]
}

fn find_global(ctx: *u8, name: &str) -> i32 {
    let nglobals: i32 = ctx[[24]]
    let gnames = ctx[[22]]
    let mut i: i32 = 0
    while i < nglobals {
        if streq(gnames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn global_is_array(ctx: *u8, idx: i32) -> i32 {
    let arr = ctx[[23]]
    return arr[[idx]]
}

fn register_global(ctx: *u8, name: &str, is_array: i32) {
    let nglobals: i32 = ctx[[24]]
    if nglobals >= MAX_GLOBALS { return }
    let gnames = ctx[[22]]
    let garr = ctx[[23]]
    gnames[[nglobals]] = name
    garr[[nglobals]] = is_array
    ctx[[24]] = nglobals + 1
}

fn find_struct(ctx: *u8, name: &str) -> i32 {
    let nstructs: i32 = ctx[[17]]
    let snames = ctx[[13]]
    let mut i: i32 = 0
    while i < nstructs {
        if streq(snames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn struct_field_offset(ctx: *u8, si: i32, field: &str) -> i32 {
    let fields_arr = ctx[[14]]
    let nfields_arr = ctx[[16]]
    let fields = fields_arr[[si]]
    let nf: i32 = nfields_arr[[si]]
    let mut i: i32 = 0
    while i < nf {
        if streq(fields[[i]], field) { return i * 8 }
        i = i + 1
    }
    return -1
}

fn struct_field_type(ctx: *u8, si: i32, field: &str) -> &str {
    let fields_arr = ctx[[14]]
    let ftypes_arr = ctx[[15]]
    let nfields_arr = ctx[[16]]
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

fn extract_struct_name(type_str: &str) -> &str {
    if type_str == 0 { return 0 }
    let mut p = type_str
    if p[0] == 38 || p[0] == 42 { p = p + 1 }  // & or *
    if starts_with(p, "mut ") { p = p + 4 }
    while p[0] == 32 { p = p + 1 }
    if p[0] >= 65 && p[0] <= 90 { return p }  // uppercase
    return 0
}

fn infer_expr_type(ctx: *u8, node: *AstNode) -> &str {
    if node.kind == NodeKind::IDENT {
        let idx = find_local(ctx, node.d0)
        if idx >= 0 {
            let tn = local_type_name(ctx, idx)
            if tn != 0 { return extract_struct_name(tn) }
        }
        return 0
    }
    if node.kind == NodeKind::MEMBER {
        let obj_type = infer_expr_type(ctx, node.d0)
        if obj_type != 0 {
            let si = find_struct(ctx, obj_type)
            if si >= 0 {
                let ftype = struct_field_type(ctx, si, node.d1)
                if ftype != 0 { return extract_struct_name(ftype) }
            }
        }
        return 0
    }
    return 0
}

fn resolve_member_offset(ctx: *u8, object: *AstNode, field: &str) -> i32 {
    let obj_type = infer_expr_type(ctx, object)
    if obj_type != 0 {
        let si = find_struct(ctx, obj_type)
        if si >= 0 {
            let o = struct_field_offset(ctx, si, field)
            if o >= 0 { return o }
        }
    }
    // Fallback: search all structs
    let nstructs: i32 = ctx[[17]]
    let mut offset: i32 = -1
    let mut i: i32 = 0
    while i < nstructs {
        let o = struct_field_offset(ctx, i, field)
        if o >= 0 {
            if offset == -1 { offset = o }
        }
        i = i + 1
    }
    return offset
}

fn find_enum(ctx: *u8, name: &str) -> i32 {
    let nenums: i32 = ctx[[21]]
    let enames = ctx[[18]]
    let mut i: i32 = 0
    while i < nenums {
        if streq(enames[[i]], name) { return i }
        i = i + 1
    }
    return -1
}

fn enum_variant_value(ctx: *u8, enum_name: &str, variant: &str) -> i32 {
    let ei = find_enum(ctx, enum_name)
    if ei < 0 { return -1 }
    let variants_arr = ctx[[19]]
    let nvariants_arr = ctx[[20]]
    let variants = variants_arr[[ei]]
    let nv: i32 = nvariants_arr[[ei]]
    let mut i: i32 = 0
    while i < nv {
        if streq(variants[[i]], variant) { return i }
        i = i + 1
    }
    return -1
}

fn find_variant_value(ctx: *u8, variant: &str) -> i32 {
    let nenums: i32 = ctx[[21]]
    let variants_arr = ctx[[19]]
    let nvariants_arr = ctx[[20]]
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

// ---- Code emission ----

fn emit_expr(ctx: *u8, node: *AstNode) {
    let out = ctx[[0]]

    if node.kind == NodeKind::INT_LIT {
        fprintf(out, "    movq    $%ld, %%rax\n", node.d0)
        return
    }

    if node.kind == NodeKind::BOOL_LIT {
        fprintf(out, "    movq    $%ld, %%rax\n", node.d0)
        return
    }

    if node.kind == NodeKind::STRING_LIT {
        let id = add_string(ctx, node.d0)
        fprintf(out, "    leaq    .LC%d(%%rip), %%rax\n", id)
        return
    }

    if node.kind == NodeKind::IDENT {
        let idx = find_local(ctx, node.d0)
        if idx >= 0 {
            if local_is_buffer(ctx, idx) {
                fprintf(out, "    leaq    %d(%%rbp), %%rax\n", local_offset(ctx, idx))
            } else {
                fprintf(out, "    movq    %d(%%rbp), %%rax\n", local_offset(ctx, idx))
            }
        } else {
            let gi = find_global(ctx, node.d0)
            if gi >= 0 && global_is_array(ctx, gi) {
                fprintf(out, "    leaq    %s(%%rip), %%rax\n", node.d0)
            } else {
                fprintf(out, "    movq    %s(%%rip), %%rax\n", node.d0)
            }
        }
        return
    }

    if node.kind == NodeKind::PATH {
        let val = enum_variant_value(ctx, node.d0, node.d1)
        if val >= 0 {
            fprintf(out, "    movq    $%d, %%rax\n", val)
        } else {
            fprintf(out, "    movq    %s__%s(%%rip), %%rax\n", node.d0, node.d1)
        }
        return
    }

    if node.kind == NodeKind::BINOP {
        emit_expr(ctx, node.d2)  // right
        fprintf(out, "    subq    $16, %%rsp\n")
        fprintf(out, "    movq    %%rax, (%%rsp)\n")
        emit_expr(ctx, node.d1)  // left
        fprintf(out, "    movq    (%%rsp), %%rcx\n")
        fprintf(out, "    addq    $16, %%rsp\n")
        // rax = left, rcx = right
        let op: i32 = node.d0
        if op == 0 { fprintf(out, "    addq    %%rcx, %%rax\n") }       // ADD
        else if op == 1 { fprintf(out, "    subq    %%rcx, %%rax\n") }  // SUB
        else if op == 2 { fprintf(out, "    imulq   %%rcx, %%rax\n") }  // MUL
        else if op == 3 {                                                // DIV
            fprintf(out, "    cqto\n")
            fprintf(out, "    idivq   %%rcx\n")
        } else if op == 4 {                                              // MOD
            fprintf(out, "    cqto\n")
            fprintf(out, "    idivq   %%rcx\n")
            fprintf(out, "    movq    %%rdx, %%rax\n")
        } else if op == 5 {                                              // EQ
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    sete    %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 6 {                                              // NEQ
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    setne   %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 7 {                                              // LT
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    setl    %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 8 {                                              // GT
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    setg    %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 9 {                                              // LTEQ
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    setle   %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 10 {                                             // GTEQ
            fprintf(out, "    cmpq    %%rcx, %%rax\n")
            fprintf(out, "    setge   %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 11 {                                             // AND
            let lf = new_label(ctx)
            let le = new_label(ctx)
            fprintf(out, "    testq   %%rax, %%rax\n")
            fprintf(out, "    je      .L%d\n", lf)
            fprintf(out, "    testq   %%rcx, %%rcx\n")
            fprintf(out, "    je      .L%d\n", lf)
            fprintf(out, "    movq    $1, %%rax\n")
            fprintf(out, "    jmp     .L%d\n", le)
            fprintf(out, ".L%d:\n", lf)
            fprintf(out, "    xorq    %%rax, %%rax\n")
            fprintf(out, ".L%d:\n", le)
        } else if op == 12 {                                             // OR
            let lt = new_label(ctx)
            let le = new_label(ctx)
            fprintf(out, "    testq   %%rax, %%rax\n")
            fprintf(out, "    jne     .L%d\n", lt)
            fprintf(out, "    testq   %%rcx, %%rcx\n")
            fprintf(out, "    jne     .L%d\n", lt)
            fprintf(out, "    xorq    %%rax, %%rax\n")
            fprintf(out, "    jmp     .L%d\n", le)
            fprintf(out, ".L%d:\n", lt)
            fprintf(out, "    movq    $1, %%rax\n")
            fprintf(out, ".L%d:\n", le)
        } else if op == 13 { fprintf(out, "    andq    %%rcx, %%rax\n") }  // BIT_AND
        else if op == 14 { fprintf(out, "    orq     %%rcx, %%rax\n") }    // BIT_OR
        else if op == 15 { fprintf(out, "    xorq    %%rcx, %%rax\n") }    // BIT_XOR
        else if op == 16 { fprintf(out, "    shlq    %%cl, %%rax\n") }     // SHL
        else if op == 17 { fprintf(out, "    sarq    %%cl, %%rax\n") }     // SHR
        return
    }

    if node.kind == NodeKind::UNARY {
        emit_expr(ctx, node.d1)  // operand
        let op: i32 = node.d0
        if op == 0 { fprintf(out, "    negq    %%rax\n") }  // NEG
        else if op == 1 {  // NOT
            fprintf(out, "    testq   %%rax, %%rax\n")
            fprintf(out, "    sete    %%al\n")
            fprintf(out, "    movzbq  %%al, %%rax\n")
        } else if op == 2 { fprintf(out, "    notq    %%rax\n") }  // BIT_NOT
        else if op == 3 { fprintf(out, "    movq    (%%rax), %%rax\n") }  // DEREF
        return
    }

    if node.kind == NodeKind::ADDR_OF {
        // For local variables, compute address
        let operand: *AstNode = node.d0
        if operand.kind == NodeKind::IDENT {
            let idx = find_local(ctx, operand.d0)
            if idx >= 0 {
                fprintf(out, "    leaq    %d(%%rbp), %%rax\n", local_offset(ctx, idx))
                return
            }
        }
        emit_expr(ctx, operand)
        return
    }

    if node.kind == NodeKind::MEMBER {
        emit_expr(ctx, node.d0)  // object
        let offset = resolve_member_offset(ctx, node.d0, node.d1)
        if offset >= 0 {
            fprintf(out, "    movq    %d(%%rax), %%rax\n", offset)
        } else {
            fprintf(out, "    movq    (%%rax), %%rax\n")
        }
        return
    }

    if node.kind == NodeKind::INDEX {
        emit_expr(ctx, node.d1)  // index
        fprintf(out, "    subq    $16, %%rsp\n")
        fprintf(out, "    movq    %%rax, (%%rsp)\n")
        emit_expr(ctx, node.d0)  // object
        fprintf(out, "    movq    (%%rsp), %%rcx\n")
        fprintf(out, "    addq    $16, %%rsp\n")
        if node.d2 {  // is_word
            fprintf(out, "    movq    (%%rax,%%rcx,8), %%rax\n")
        } else {
            fprintf(out, "    movzbq  (%%rax,%%rcx), %%rax\n")
        }
        return
    }

    if node.kind == NodeKind::CALL {
        let nargs: i32 = node.d2
        let name: &str = node.d0
        let args = node.d1
        let stack_args = nargs - MAX_REG_ARGS
        let mut sa: i32 = 0
        if stack_args > 0 { sa = stack_args }

        // Pre-allocate frame
        let mut total_slots = nargs + sa
        if sa % 2 != 0 { total_slots = total_slots + 1 }
        let frame_size = (total_slots * 8 + 15) & ~15
        if frame_size > 0 {
            fprintf(out, "    subq    $%d, %%rsp\n", frame_size)
        }

        // Eval each arg to temp slot
        let mut i: i32 = 0
        while i < nargs {
            emit_expr(ctx, args[[i]])
            fprintf(out, "    movq    %%rax, %d(%%rsp)\n", i * 8)
            i = i + 1
        }

        // Copy stack args
        if sa > 0 {
            let mut call_area = nargs * 8
            if sa % 2 != 0 { call_area = call_area + 8 }
            i = MAX_REG_ARGS
            while i < nargs {
                let src_off = i * 8
                let dst_off = call_area + (i - MAX_REG_ARGS) * 8
                fprintf(out, "    movq    %d(%%rsp), %%r10\n", src_off)
                fprintf(out, "    movq    %%r10, %d(%%rsp)\n", dst_off)
                i = i + 1
            }
        }

        // Load register args
        let mut reg_args = nargs
        if reg_args > MAX_REG_ARGS { reg_args = MAX_REG_ARGS }
        i = 0
        while i < reg_args {
            fprintf(out, "    movq    %d(%%rsp), %s\n", i * 8, get_arg_reg(i))
            i = i + 1
        }

        if sa == 0 {
            if frame_size > 0 {
                fprintf(out, "    addq    $%d, %%rsp\n", frame_size)
            }
            fprintf(out, "    xorl    %%eax, %%eax\n")
            fprintf(out, "    call    %s\n", name)
        } else {
            let temp_remove = nargs * 8
            if temp_remove > 0 {
                fprintf(out, "    addq    $%d, %%rsp\n", temp_remove)
            }
            fprintf(out, "    xorl    %%eax, %%eax\n")
            fprintf(out, "    call    %s\n", name)
            let remaining = frame_size - temp_remove
            if remaining > 0 {
                fprintf(out, "    addq    $%d, %%rsp\n", remaining)
            }
        }
        return
    }

    if node.kind == NodeKind::IF {
        let lelse = new_label(ctx)
        let lend = new_label(ctx)
        emit_expr(ctx, node.d0)  // cond
        fprintf(out, "    testq   %%rax, %%rax\n")
        fprintf(out, "    je      .L%d\n", lelse)
        let then_b: *AstNode = node.d1
        if then_b.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < then_b.d1 {
                emit_stmt(ctx, then_b.d0[[i]])
                i = i + 1
            }
        } else {
            emit_expr(ctx, then_b)
        }
        fprintf(out, "    jmp     .L%d\n", lend)
        fprintf(out, ".L%d:\n", lelse)
        if node.d2 != 0 {
            let else_b: *AstNode = node.d2
            if else_b.kind == NodeKind::BLOCK {
                let mut i: i32 = 0
                while i < else_b.d1 {
                    emit_stmt(ctx, else_b.d0[[i]])
                    i = i + 1
                }
            } else {
                emit_expr(ctx, else_b)
            }
        }
        fprintf(out, ".L%d:\n", lend)
        return
    }

    if node.kind == NodeKind::STRUCT_LIT {
        // d0=name, d1=field_names, d2=field_values
        // nfields is stored in the word right after d2 in memory
        // Access: the "extra" word at node + 40
        let nfields_ptr = node + 40
        let nfields: i32 = nfields_ptr[[0]]
        let fvals = node.d2
        let size = nfields * 8
        fprintf(out, "    movq    $%d, %%rdi\n", size)
        fprintf(out, "    xorl    %%eax, %%eax\n")
        fprintf(out, "    call    malloc\n")
        fprintf(out, "    subq    $16, %%rsp\n")
        fprintf(out, "    movq    %%rax, (%%rsp)\n")
        let mut i: i32 = 0
        while i < nfields {
            emit_expr(ctx, fvals[[i]])
            fprintf(out, "    movq    %%rax, %%rcx\n")
            fprintf(out, "    movq    (%%rsp), %%rax\n")
            fprintf(out, "    movq    %%rcx, %d(%%rax)\n", i * 8)
            i = i + 1
        }
        fprintf(out, "    movq    (%%rsp), %%rax\n")
        fprintf(out, "    addq    $16, %%rsp\n")
        return
    }

    if node.kind == NodeKind::BLOCK {
        let mut i: i32 = 0
        let nstmts: i32 = node.d1
        while i < nstmts {
            let s: *AstNode = node.d0[[i]]
            if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
                emit_expr(ctx, s.d0)
            } else {
                emit_stmt(ctx, s)
            }
            i = i + 1
        }
        return
    }

    // Default
    fprintf(out, "    xorq    %%rax, %%rax\n")
}

fn emit_lvalue(ctx: *u8, node: *AstNode) {
    let out = ctx[[0]]
    if node.kind == NodeKind::IDENT {
        let idx = find_local(ctx, node.d0)
        if idx >= 0 {
            fprintf(out, "    leaq    %d(%%rbp), %%rax\n", local_offset(ctx, idx))
        } else {
            fprintf(out, "    leaq    %s(%%rip), %%rax\n", node.d0)
        }
    } else if node.kind == NodeKind::MEMBER {
        emit_expr(ctx, node.d0)  // object
        let o = resolve_member_offset(ctx, node.d0, node.d1)
        if o > 0 { fprintf(out, "    addq    $%d, %%rax\n", o) }
    } else if node.kind == NodeKind::INDEX {
        emit_expr(ctx, node.d1)  // index
        fprintf(out, "    subq    $16, %%rsp\n")
        fprintf(out, "    movq    %%rax, (%%rsp)\n")
        emit_expr(ctx, node.d0)  // object
        fprintf(out, "    movq    (%%rsp), %%rcx\n")
        fprintf(out, "    addq    $16, %%rsp\n")
        if node.d2 {  // is_word
            fprintf(out, "    leaq    (%%rax,%%rcx,8), %%rax\n")
        } else {
            fprintf(out, "    addq    %%rcx, %%rax\n")
        }
    } else if node.kind == NodeKind::UNARY && node.d0 == 3 {  // DEREF
        emit_expr(ctx, node.d1)
    }
}

fn emit_stmt(ctx: *u8, stmt: *AstNode) {
    let out = ctx[[0]]

    if stmt.kind == NodeKind::EXPR_STMT {
        emit_expr(ctx, stmt.d0)
        return
    }

    if stmt.kind == NodeKind::RETURN {
        if stmt.d0 != 0 {
            emit_expr(ctx, stmt.d0)
        } else {
            fprintf(out, "    xorl    %%eax, %%eax\n")
        }
        fprintf(out, "    leave\n")
        fprintf(out, "    ret\n")
        return
    }

    if stmt.kind == NodeKind::LET {
        let idx = find_local(ctx, stmt.d0)
        // d1 = init expression, d2 = buffer size
        // If d2 > 0, it's a buffer (no init).
        // If d1 != 0 and d2 == 0, it's a regular let with init.
        if idx >= 0 && stmt.d1 != 0 && stmt.d2 == 0 {
            emit_expr(ctx, stmt.d1)
            fprintf(out, "    movq    %%rax, %d(%%rbp)\n", local_offset(ctx, idx))
        }
        return
    }

    if stmt.kind == NodeKind::ASSIGN {
        emit_expr(ctx, stmt.d1)  // value
        fprintf(out, "    subq    $16, %%rsp\n")
        fprintf(out, "    movq    %%rax, (%%rsp)\n")
        emit_lvalue(ctx, stmt.d0)  // target
        fprintf(out, "    movq    (%%rsp), %%rcx\n")
        fprintf(out, "    addq    $16, %%rsp\n")
        // Byte store for byte index, word store otherwise
        let target: *AstNode = stmt.d0
        if target.kind == NodeKind::INDEX && target.d2 == 0 {
            fprintf(out, "    movb    %%cl, (%%rax)\n")
        } else {
            fprintf(out, "    movq    %%rcx, (%%rax)\n")
        }
        return
    }

    if stmt.kind == NodeKind::IF {
        let lelse = new_label(ctx)
        let lend = new_label(ctx)
        emit_expr(ctx, stmt.d0)  // cond
        fprintf(out, "    testq   %%rax, %%rax\n")
        fprintf(out, "    je      .L%d\n", lelse)
        let then_b: *AstNode = stmt.d1
        if then_b.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < then_b.d1 {
                emit_stmt(ctx, then_b.d0[[i]])
                i = i + 1
            }
        } else {
            emit_expr(ctx, then_b)
        }
        fprintf(out, "    jmp     .L%d\n", lend)
        fprintf(out, ".L%d:\n", lelse)
        if stmt.d2 != 0 {
            let else_b: *AstNode = stmt.d2
            if else_b.kind == NodeKind::BLOCK {
                let mut i: i32 = 0
                while i < else_b.d1 {
                    emit_stmt(ctx, else_b.d0[[i]])
                    i = i + 1
                }
            } else if else_b.kind == NodeKind::IF {
                emit_stmt(ctx, else_b)
            } else {
                emit_expr(ctx, else_b)
            }
        }
        fprintf(out, ".L%d:\n", lend)
        return
    }

    if stmt.kind == NodeKind::WHILE {
        let ltop = new_label(ctx)
        let lend = new_label(ctx)
        let save_break = ctx[[11]]
        let save_continue = ctx[[12]]
        ctx[[11]] = lend
        ctx[[12]] = ltop
        fprintf(out, ".L%d:\n", ltop)
        emit_expr(ctx, stmt.d0)  // cond
        fprintf(out, "    testq   %%rax, %%rax\n")
        fprintf(out, "    je      .L%d\n", lend)
        let body: *AstNode = stmt.d1
        if body.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < body.d1 {
                emit_stmt(ctx, body.d0[[i]])
                i = i + 1
            }
        }
        fprintf(out, "    jmp     .L%d\n", ltop)
        fprintf(out, ".L%d:\n", lend)
        ctx[[11]] = save_break
        ctx[[12]] = save_continue
        return
    }

    if stmt.kind == NodeKind::FOR_RANGE {
        // d0=var, d1=start, d2=end, body via extra (at stmt+40)
        let var_name: &str = stmt.d0
        let idx = find_local(ctx, var_name)
        if idx < 0 { return }
        emit_expr(ctx, stmt.d1)  // start
        fprintf(out, "    movq    %%rax, %d(%%rbp)\n", local_offset(ctx, idx))

        let ltop = new_label(ctx)
        let lend = new_label(ctx)
        let lcont = new_label(ctx)
        let save_break = ctx[[11]]
        let save_continue = ctx[[12]]
        ctx[[11]] = lend
        ctx[[12]] = lcont

        fprintf(out, ".L%d:\n", ltop)
        emit_expr(ctx, stmt.d2)  // end
        fprintf(out, "    movq    %%rax, %%rcx\n")
        fprintf(out, "    movq    %d(%%rbp), %%rax\n", local_offset(ctx, idx))
        fprintf(out, "    cmpq    %%rcx, %%rax\n")
        fprintf(out, "    jge     .L%d\n", lend)

        // body is stored at extra (stmt + 40)
        let extra_ptr = stmt + 40
        let body: *AstNode = extra_ptr[[0]]
        if body.kind == NodeKind::BLOCK {
            let mut i: i32 = 0
            while i < body.d1 {
                emit_stmt(ctx, body.d0[[i]])
                i = i + 1
            }
        }

        fprintf(out, ".L%d:\n", lcont)
        fprintf(out, "    incq    %d(%%rbp)\n", local_offset(ctx, idx))
        fprintf(out, "    jmp     .L%d\n", ltop)
        fprintf(out, ".L%d:\n", lend)
        ctx[[11]] = save_break
        ctx[[12]] = save_continue
        return
    }

    if stmt.kind == NodeKind::MATCH {
        let lend = new_label(ctx)
        emit_expr(ctx, stmt.d0)  // expr
        fprintf(out, "    movq    %%rax, %%r10\n")
        let arms = stmt.d1
        let narms: i32 = stmt.d2
        let mut i: i32 = 0
        while i < narms {
            let lnext = new_label(ctx)
            let arm_ptr = arms + i * 24
            let pattern: &str = arm_ptr[[0]]
            let enum_name: &str = arm_ptr[[1]]
            let body: *AstNode = arm_ptr[[2]]

            let mut val: i32 = -1
            if enum_name != 0 {
                val = enum_variant_value(ctx, enum_name, pattern)
            } else {
                val = find_variant_value(ctx, pattern)
                if val < 0 {
                    // Try as integer literal
                    val = kt_strtol(pattern)
                    // Check if it's actually a number (first char is digit or minus)
                    if is_digit(pattern[0]) == 0 && pattern[0] != 45 {
                        val = -1
                    }
                }
            }

            if val >= 0 {
                fprintf(out, "    cmpq    $%d, %%r10\n", val)
                fprintf(out, "    jne     .L%d\n", lnext)
            }
            if body.kind == NodeKind::BLOCK {
                let mut j: i32 = 0
                while j < body.d1 {
                    emit_stmt(ctx, body.d0[[j]])
                    j = j + 1
                }
            } else {
                emit_expr(ctx, body)
            }
            fprintf(out, "    jmp     .L%d\n", lend)
            fprintf(out, ".L%d:\n", lnext)
            i = i + 1
        }
        fprintf(out, ".L%d:\n", lend)
        return
    }

    if stmt.kind == NodeKind::BREAK {
        let brk: i32 = ctx[[11]]
        if brk >= 0 {
            fprintf(out, "    jmp     .L%d\n", brk)
        }
        return
    }

    if stmt.kind == NodeKind::CONTINUE {
        let cont: i32 = ctx[[12]]
        if cont >= 0 {
            fprintf(out, "    jmp     .L%d\n", cont)
        }
        return
    }
}

// ---- Local variable scanning ----

fn add_local(ctx: *u8, name: &str, is_buffer: i32, buf_size: i32, type_name: &str) {
    let nlocals: i32 = ctx[[8]]
    let stack_size: i32 = ctx[[9]]
    let names = ctx[[4]]
    let offsets = ctx[[5]]
    let bufs = ctx[[6]]
    let types = ctx[[7]]

    let mut new_stack: i32 = 0
    if is_buffer {
        new_stack = stack_size + ((buf_size + 15) & ~15)
    } else {
        new_stack = stack_size + 8
    }
    ctx[[9]] = new_stack

    names[[nlocals]] = name
    offsets[[nlocals]] = 0 - new_stack
    bufs[[nlocals]] = is_buffer
    types[[nlocals]] = type_name
    ctx[[8]] = nlocals + 1
}

fn scan_locals(ctx: *u8, block: *AstNode) {
    if block == 0 { return }
    if block.kind == NodeKind::IF {
        scan_locals(ctx, block.d1)
        if block.d2 != 0 { scan_locals(ctx, block.d2) }
        return
    }
    if block.kind != NodeKind::BLOCK { return }
    let mut i: i32 = 0
    let nstmts: i32 = block.d1
    while i < nstmts {
        let stmt: *AstNode = block.d0[[i]]
        if stmt.kind == NodeKind::LET {
            // Read extra data: [is_mut, is_buffer, type_name] at stmt+40
            let extra_ptr = stmt + 40
            let is_buffer: i32 = extra_ptr[[1]]
            let type_name: &str = extra_ptr[[2]]
            if is_buffer {
                add_local(ctx, stmt.d0, 1, stmt.d2, type_name)
            } else {
                add_local(ctx, stmt.d0, 0, 0, type_name)
            }
        }
        if stmt.kind == NodeKind::FOR_RANGE {
            add_local(ctx, stmt.d0, 0, 0, 0)
            let extra_ptr = stmt + 40
            let body: *AstNode = extra_ptr[[0]]
            scan_locals(ctx, body)
        }
        if stmt.kind == NodeKind::IF {
            scan_locals(ctx, stmt.d1)
            if stmt.d2 != 0 { scan_locals(ctx, stmt.d2) }
        }
        if stmt.kind == NodeKind::WHILE {
            scan_locals(ctx, stmt.d1)
        }
        if stmt.kind == NodeKind::MATCH {
            let arms = stmt.d1
            let narms: i32 = stmt.d2
            let mut j: i32 = 0
            while j < narms {
                let arm_ptr = arms + j * 24
                let body: *AstNode = arm_ptr[[2]]
                if body.kind == NodeKind::BLOCK {
                    scan_locals(ctx, body)
                }
                j = j + 1
            }
        }
        i = i + 1
    }
}

fn emit_fn(ctx: *u8, f: *AstNode) {
    let out = ctx[[0]]

    // Reset locals
    ctx[[8]] = 0  // nlocals
    ctx[[9]] = 0  // stack_size

    let name: &str = f.d0
    let body: *AstNode = f.d1

    // Read fn extra data: [params, nparams, ret_type] at f+40
    let fn_extra = f + 40
    let params = fn_extra[[0]]
    let nparams: i32 = fn_extra[[1]]

    // Allocate stack slots for parameters
    let mut i: i32 = 0
    while i < nparams {
        // Each param is 5 words: [name, type_name, is_mut_ref, is_ref, is_ptr]
        let param = params[[i]]
        let param_name: &str = param[[0]]
        let param_type: &str = param[[1]]
        add_local(ctx, param_name, 0, 0, param_type)
        i = i + 1
    }

    // Scan body for locals
    scan_locals(ctx, body)

    // Round stack to 16-byte alignment
    let stack_size: i32 = ctx[[9]]
    ctx[[9]] = (stack_size + 15) & ~15
    let aligned_stack: i32 = ctx[[9]]

    // Prologue
    fprintf(out, "    .globl %s\n", name)
    fprintf(out, "    .type %s, @function\n", name)
    fprintf(out, "%s:\n", name)
    fprintf(out, "    pushq   %%rbp\n")
    fprintf(out, "    movq    %%rsp, %%rbp\n")
    if aligned_stack > 0 {
        fprintf(out, "    subq    $%d, %%rsp\n", aligned_stack)
    }

    // Copy params from registers to stack
    i = 0
    while i < nparams && i < MAX_REG_ARGS {
        fprintf(out, "    movq    %s, %d(%%rbp)\n", get_arg_reg(i), local_offset(ctx, i))
        i = i + 1
    }

    // Emit body
    let nstmts: i32 = body.d1
    let mut last_is_expr: i32 = 0
    i = 0
    while i < nstmts {
        let s: *AstNode = body.d0[[i]]
        if i == nstmts - 1 && s.kind == NodeKind::EXPR_STMT {
            emit_expr(ctx, s.d0)
            last_is_expr = 1
        } else {
            emit_stmt(ctx, s)
        }
        i = i + 1
    }

    // Epilogue
    if last_is_expr == 0 {
        fprintf(out, "    xorl    %%eax, %%eax\n")
    }
    fprintf(out, "    leave\n")
    fprintf(out, "    ret\n")
}

fn codegen(program: *AstNode, out: *u8) {
    let ctx = ctx_new(out)

    let decls = program.d0
    let ndecls: i32 = program.d1

    // First pass: register structs and enums
    let mut i: i32 = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::STRUCT_DEF {
            let nstructs: i32 = ctx[[17]]
            if nstructs < MAX_STRUCTS {
                let snames = ctx[[13]]
                let sfields = ctx[[14]]
                let sftypes = ctx[[15]]
                let snfields = ctx[[16]]
                snames[[nstructs]] = d.d0  // name
                let nf: i32 = d.d2  // nfields
                snfields[[nstructs]] = nf
                // d1 = fields array, each field is 2 words (name, type)
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
                ctx[[17]] = nstructs + 1
            }
        }
        if d.kind == NodeKind::ENUM_DEF {
            let nenums: i32 = ctx[[21]]
            if nenums < MAX_ENUMS {
                let enames = ctx[[18]]
                let evariants = ctx[[19]]
                let envariants = ctx[[20]]
                enames[[nenums]] = d.d0  // name
                let nv: i32 = d.d2  // nvariants
                envariants[[nenums]] = nv
                // d1 = variants array (array of name pointers)
                let vars = malloc(nv * 8)
                let mut j: i32 = 0
                while j < nv {
                    let src = d.d1
                    vars[[j]] = src[[j]]
                    j = j + 1
                }
                evariants[[nenums]] = vars
                ctx[[21]] = nenums + 1
            }
        }
        i = i + 1
    }

    // Register top-level let as globals
    i = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::LET {
            // Read extra: [is_mut, is_buffer, type_name]
            let extra_ptr = d + 40
            let is_buffer: i32 = extra_ptr[[1]]
            let type_name: &str = extra_ptr[[2]]
            let mut is_arr: i32 = is_buffer
            if type_name != 0 && type_name[0] == 91 { is_arr = 1 }  // '['
            register_global(ctx, d.d0, is_arr)
        }
        i = i + 1
    }

    fprintf(out, "    .text\n")

    // Second pass: emit functions
    i = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::FN_DEF {
            emit_fn(ctx, d)
        } else if d.kind == NodeKind::ANNOTATION {
            if d.d2 != 0 {
                let child: *AstNode = d.d2
                if child.kind == NodeKind::FN_DEF {
                    emit_fn(ctx, child)
                }
            }
        }
        i = i + 1
    }

    // Emit string constants
    let nstrings: i32 = ctx[[2]]
    if nstrings > 0 {
        fprintf(out, "\n    .section .rodata\n")
        let strings = ctx[[1]]
        let mut j: i32 = 0
        while j < nstrings {
            fprintf(out, ".LC%d:\n", j)
            // Emit string with escape handling
            let s: &str = strings[[j]]
            fprintf(out, "    .string \"")
            let mut k: i32 = 0
            while s[k] != 0 {
                let ch = s[k]
                if ch == 10 { fprintf(out, "\\n") }
                else if ch == 9 { fprintf(out, "\\t") }
                else if ch == 13 { fprintf(out, "\\r") }
                else if ch == 92 { fprintf(out, "\\\\") }
                else if ch == 34 { fprintf(out, "\\\"") }
                else { fputc(ch, out) }
                k = k + 1
            }
            fprintf(out, "\"\n")
            j = j + 1
        }
    }

    // Emit global variables
    let mut has_globals: i32 = 0
    i = 0
    while i < ndecls {
        let d: *AstNode = decls[[i]]
        if d.kind == NodeKind::LET && d.d1 != 0 {
            if has_globals == 0 {
                fprintf(out, "\n    .data\n")
                has_globals = 1
            }
            fprintf(out, "    .globl %s\n", d.d0)
            fprintf(out, "%s:\n", d.d0)
            let init: *AstNode = d.d1
            if init.kind == NodeKind::INT_LIT {
                fprintf(out, "    .quad %ld\n", init.d0)
            } else if init.kind == NodeKind::STRING_LIT {
                let id = add_string(ctx, init.d0)
                fprintf(out, "    .quad .LC%d\n", id)
            } else {
                fprintf(out, "    .quad 0\n")
            }
        }
        i = i + 1
    }

    fprintf(out, "\n    .section .note.GNU-stack,\"\",@progbits\n")

    // Cleanup
    free(ctx[[1]])  // strings
    free(ctx[[4]])  // local_names
    free(ctx[[5]])  // local_offsets
    free(ctx[[6]])  // local_is_buffer
    free(ctx[[7]])  // local_type_name
    free(ctx[[13]])  // struct_names
    free(ctx[[14]])  // struct_fields
    free(ctx[[15]])  // struct_field_types
    free(ctx[[16]])  // struct_nfields
    free(ctx[[18]])  // enum_names
    free(ctx[[19]])  // enum_variants
    free(ctx[[20]])  // enum_nvariants
    free(ctx[[22]])  // global_names
    free(ctx[[23]])  // global_is_array
    free(ctx)
}
