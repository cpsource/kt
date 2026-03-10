#include "types.kth"
// parser.kt — Simplified recursive descent parser for kt-src subset

fn next(p: &mut Parser) {
    p.cur = lexer_next(p.lexer)
}

fn expect(p: &mut Parser, kind: TokenKind, what: &str) -> Token {
    if p.cur.kind != kind {
        error_at(p.cur.loc, what)
    }
    let t = p.cur
    next(p)
    return t
}

fn parse_expr(p: &mut Parser) -> *AstNode {
    // String literal
    if p.cur.kind == TokenKind::STRING {
        let n = ast_new(p.arena, NodeKind::STRING_LIT, p.cur.loc)
        n.d0 = arena_strndup(p.arena, p.cur.text, p.cur.len)
        next(p)
        return n
    }

    // Integer literal
    if p.cur.kind == TokenKind::INT {
        let n = ast_new(p.arena, NodeKind::INT_LIT, p.cur.loc)
        n.d1 = arena_strndup(p.arena, p.cur.text, p.cur.len)
        n.d0 = strtol(n.d1)
        next(p)
        return n
    }

    // Identifier or call
    if p.cur.kind == TokenKind::IDENT {
        let name = p.cur
        next(p)

        // Call: name(args)
        if p.cur.kind == TokenKind::LPAREN {
            next(p)
            let n = ast_new(p.arena, NodeKind::CALL, name.loc)
            n.d0 = arena_strndup(p.arena, name.text, name.len)

            let mut cap: i32 = 4
            let mut nargs: i32 = 0
            let mut args = arena_alloc(p.arena, cap * 8, 8)

            while p.cur.kind != TokenKind::RPAREN {
                if nargs > 0 { expect(p, TokenKind::COMMA, "','") }
                if nargs >= cap {
                    let newcap = cap * 2
                    let newargs = arena_alloc(p.arena, newcap * 8, 8)
                    memcpy(newargs, args, nargs * 8)
                    args = newargs
                    cap = newcap
                }
                args[[nargs]] = parse_expr(p)
                nargs = nargs + 1
            }
            expect(p, TokenKind::RPAREN, "')'")
            n.d1 = args
            n.d2 = nargs
            return n
        }

        // Plain identifier
        let n = ast_new(p.arena, NodeKind::IDENT, name.loc)
        n.d0 = arena_strndup(p.arena, name.text, name.len)
        return n
    }

    error_at(p.cur.loc, "unexpected token in expression")
}

fn parse_block(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::LBRACE, "'{'")

    let mut cap: i32 = 8
    let mut nstmts: i32 = 0
    let mut stmts = arena_alloc(p.arena, cap * 8, 8)

    while p.cur.kind != TokenKind::RBRACE && p.cur.kind != TokenKind::EOF {
        let mut stmt: *AstNode

        if p.cur.kind == TokenKind::RETURN {
            let rl = p.cur.loc
            next(p)
            if p.cur.kind == TokenKind::RBRACE {
                stmt = ast_new(p.arena, NodeKind::RETURN, rl)
                stmt.d0 = 0
            } else {
                let expr = parse_expr(p)
                stmt = ast_new(p.arena, NodeKind::RETURN, rl)
                stmt.d0 = expr
            }
        } else if p.cur.kind == TokenKind::LET {
            let ll = p.cur.loc
            next(p)
            let name = expect(p, TokenKind::IDENT, "variable name")
            expect(p, TokenKind::EQ, "'='")
            if p.cur.kind == TokenKind::LBRACKET {
                // Buffer: let x = [N]
                next(p)
                let size_tok = expect(p, TokenKind::INT, "buffer size")
                expect(p, TokenKind::RBRACKET, "']'")
                stmt = ast_new(p.arena, NodeKind::LET, ll)
                stmt.d0 = arena_strndup(p.arena, name.text, name.len)
                stmt.d1 = strtol(size_tok.text)
            } else {
                // Regular: let x = expr
                let init = parse_expr(p)
                stmt = ast_new(p.arena, NodeKind::LET, ll)
                stmt.d0 = arena_strndup(p.arena, name.text, name.len)
                stmt.d1 = init
            }
        } else {
            // Expression statement
            let expr = parse_expr(p)
            stmt = ast_new(p.arena, NodeKind::EXPR_STMT, expr.loc)
            stmt.d0 = expr
        }

        if nstmts >= cap {
            let newcap = cap * 2
            let ns = arena_alloc(p.arena, newcap * 8, 8)
            memcpy(ns, stmts, nstmts * 8)
            stmts = ns
            cap = newcap
        }
        stmts[[nstmts]] = stmt
        nstmts = nstmts + 1
    }

    expect(p, TokenKind::RBRACE, "'}'")
    let block = ast_new(p.arena, NodeKind::BLOCK, sl)
    block.d0 = stmts
    block.d1 = nstmts
    return block
}

fn parse_annotation(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::AT, "'@'")
    let name = expect(p, TokenKind::IDENT, "annotation name")
    expect(p, TokenKind::LPAREN, "'('")
    let prompt = expect(p, TokenKind::STRING, "annotation string")
    expect(p, TokenKind::RPAREN, "')'")
    let ann = ast_new(p.arena, NodeKind::ANNOTATION, sl)
    ann.d0 = arena_strndup(p.arena, name.text, name.len)
    ann.d1 = arena_strndup(p.arena, prompt.text, prompt.len)
    return ann
}

fn parse_fn_def(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::FN, "'fn'")
    let name = expect(p, TokenKind::IDENT, "function name")
    expect(p, TokenKind::LPAREN, "'('")
    // Skip parameters until closing paren
    while p.cur.kind != TokenKind::RPAREN && p.cur.kind != TokenKind::EOF {
        next(p)
    }
    expect(p, TokenKind::RPAREN, "')'")
    // Skip optional return type: -> Type
    if p.cur.kind == TokenKind::IDENT {
        // Could be start of block or return type; blocks start with {
        // So if it's not {, skip tokens until we see {
    }
    let body = parse_block(p)
    let f = ast_new(p.arena, NodeKind::FN_DEF, sl)
    f.d0 = arena_strndup(p.arena, name.text, name.len)
    f.d1 = body
    return f
}

fn parse(l: &mut Lexer, a: &mut Arena) -> *AstNode {
    let mut p = Parser { lexer: l, arena: a, cur: lexer_next(l) }

    let mut cap: i32 = 16
    let mut ndecls: i32 = 0
    let mut decls = arena_alloc(a, cap * 8, 8)

    while p.cur.kind != TokenKind::EOF {
        let mut decl: *AstNode

        if p.cur.kind == TokenKind::AT {
            let ann = parse_annotation(p)
            let f = parse_fn_def(p)
            ann.d2 = f
            decl = ann
        } else if p.cur.kind == TokenKind::FN {
            decl = parse_fn_def(p)
        } else {
            error_at(p.cur.loc, "expected function or annotation")
        }

        if ndecls >= cap {
            let newcap = cap * 2
            let nd = arena_alloc(a, newcap * 8, 8)
            memcpy(nd, decls, ndecls * 8)
            decls = nd
            cap = newcap
        }
        decls[[ndecls]] = decl
        ndecls = ndecls + 1
    }

    let prog = ast_new(a, NodeKind::PROGRAM, SrcLoc { file: l.file, line: 1, col: 1 })
    prog.d0 = decls
    prog.d1 = ndecls
    return prog
}
