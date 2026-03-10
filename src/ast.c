#include "ast.h"
#include <string.h>

AstNode *ast_new(Arena *a, NodeKind kind, SrcLoc loc) {
    AstNode *n = arena_alloc(a, sizeof(AstNode), _Alignof(AstNode));
    memset(n, 0, sizeof(AstNode));
    n->kind = kind;
    n->loc = loc;
    return n;
}
