#include "types.kth"
// parser.kt — Recursive descent parser with Pratt expression parsing

fn next(p: &mut Parser) {
    p.cur = lexer_next(p.lexer)
}

fn check(p: &Parser, k: TokenKind) -> i32 {
    return p.cur.kind == k
}

fn expect(p: &mut Parser, kind: TokenKind, what: &str) -> Token {
    if p.cur.kind != kind {
        error_at(p.cur.loc, what)
    }
    let t = p.cur
    next(p)
    return t
}

fn match_tok(p: &mut Parser, k: TokenKind) -> i32 {
    if p.cur.kind == k {
        next(p)
        return 1
    }
    return 0
}

// ---- Type parsing (consume and return as string) ----

fn parse_type(p: &mut Parser) -> &str {
    let mut buf = malloc(256)
    let mut len: i32 = 0

    // Handle &, &mut, * prefixes
    if check(p, TokenKind::AMP) {
        next(p)
        if check(p, TokenKind::MUT) {
            next(p)
            let inner = parse_type(p)
            len = snprintf(buf, 256, "&mut %s", inner)
            return buf
        }
        let inner = parse_type(p)
        len = snprintf(buf, 256, "&%s", inner)
        return buf
    }
    if check(p, TokenKind::STAR) {
        next(p)
        let inner = parse_type(p)
        len = snprintf(buf, 256, "*%s", inner)
        return buf
    }

    // [T; N] array type
    if check(p, TokenKind::LBRACKET) {
        next(p)
        let elem = parse_type(p)
        // Accept , or ; (semicolons are skipped by lexer)
        if check(p, TokenKind::COMMA) { next(p) }
        let size = expect(p, TokenKind::INT, "array size")
        expect(p, TokenKind::RBRACKET, "']'")
        let size_str = arena_strndup(p.arena, size.text, size.len)
        len = snprintf(buf, 256, "[%s;%s]", elem, size_str)
        return buf
    }

    let name = expect(p, TokenKind::IDENT, "type name")
    free(buf)
    return arena_strndup(p.arena, name.text, name.len)
}

// ---- Precedence for Pratt parser ----

fn prefix_bp(k: TokenKind) -> i32 {
    if k == TokenKind::MINUS || k == TokenKind::NOT || k == TokenKind::TILDE || k == TokenKind::STAR || k == TokenKind::AMP {
        return 21
    }
    return -1
}

fn infix_bp_left(k: TokenKind) -> i32 {
    if k == TokenKind::OR { return 2 }
    if k == TokenKind::AND { return 3 }
    if k == TokenKind::PIPE { return 4 }
    if k == TokenKind::CARET { return 5 }
    if k == TokenKind::AMP { return 6 }
    if k == TokenKind::EQEQ || k == TokenKind::NEQ { return 7 }
    if k == TokenKind::LT || k == TokenKind::GT || k == TokenKind::LTEQ || k == TokenKind::GTEQ { return 8 }
    if k == TokenKind::SHL || k == TokenKind::SHR { return 9 }
    if k == TokenKind::PLUS || k == TokenKind::MINUS { return 10 }
    if k == TokenKind::STAR || k == TokenKind::SLASH || k == TokenKind::PERCENT { return 11 }
    return -1
}

fn infix_bp_right(k: TokenKind) -> i32 {
    let l = infix_bp_left(k)
    if l >= 0 { return l + 1 }
    return -1
}

// Map token to BinOp integer (matching C enum order)
//   OP_ADD=0, OP_SUB=1, OP_MUL=2, OP_DIV=3, OP_MOD=4,
//   OP_EQ=5, OP_NEQ=6, OP_LT=7, OP_GT=8, OP_LTEQ=9, OP_GTEQ=10,
//   OP_AND=11, OP_OR=12,
//   OP_BIT_AND=13, OP_BIT_OR=14, OP_BIT_XOR=15, OP_SHL=16, OP_SHR=17

