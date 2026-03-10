#include "types.kth"
// check.kt — Static analysis: escape analysis for stack locals

let mut check_locals: [&str; 64]
let mut check_nlocals: i32 = 0

fn collect_locals(block: *AstNode) {
    if block == 0 { return }
    if block.kind != NodeKind::BLOCK { return }
    let mut i: i32 = 0
    while i < block.d1 {  // block.nstmts
        let stmt = block.d0[[i]]  // block.stmts
        if stmt.kind == NodeKind::LET {
            if check_nlocals < 64 {
                check_locals[[check_nlocals]] = stmt.d0  // let.name
                check_nlocals = check_nlocals + 1
            }
        }
        i = i + 1
    }
}

fn is_local(name: &str) -> bool {
    let mut i: i32 = 0
    while i < check_nlocals {
        if streq(check_locals[[i]], name) {
            return true
        }
        i = i + 1
    }
    return false
}

fn check_return_expr(expr: *AstNode, ret_loc: SrcLoc) {
    if expr == 0 { return }
    if expr.kind == 23 && is_local(expr.d0) {  // IDENT, ident.name
        error_at(ret_loc,
            "returning pointer to stack-allocated variable — memory will be invalid after function returns")
    }
}

fn check_fn(f: *AstNode) {
    let body = f.d1  // fn_def.body
    if body == 0 { return }
    if body.kind != NodeKind::BLOCK { return }

    check_nlocals = 0
    collect_locals(body)
    if check_nlocals == 0 { return }

    let mut i: i32 = 0
    while i < body.d1 {  // block.nstmts
        let stmt = body.d0[[i]]  // block.stmts
        if stmt.kind == 6 {  // RETURN
            check_return_expr(stmt.d0, stmt.loc)  // return.expr
        }
        i = i + 1
    }
}

fn check_escape(program: *AstNode) {
    let mut i: i32 = 0
    while i < program.d1 {  // program.ndecls
        let decl = program.d0[[i]]  // program.decls
        if decl.kind == 1 {  // FN_DEF
            check_fn(decl)
        } else if decl.kind == 27 {  // ANNOTATION
            let child = decl.d2  // annotation.child
            if child != 0 && child.kind == 1 {  // FN_DEF
                check_fn(child)
            }
        }
        i = i + 1
    }
}
