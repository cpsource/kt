#include "types.kth"
// check.kt — Static analysis: escape analysis for stack locals

let mut check_locals: [&str; 64]
let mut check_nlocals: i32 = 0

fn collect_locals(block: *AstNode) {
    if block == 0 { return }
    if block.kind != NodeKind::BLOCK { return }
    let mut i: i32 = 0
    while i < block.d1 {
        let stmt: *AstNode = block.d0[[i]]
        if stmt.kind == NodeKind::LET {
            // d2 > 0 means buffer
            if stmt.d2 > 0 {
                if check_nlocals < 64 {
                    check_locals[[check_nlocals]] = stmt.d0
                    check_nlocals = check_nlocals + 1
                }
            }
        }
        i = i + 1
    }
}

fn is_local(name: &str) -> i32 {
    let mut i: i32 = 0
    while i < check_nlocals {
        if streq(check_locals[[i]], name) {
            return 1
        }
        i = i + 1
    }
    return 0
}

fn check_return_expr(expr: *AstNode, ret_loc: SrcLoc) {
    if expr == 0 { return }
    if expr.kind == NodeKind::IDENT && is_local(expr.d0) {
        error_at(ret_loc, "returning pointer to stack-allocated variable")
    }
}

fn check_block(block: *AstNode) {
    if block == 0 { return }
    if block.kind != NodeKind::BLOCK { return }
    let mut i: i32 = 0
    while i < block.d1 {
        let stmt: *AstNode = block.d0[[i]]
        if stmt.kind == NodeKind::RETURN {
            check_return_expr(stmt.d0, stmt.loc)
        }
        i = i + 1
    }
}

fn check_fn(f: *AstNode) {
    let body: *AstNode = f.d1
    if body == 0 { return }
    if body.kind != NodeKind::BLOCK { return }

    check_nlocals = 0
    collect_locals(body)
    if check_nlocals == 0 { return }

    check_block(body)
}

fn check_escape(program: *AstNode) {
    let mut i: i32 = 0
    while i < program.d1 {
        let decl: *AstNode = program.d0[[i]]
        if decl.kind == NodeKind::FN_DEF {
            check_fn(decl)
        } else if decl.kind == NodeKind::ANNOTATION {
            let child: *AstNode = decl.d2
            if child != 0 && child.kind == NodeKind::FN_DEF {
                check_fn(child)
            }
        }
        i = i + 1
    }
}