fn tok_to_binop(k: TokenKind) -> i32 {
    if k == TokenKind::PLUS { return 0 }
    if k == TokenKind::MINUS { return 1 }
    if k == TokenKind::STAR { return 2 }
    if k == TokenKind::SLASH { return 3 }
    if k == TokenKind::PERCENT { return 4 }
    if k == TokenKind::EQEQ { return 5 }
    if k == TokenKind::NEQ { return 6 }
    if k == TokenKind::LT { return 7 }
    if k == TokenKind::GT { return 8 }
    if k == TokenKind::LTEQ { return 9 }
    if k == TokenKind::GTEQ { return 10 }
    if k == TokenKind::AND { return 11 }
    if k == TokenKind::OR { return 12 }
    if k == TokenKind::AMP { return 13 }
    if k == TokenKind::PIPE { return 14 }
    if k == TokenKind::CARET { return 15 }
    if k == TokenKind::SHL { return 16 }
    if k == TokenKind::SHR { return 17 }
    return 0
}

// ---- Expression parsing ----

fn parse_expr(p: &mut Parser) -> *AstNode {
    return parse_expr_bp(p, 0)
}

fn parse_postfix(p: &mut Parser, left: *AstNode) -> *AstNode {
    let mut result = left

    while 1 {
        if check(p, TokenKind::DOT) {
            let sl = p.cur.loc
            next(p)
            let field = expect(p, TokenKind::IDENT, "field name")

            // Method call: expr.method(args)
            if check(p, TokenKind::LPAREN) {
                next(p)
                let n = ast_new(p.arena, NodeKind::CALL, sl)
                n.d0 = arena_strndup(p.arena, field.text, field.len)
                let mut cap: i32 = 4
                let mut nargs: i32 = 1
                let mut args = arena_alloc(p.arena, cap * 8, 8)
                args[[0]] = result  // self
                while check(p, TokenKind::RPAREN) == 0 {
                    if nargs > 1 { expect(p, TokenKind::COMMA, "','") }
                    if nargs >= cap {
                        let nc = cap * 2
                        let na = arena_alloc(p.arena, nc * 8, 8)
                        memcpy(na, args, nargs * 8)
                        args = na
                        cap = nc
                    }
                    args[[nargs]] = parse_expr(p)
                    nargs = nargs + 1
                }
                expect(p, TokenKind::RPAREN, "')'")
                n.d1 = args
                n.d2 = nargs
                result = n
                continue
            }

            let n = ast_new(p.arena, NodeKind::MEMBER, sl)
            n.d0 = result
            n.d1 = arena_strndup(p.arena, field.text, field.len)
            result = n
            continue
        }

        if check(p, TokenKind::LBRACKET) {
            let sl = p.cur.loc
            next(p)
            let mut is_word: i32 = 0
            if check(p, TokenKind::LBRACKET) {
                next(p)
                is_word = 1
            }
            let idx = parse_expr(p)
            expect(p, TokenKind::RBRACKET, "']'")
            if is_word { expect(p, TokenKind::RBRACKET, "']]'") }
            let n = ast_new(p.arena, NodeKind::INDEX, sl)
            n.d0 = result
            n.d1 = idx
            n.d2 = is_word
            result = n
            continue
        }

        break
    }
    return result
}

