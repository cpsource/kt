#include "types.kth"
// ast.kt — AST node constructor

fn ast_new(a: &mut Arena, kind: NodeKind, loc: SrcLoc) -> *AstNode {
    let n: *AstNode = arena_alloc(a, 56, 8)
    memset(n, 0, 56)
    n.kind = kind
    n.loc = loc
    return n
}
