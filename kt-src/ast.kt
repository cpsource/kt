#include "types.kth"
// ast.kt — AST node constructor

fn ast_new(a: &mut Arena, kind: NodeKind, loc: SrcLoc) -> *AstNode {
    let n = arena_alloc(a, 40, 8)
    memset(n, 0, 40)
    n.kind = kind
    n.loc = loc
    return n
}