fn parse_primary(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc

    // Prefix: unary minus
    if check(p, TokenKind::MINUS) {
        next(p)
        let operand = parse_expr_bp(p, prefix_bp(TokenKind::MINUS))
        // Optimize: -literal -> negative literal
        if operand.kind == NodeKind::INT_LIT {
            operand.d0 = 0 - operand.d0
            return operand
        }
        let n = ast_new(p.arena, NodeKind::UNARY, sl)
        n.d0 = 0  // UNOP_NEG
        n.d1 = operand
        return n
    }

    // Prefix: !
    if check(p, TokenKind::NOT) {
        next(p)
        let n = ast_new(p.arena, NodeKind::UNARY, sl)
        n.d0 = 1  // UNOP_NOT
        n.d1 = parse_expr_bp(p, prefix_bp(TokenKind::NOT))
        return n
    }

    // Prefix: ~
    if check(p, TokenKind::TILDE) {
        next(p)
        let n = ast_new(p.arena, NodeKind::UNARY, sl)
        n.d0 = 2  // UNOP_BIT_NOT
        n.d1 = parse_expr_bp(p, prefix_bp(TokenKind::TILDE))
        return n
    }

    // Prefix: * (deref)
    if check(p, TokenKind::STAR) {
        next(p)
        let n = ast_new(p.arena, NodeKind::UNARY, sl)
        n.d0 = 3  // UNOP_DEREF
        n.d1 = parse_expr_bp(p, prefix_bp(TokenKind::STAR))
        return n
    }

    // Prefix: & / &mut (addr_of)
    if check(p, TokenKind::AMP) {
        next(p)
        let mut is_mut: i32 = 0
        if check(p, TokenKind::MUT) { next(p); is_mut = 1 }
        let n = ast_new(p.arena, NodeKind::ADDR_OF, sl)
        n.d0 = parse_expr_bp(p, prefix_bp(TokenKind::AMP))
        n.d1 = is_mut
        return n
    }

    // Parenthesized expression
    if check(p, TokenKind::LPAREN) {
        next(p)
        let e = parse_expr(p)
        expect(p, TokenKind::RPAREN, "')'")
        return e
    }

    // Block expression
    if check(p, TokenKind::LBRACE) {
        return parse_block(p)
    }

    // If expression
    if check(p, TokenKind::IF) {
        next(p)
        let n = ast_new(p.arena, NodeKind::IF, sl)
        n.d0 = parse_expr(p)
        n.d1 = parse_block(p)
        n.d2 = 0
        if check(p, TokenKind::ELSE) {
            next(p)
            if check(p, TokenKind::IF) {
                n.d2 = parse_primary(p)
            } else {
                n.d2 = parse_block(p)
            }
        }
        return n
    }

    // String literal
    if check(p, TokenKind::STRING) {
        let n = ast_new(p.arena, NodeKind::STRING_LIT, sl)
        n.d0 = arena_strndup(p.arena, p.cur.text, p.cur.len)
        next(p)
        return n
    }

    // Integer literal
    if check(p, TokenKind::INT) {
        let n = ast_new(p.arena, NodeKind::INT_LIT, sl)
        n.d1 = arena_strndup(p.arena, p.cur.text, p.cur.len)
        // Handle hex, binary, octal
        if p.cur.len > 2 && p.cur.text[0] == 48 {
            if p.cur.text[1] == 120 {
                n.d0 = strtol_base(n.d1, 16)
            } else if p.cur.text[1] == 98 {
                n.d0 = strtol_base(n.d1 + 2, 2)
            } else if p.cur.text[1] == 111 {
                n.d0 = strtol_base(n.d1 + 2, 8)
            } else {
                n.d0 = kt_strtol(n.d1)
            }
        } else {
            n.d0 = kt_strtol(n.d1)
        }
        next(p)
        return n
    }

    // Boolean literals
    if check(p, TokenKind::TRUE) {
        next(p)
        let n = ast_new(p.arena, NodeKind::BOOL_LIT, sl)
        n.d0 = 1
        return n
    }
    if check(p, TokenKind::FALSE) {
        next(p)
        let n = ast_new(p.arena, NodeKind::BOOL_LIT, sl)
        n.d0 = 0
        return n
    }

    // Identifier, call, path, struct literal
    if check(p, TokenKind::IDENT) {
        let name = p.cur
        next(p)

        // Path: Ident::Ident
        if check(p, TokenKind::COLONCOLON) {
            next(p)
            let member = expect(p, TokenKind::IDENT, "path member")
            let n = ast_new(p.arena, NodeKind::PATH, sl)
            n.d0 = arena_strndup(p.arena, name.text, name.len)
            n.d1 = arena_strndup(p.arena, member.text, member.len)
            return n
        }

        // Call: Ident(args)
        if check(p, TokenKind::LPAREN) {
            next(p)
            let n = ast_new(p.arena, NodeKind::CALL, name.loc)
            n.d0 = arena_strndup(p.arena, name.text, name.len)
            let mut cap: i32 = 4
            let mut nargs: i32 = 0
            let mut args = arena_alloc(p.arena, cap * 8, 8)
            while check(p, TokenKind::RPAREN) == 0 {
                if nargs > 0 { expect(p, TokenKind::COMMA, "','") }
                if nargs >= cap {
                    let nc = cap * 2
                    let na = arena_alloc(p.arena, nc * 8, 8)
                    memcpy(na, args, nargs * 8)
                    args = na
                    cap = nc
                }
                args[[nargs]] = parse_expr(p)
                nargs = nargs + 1
            }
            expect(p, TokenKind::RPAREN, "')'")
            n.d1 = args
            n.d2 = nargs
            return n
        }

        // Struct literal: Ident { field: value, ... }
        if check(p, TokenKind::LBRACE) {
            // Save lexer state for backtracking
            let save_pos = p.lexer.pos
            let save_line = p.lexer.line
            let save_col = p.lexer.col
            let save_cur = p.cur

            next(p)  // consume {
            if check(p, TokenKind::IDENT) {
                let f = p.cur
                next(p)
                if check(p, TokenKind::COLON) {
                    // It's a struct literal
                    next(p)  // consume :
                    let n = ast_new(p.arena, NodeKind::STRUCT_LIT, sl)
                    n.d0 = arena_strndup(p.arena, name.text, name.len)
                    let mut cap: i32 = 8
                    let mut nf: i32 = 0
                    let mut fnames = arena_alloc(p.arena, cap * 8, 8)
                    let mut fvals = arena_alloc(p.arena, cap * 8, 8)
                    // First field already consumed
                    fnames[[nf]] = arena_strndup(p.arena, f.text, f.len)
                    fvals[[nf]] = parse_expr(p)
                    nf = nf + 1
                    while match_tok(p, TokenKind::COMMA) {
                        if check(p, TokenKind::RBRACE) { break }
                        if nf >= cap {
                            let nc = cap * 2
                            let nn = arena_alloc(p.arena, nc * 8, 8)
                            let nv = arena_alloc(p.arena, nc * 8, 8)
                            memcpy(nn, fnames, nf * 8)
                            memcpy(nv, fvals, nf * 8)
                            fnames = nn
                            fvals = nv
                            cap = nc
                        }
                        let fn_tok = expect(p, TokenKind::IDENT, "field name")
                        expect(p, TokenKind::COLON, "':'")
                        fnames[[nf]] = arena_strndup(p.arena, fn_tok.text, fn_tok.len)
                        fvals[[nf]] = parse_expr(p)
                        nf = nf + 1
                    }
                    expect(p, TokenKind::RBRACE, "'}'")
                    n.d1 = fnames
                    n.d2 = fvals
                    n.d3 = nf
                    return n
                }
                // Not a struct literal — backtrack
                p.lexer.pos = save_pos
                p.lexer.line = save_line
                p.lexer.col = save_col
                p.cur = save_cur
            } else {
                // Not a struct literal — backtrack
                p.lexer.pos = save_pos
                p.lexer.line = save_line
                p.lexer.col = save_col
                p.cur = save_cur
            }
        }

        // Plain identifier
        let n = ast_new(p.arena, NodeKind::IDENT, name.loc)
        n.d0 = arena_strndup(p.arena, name.text, name.len)
        return n
    }

    error_at(p.cur.loc, "unexpected token in expression")
}

fn parse_expr_bp(p: &mut Parser, min_bp: i32) -> *AstNode {
    let mut left = parse_primary(p)
    left = parse_postfix(p, left)

    while 1 {
        let op = p.cur.kind
        let lbp = infix_bp_left(op)
        if lbp < 0 || lbp < min_bp { break }

        let sl = p.cur.loc
        next(p)  // consume operator

        let mut right = parse_expr_bp(p, infix_bp_right(op))
        right = parse_postfix(p, right)

        let n = ast_new(p.arena, NodeKind::BINOP, sl)
        n.d0 = tok_to_binop(op)
        n.d1 = left
        n.d2 = right
        left = n
    }
    return left
}

// ---- Statements ----

fn parse_block(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::LBRACE, "'{'")
    let mut cap: i32 = 8
    let mut nstmts: i32 = 0
    let mut stmts = arena_alloc(p.arena, cap * 8, 8)

    while check(p, TokenKind::RBRACE) == 0 && check(p, TokenKind::EOF) == 0 {
        let mut stmt: *AstNode = 0

        if check(p, TokenKind::RETURN) {
            let rl = p.cur.loc
            next(p)
            stmt = ast_new(p.arena, NodeKind::RETURN, rl)
            if check(p, TokenKind::RBRACE) {
                stmt.d0 = 0
            } else {
                stmt.d0 = parse_expr(p)
            }
        } else if check(p, TokenKind::LET) {
            let ll = p.cur.loc
            next(p)
            let mut is_mut: i32 = 0
            if check(p, TokenKind::MUT) { next(p); is_mut = 1 }
            let name = expect(p, TokenKind::IDENT, "variable name")
            stmt = ast_new(p.arena, NodeKind::LET, ll)
            stmt.d0 = arena_strndup(p.arena, name.text, name.len)
            stmt.d1 = 0  // init
            stmt.d2 = 0  // buffer_size (also encodes is_buffer and is_mut)

            // Optional type annotation
            let mut type_name: &str = 0
            if check(p, TokenKind::COLON) {
                next(p)
                type_name = parse_type(p)
            }

            // Optional initializer
            if match_tok(p, TokenKind::EQ) {
                // Buffer: let x = [N]
                if check(p, TokenKind::LBRACKET) {
                    next(p)
                    let sz = expect(p, TokenKind::INT, "buffer size")
                    expect(p, TokenKind::RBRACKET, "']'")
                    let sz_str = arena_strndup(p.arena, sz.text, sz.len)
                    stmt.d2 = kt_strtol(sz_str)
                    // Mark as buffer: d1 stays 0 (no init), d2 has size > 0
                    // We need to distinguish buffer from non-buffer.
                    // Convention: d2 > 0 means buffer with that size.
                    // For non-buffer let with init, d1 = init node, d2 = 0.
                } else {
                    stmt.d1 = parse_expr(p)
                }
            }

            stmt.d3 = is_mut
            stmt.d4 = type_name

        } else if check(p, TokenKind::WHILE) {
            let wl = p.cur.loc
            next(p)
            stmt = ast_new(p.arena, NodeKind::WHILE, wl)
            stmt.d0 = parse_expr(p)
            stmt.d1 = parse_block(p)
        } else if check(p, TokenKind::FOR) {
            let fl = p.cur.loc
            next(p)
            let var = expect(p, TokenKind::IDENT, "loop variable")
            expect(p, TokenKind::IN, "'in'")
            let start = parse_expr(p)
            expect(p, TokenKind::DOTDOT, "'..'")
            let end = parse_expr(p)
            stmt = ast_new(p.arena, NodeKind::FOR_RANGE, fl)
            stmt.d0 = arena_strndup(p.arena, var.text, var.len)
            stmt.d1 = start
            stmt.d2 = end
            let body = parse_block(p)
            stmt.d3 = body
        } else if check(p, TokenKind::MATCH) {
            let ml = p.cur.loc
            next(p)
            let expr = parse_expr(p)
            expect(p, TokenKind::LBRACE, "'{'")
            let mut acap: i32 = 8
            let mut narms: i32 = 0
            // Each MatchArm: 3 words (pattern, enum_name, body)
            let mut arms = arena_alloc(p.arena, acap * 24, 8)

            while check(p, TokenKind::RBRACE) == 0 {
                let pat = p.cur
                next(p)
                let mut pattern: &str = 0
                let mut enum_name: &str = 0
                if check(p, TokenKind::COLONCOLON) {
                    next(p)
                    let mem = expect(p, TokenKind::IDENT, "variant name")
                    enum_name = arena_strndup(p.arena, pat.text, pat.len)
                    pattern = arena_strndup(p.arena, mem.text, mem.len)
                } else {
                    pattern = arena_strndup(p.arena, pat.text, pat.len)
                    enum_name = 0
                }
                // Optional destructuring
                if check(p, TokenKind::LPAREN) {
                    next(p)
                    while check(p, TokenKind::RPAREN) == 0 && check(p, TokenKind::EOF) == 0 { next(p) }
                    match_tok(p, TokenKind::RPAREN)
                }
                expect(p, TokenKind::FAT_ARROW, "'=>'")
                let mut body: *AstNode = 0
                if check(p, TokenKind::LBRACE) {
                    body = parse_block(p)
                } else {
                    body = parse_expr(p)
                }
                match_tok(p, TokenKind::COMMA)

                if narms >= acap {
                    let nc = acap * 2
                    let na = arena_alloc(p.arena, nc * 24, 8)
                    memcpy(na, arms, narms * 24)
                    arms = na
                    acap = nc
                }
                // Store arm at arms + narms * 24
                let arm_ptr = arms + narms * 24
                arm_ptr[[0]] = pattern
                arm_ptr[[1]] = enum_name
                arm_ptr[[2]] = body
                narms = narms + 1
            }
            expect(p, TokenKind::RBRACE, "'}'")
            stmt = ast_new(p.arena, NodeKind::MATCH, ml)
            stmt.d0 = expr
            stmt.d1 = arms
            stmt.d2 = narms
        } else if check(p, TokenKind::IF) {
            stmt = parse_primary(p)
        } else if check(p, TokenKind::BREAK) {
            stmt = ast_new(p.arena, NodeKind::BREAK, p.cur.loc)
            next(p)
        } else if check(p, TokenKind::CONTINUE) {
            stmt = ast_new(p.arena, NodeKind::CONTINUE, p.cur.loc)
            next(p)
        } else {
            // Expression statement, possibly followed by = for assignment
            let expr: *AstNode = parse_expr(p)
            if check(p, TokenKind::EQ) {
                let al = p.cur.loc
                next(p)
                let value = parse_expr(p)
                stmt = ast_new(p.arena, NodeKind::ASSIGN, al)
                stmt.d0 = expr
                stmt.d1 = value
            } else {
                stmt = ast_new(p.arena, NodeKind::EXPR_STMT, expr.loc)
                stmt.d0 = expr
            }
        }

        if nstmts >= cap {
            let nc = cap * 2
            let ns = arena_alloc(p.arena, nc * 8, 8)
            memcpy(ns, stmts, nstmts * 8)
            stmts = ns
            cap = nc
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

// ---- Top-level declarations ----

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

fn parse_param(p: &mut Parser) -> *u8 {
    // Returns pointer to 5 words: [name, type_name, is_mut_ref, is_ref, is_ptr]
    let param = arena_alloc(p.arena, 40, 8)
    let name = expect(p, TokenKind::IDENT, "parameter name")
    param[[0]] = arena_strndup(p.arena, name.text, name.len)
    expect(p, TokenKind::COLON, "':'")
    param[[2]] = 0  // is_mut_ref
    param[[3]] = 0  // is_ref
    param[[4]] = 0  // is_ptr
    if check(p, TokenKind::AMP) {
        next(p)
        if check(p, TokenKind::MUT) {
            next(p)
            param[[2]] = 1
        } else {
            param[[3]] = 1
        }
        let tn = expect(p, TokenKind::IDENT, "type name")
        param[[1]] = arena_strndup(p.arena, tn.text, tn.len)
    } else if check(p, TokenKind::STAR) {
        next(p)
        param[[4]] = 1
        let tn = expect(p, TokenKind::IDENT, "type name")
        param[[1]] = arena_strndup(p.arena, tn.text, tn.len)
    } else {
        let tn = expect(p, TokenKind::IDENT, "type name")
        param[[1]] = arena_strndup(p.arena, tn.text, tn.len)
    }
    return param
}

fn parse_fn_def(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::FN, "'fn'")
    let name = expect(p, TokenKind::IDENT, "function name")
    expect(p, TokenKind::LPAREN, "'('")

    // Parse parameters
    let mut pcap: i32 = 4
    let mut nparams: i32 = 0
    let mut params = arena_alloc(p.arena, pcap * 8, 8)
    while check(p, TokenKind::RPAREN) == 0 {
        if nparams > 0 { expect(p, TokenKind::COMMA, "','") }
        if nparams >= pcap {
            let nc = pcap * 2
            let np = arena_alloc(p.arena, nc * 8, 8)
            memcpy(np, params, nparams * 8)
            params = np
            pcap = nc
        }
        params[[nparams]] = parse_param(p)
        nparams = nparams + 1
    }
    expect(p, TokenKind::RPAREN, "')'")

    // Optional return type
    let mut ret_type: &str = 0
    if check(p, TokenKind::ARROW) {
        next(p)
        ret_type = parse_type(p)
    }

    let body = parse_block(p)
    let f = ast_new(p.arena, NodeKind::FN_DEF, sl)
    f.d0 = arena_strndup(p.arena, name.text, name.len)
    f.d1 = body
    f.d2 = params
    f.d3 = nparams
    f.d4 = ret_type
    return f
}

fn parse_struct_def(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::STRUCT, "'struct'")
    let name = expect(p, TokenKind::IDENT, "struct name")
    expect(p, TokenKind::LBRACE, "'{'")

    let mut cap: i32 = 8
    let mut nfields: i32 = 0
    // Each field: 2 words (name, type_name)
    let mut fields = arena_alloc(p.arena, cap * 16, 8)

    while check(p, TokenKind::RBRACE) == 0 {
        if nfields >= cap {
            let nc = cap * 2
            let nf = arena_alloc(p.arena, nc * 16, 8)
            memcpy(nf, fields, nfields * 16)
            fields = nf
            cap = nc
        }
        let fn_tok = expect(p, TokenKind::IDENT, "field name")
        expect(p, TokenKind::COLON, "':'")
        let tn = parse_type(p)
        let field_ptr = fields + nfields * 16
        field_ptr[[0]] = arena_strndup(p.arena, fn_tok.text, fn_tok.len)
        field_ptr[[1]] = tn
        nfields = nfields + 1
        match_tok(p, TokenKind::COMMA)
    }
    expect(p, TokenKind::RBRACE, "'}'")
    let s = ast_new(p.arena, NodeKind::STRUCT_DEF, sl)
    s.d0 = arena_strndup(p.arena, name.text, name.len)
    s.d1 = fields
    s.d2 = nfields
    return s
}

fn parse_enum_def(p: &mut Parser) -> *AstNode {
    let sl = p.cur.loc
    expect(p, TokenKind::ENUM, "'enum'")
    let name = expect(p, TokenKind::IDENT, "enum name")
    expect(p, TokenKind::LBRACE, "'{'")

    let mut cap: i32 = 8
    let mut nvariants: i32 = 0
    // Each variant: 1 word (name) — we skip field_types for now
    let mut variants = arena_alloc(p.arena, cap * 8, 8)

    while check(p, TokenKind::RBRACE) == 0 {
        if nvariants >= cap {
            let nc = cap * 2
            let nv = arena_alloc(p.arena, nc * 8, 8)
            memcpy(nv, variants, nvariants * 8)
            variants = nv
            cap = nc
        }
        let vn = expect(p, TokenKind::IDENT, "variant name")
        variants[[nvariants]] = arena_strndup(p.arena, vn.text, vn.len)
        // Optional data fields
        if check(p, TokenKind::LPAREN) {
            next(p)
            while check(p, TokenKind::RPAREN) == 0 && check(p, TokenKind::EOF) == 0 {
                parse_type(p)
                match_tok(p, TokenKind::COMMA)
            }
            expect(p, TokenKind::RPAREN, "')'")
        }
        nvariants = nvariants + 1
        match_tok(p, TokenKind::COMMA)
    }
    expect(p, TokenKind::RBRACE, "'}'")
    let e = ast_new(p.arena, NodeKind::ENUM_DEF, sl)
    e.d0 = arena_strndup(p.arena, name.text, name.len)
    e.d1 = variants
    e.d2 = nvariants
    return e
}

fn parse(l: &mut Lexer, a: &mut Arena) -> *AstNode {
    let mut p = Parser { lexer: l, arena: a, cur: lexer_next(l) }

    let mut cap: i32 = 16
    let mut ndecls: i32 = 0
    let mut decls = arena_alloc(a, cap * 8, 8)

    while check(p, TokenKind::EOF) == 0 {
        let mut decl: *AstNode = 0

        if check(p, TokenKind::AT) {
            let ann = parse_annotation(p)
            let f = parse_fn_def(p)
            ann.d2 = f
            decl = ann
        } else if check(p, TokenKind::FN) {
            decl = parse_fn_def(p)
        } else if check(p, TokenKind::STRUCT) {
            decl = parse_struct_def(p)
        } else if check(p, TokenKind::ENUM) {
            decl = parse_enum_def(p)
        } else if check(p, TokenKind::LET) {
            // Top-level let
            let ll = p.cur.loc
            next(p)
            let mut is_mut: i32 = 0
            if check(p, TokenKind::MUT) { next(p); is_mut = 1 }
            let name = expect(p, TokenKind::IDENT, "variable name")
            decl = ast_new(a, NodeKind::LET, ll)
            decl.d0 = arena_strndup(a, name.text, name.len)
            decl.d1 = 0
            decl.d2 = 0

            let mut type_name: &str = 0
            if check(p, TokenKind::COLON) {
                next(p)
                type_name = parse_type(p)
            }
            if match_tok(p, TokenKind::EQ) {
                if check(p, TokenKind::LBRACKET) {
                    next(p)
                    let sz = expect(p, TokenKind::INT, "buffer size")
                    expect(p, TokenKind::RBRACKET, "']'")
                    let sz_str = arena_strndup(a, sz.text, sz.len)
                    decl.d2 = kt_strtol(sz_str)
                } else {
                    decl.d1 = parse_expr(p)
                }
            }
            decl.d3 = is_mut
            decl.d4 = type_name
        } else {
            error_at(p.cur.loc, "expected top-level declaration")
        }

        if ndecls >= cap {
            let nc = cap * 2
            let nd = arena_alloc(a, nc * 8, 8)
            memcpy(nd, decls, ndecls * 8)
            decls = nd
            cap = nc
        }
        decls[[ndecls]] = decl
        ndecls = ndecls + 1
    }

    let prog = ast_new(a, NodeKind::PROGRAM, SrcLoc { file: l.file, line: 1, col: 1 })
    prog.d0 = decls
    prog.d1 = ndecls
    return prog
}
