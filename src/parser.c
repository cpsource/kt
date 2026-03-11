#include "parser.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    Lexer *lexer;
    Arena *arena;
    Token cur;
} Parser;

static void next(Parser *p) { p->cur = lexer_next(p->lexer); }

static int check(Parser *p, TokenKind k) { return p->cur.kind == k; }

static Token expect(Parser *p, TokenKind kind, const char *what) {
    if (p->cur.kind != kind)
        error_at(p->cur.loc, "expected %s, got '%.*s'", what, p->cur.len, p->cur.text);
    Token t = p->cur;
    next(p);
    return t;
}

static int match(Parser *p, TokenKind k) {
    if (p->cur.kind == k) { next(p); return 1; }
    return 0;
}

/* ---- Type parsing (consume and return as string, mostly ignored in codegen) ---- */

static const char *parse_type(Parser *p) {
    char buf[256];
    int len = 0;

    /* Handle &, &mut, * prefixes */
    if (check(p, TOK_AMP)) {
        next(p);
        if (check(p, TOK_MUT)) {
            next(p);
            const char *inner = parse_type(p);
            len = snprintf(buf, sizeof(buf), "&mut %s", inner);
            return arena_strndup(p->arena, buf, len);
        }
        const char *inner = parse_type(p);
        len = snprintf(buf, sizeof(buf), "&%s", inner);
        return arena_strndup(p->arena, buf, len);
    }
    if (check(p, TOK_STAR)) {
        next(p);
        const char *inner = parse_type(p);
        len = snprintf(buf, sizeof(buf), "*%s", inner);
        return arena_strndup(p->arena, buf, len);
    }

    /* [T; N] array type */
    if (check(p, TOK_LBRACKET)) {
        next(p);
        const char *elem = parse_type(p);
        /* Accept either , or ; as array type separator: [T; N] or [T, N] */
        if (!match(p, TOK_COMMA)) {
            /* ; is skipped by lexer, so the next token is just the size */
        }
        Token size = expect(p, TOK_INT, "array size");
        expect(p, TOK_RBRACKET, "']'");
        len = snprintf(buf, sizeof(buf), "[%s;%.*s]", elem, size.len, size.text);
        return arena_strndup(p->arena, buf, len);
    }

    Token name = expect(p, TOK_IDENT, "type name");
    return arena_strndup(p->arena, name.text, name.len);
}

/* ---- Expression parsing (Pratt / precedence climbing) ---- */

static AstNode *parse_expr(Parser *p);
static AstNode *parse_expr_bp(Parser *p, int min_bp);
static AstNode *parse_block(Parser *p);

/* Precedence levels (binding power, higher = tighter) */
static int prefix_bp(TokenKind k) {
    switch (k) {
    case TOK_MINUS: case TOK_NOT: case TOK_TILDE: case TOK_AT: case TOK_AMP:
        return 21;
    default: return -1;
    }
}

static int infix_bp_left(TokenKind k) {
    switch (k) {
    case TOK_OR:      return 2;
    case TOK_AND:     return 3;
    case TOK_PIPE:    return 4;
    case TOK_CARET:   return 5;
    case TOK_AMP:     return 6;
    case TOK_EQEQ: case TOK_NEQ: return 7;
    case TOK_LT: case TOK_GT: case TOK_LTEQ: case TOK_GTEQ: return 8;
    case TOK_SHL: case TOK_SHR: return 9;
    case TOK_PLUS: case TOK_MINUS: return 10;
    case TOK_STAR: case TOK_SLASH: case TOK_PERCENT: return 11;
    default: return -1;
    }
}

static int infix_bp_right(TokenKind k) {
    /* Right binding power = left + 1 for left-assoc */
    int l = infix_bp_left(k);
    return l >= 0 ? l + 1 : -1;
}

static BinOp tok_to_binop(TokenKind k) {
    switch (k) {
    case TOK_PLUS: return OP_ADD;
    case TOK_MINUS: return OP_SUB;
    case TOK_STAR: return OP_MUL;
    case TOK_SLASH: return OP_DIV;
    case TOK_PERCENT: return OP_MOD;
    case TOK_EQEQ: return OP_EQ;
    case TOK_NEQ: return OP_NEQ;
    case TOK_LT: return OP_LT;
    case TOK_GT: return OP_GT;
    case TOK_LTEQ: return OP_LTEQ;
    case TOK_GTEQ: return OP_GTEQ;
    case TOK_AND: return OP_AND;
    case TOK_OR: return OP_OR;
    case TOK_AMP: return OP_BIT_AND;
    case TOK_PIPE: return OP_BIT_OR;
    case TOK_CARET: return OP_BIT_XOR;
    case TOK_SHL: return OP_SHL;
    case TOK_SHR: return OP_SHR;
    default: return OP_ADD; /* shouldn't reach */
    }
}

/* Parse primary (atoms + prefix) */
static AstNode *parse_primary(Parser *p) {
    SrcLoc sl = p->cur.loc;

    /* Prefix operators */
    if (check(p, TOK_MINUS)) {
        next(p);
        AstNode *operand = parse_expr_bp(p, prefix_bp(TOK_MINUS));
        /* Optimize: -literal → negative literal */
        if (operand->kind == NODE_INT_LIT) {
            operand->int_lit.value = -operand->int_lit.value;
            return operand;
        }
        AstNode *n = ast_new(p->arena, NODE_UNARY, sl);
        n->unary.op = UNOP_NEG;
        n->unary.operand = operand;
        return n;
    }
    if (check(p, TOK_NOT)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_UNARY, sl);
        n->unary.op = UNOP_NOT;
        n->unary.operand = parse_expr_bp(p, prefix_bp(TOK_NOT));
        return n;
    }
    if (check(p, TOK_TILDE)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_UNARY, sl);
        n->unary.op = UNOP_BIT_NOT;
        n->unary.operand = parse_expr_bp(p, prefix_bp(TOK_TILDE));
        return n;
    }
    if (check(p, TOK_AT)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_UNARY, sl);
        n->unary.op = UNOP_DEREF;
        n->unary.operand = parse_expr_bp(p, prefix_bp(TOK_AT));
        return n;
    }
    if (check(p, TOK_AMP)) {
        next(p);
        int is_mut = 0;
        if (check(p, TOK_MUT)) { next(p); is_mut = 1; }
        AstNode *n = ast_new(p->arena, NODE_ADDR_OF, sl);
        n->addr_of.operand = parse_expr_bp(p, prefix_bp(TOK_AMP));
        n->addr_of.is_mut = is_mut;
        return n;
    }

    /* Parenthesized expression */
    if (check(p, TOK_LPAREN)) {
        next(p);
        AstNode *e = parse_expr(p);
        expect(p, TOK_RPAREN, "')'");
        return e;
    }

    /* Block expression */
    if (check(p, TOK_LBRACE)) {
        return parse_block(p);
    }

    /* if expression */
    if (check(p, TOK_IF)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_IF, sl);
        n->if_.cond = parse_expr(p);
        n->if_.then_b = parse_block(p);
        n->if_.else_b = NULL;
        if (check(p, TOK_ELSE)) {
            next(p);
            if (check(p, TOK_IF)) {
                /* else if — parse as nested if expression */
                n->if_.else_b = parse_primary(p);
            } else {
                n->if_.else_b = parse_block(p);
            }
        }
        return n;
    }

    /* String literal */
    if (check(p, TOK_STRING)) {
        AstNode *n = ast_new(p->arena, NODE_STRING_LIT, sl);
        n->string_lit.value = arena_strndup(p->arena, p->cur.text, p->cur.len);
        next(p);
        return n;
    }

    /* Integer literal */
    if (check(p, TOK_INT)) {
        AstNode *n = ast_new(p->arena, NODE_INT_LIT, sl);
        n->int_lit.text = arena_strndup(p->arena, p->cur.text, p->cur.len);
        /* Handle hex, binary, octal */
        if (p->cur.len > 2 && p->cur.text[0] == '0') {
            if (p->cur.text[1] == 'x') n->int_lit.value = strtol(n->int_lit.text, NULL, 16);
            else if (p->cur.text[1] == 'b') n->int_lit.value = strtol(n->int_lit.text + 2, NULL, 2);
            else if (p->cur.text[1] == 'o') n->int_lit.value = strtol(n->int_lit.text + 2, NULL, 8);
            else n->int_lit.value = strtol(n->int_lit.text, NULL, 10);
        } else {
            n->int_lit.value = strtol(n->int_lit.text, NULL, 10);
        }
        next(p);
        return n;
    }

    /* Boolean literals */
    if (check(p, TOK_TRUE)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_BOOL_LIT, sl);
        n->bool_lit.value = 1;
        return n;
    }
    if (check(p, TOK_FALSE)) {
        next(p);
        AstNode *n = ast_new(p->arena, NODE_BOOL_LIT, sl);
        n->bool_lit.value = 0;
        return n;
    }

    /* Identifier, call, path, struct literal */
    if (check(p, TOK_IDENT)) {
        Token name = p->cur;
        next(p);

        /* Path: Ident::Ident */
        if (check(p, TOK_COLONCOLON)) {
            next(p);
            Token member = expect(p, TOK_IDENT, "path member");
            AstNode *n = ast_new(p->arena, NODE_PATH, sl);
            n->path.base = arena_strndup(p->arena, name.text, name.len);
            n->path.member = arena_strndup(p->arena, member.text, member.len);
            return n;
        }

        /* Call: Ident( args ) */
        if (check(p, TOK_LPAREN)) {
            next(p);
            AstNode *n = ast_new(p->arena, NODE_CALL, name.loc);
            n->call.name = arena_strndup(p->arena, name.text, name.len);
            int cap = 4, nargs = 0;
            AstNode **args = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));
            while (!check(p, TOK_RPAREN)) {
                if (nargs > 0) expect(p, TOK_COMMA, "','");
                if (nargs >= cap) {
                    int nc = cap * 2;
                    AstNode **na = arena_alloc(p->arena, nc * sizeof(AstNode *), _Alignof(AstNode *));
                    memcpy(na, args, nargs * sizeof(AstNode *));
                    args = na; cap = nc;
                }
                args[nargs++] = parse_expr(p);
            }
            expect(p, TOK_RPAREN, "')'");
            n->call.args = args;
            n->call.nargs = nargs;
            return n;
        }

        /* Struct literal: Ident { field: value, ... } */
        if (check(p, TOK_LBRACE)) {
            /* Peek ahead: if next is IDENT COLON, it's a struct literal.
             * Otherwise it could be a block. Save state to backtrack. */
            int save_pos = p->lexer->pos;
            int save_line = p->lexer->line;
            int save_col = p->lexer->col;
            Token save_cur = p->cur;

            next(p); /* consume { */
            if (check(p, TOK_IDENT)) {
                Token f = p->cur;
                next(p);
                if (check(p, TOK_COLON)) {
                    /* It's a struct literal — parse it */
                    next(p); /* consume : */
                    AstNode *n = ast_new(p->arena, NODE_STRUCT_LIT, sl);
                    n->struct_lit.name = arena_strndup(p->arena, name.text, name.len);
                    int cap = 8, nf = 0;
                    const char **fnames = arena_alloc(p->arena, cap * sizeof(char *), _Alignof(char *));
                    AstNode **fvals = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));
                    /* First field already consumed name and colon */
                    fnames[nf] = arena_strndup(p->arena, f.text, f.len);
                    fvals[nf] = parse_expr(p);
                    nf++;
                    while (match(p, TOK_COMMA)) {
                        if (check(p, TOK_RBRACE)) break;
                        if (nf >= cap) {
                            int nc = cap * 2;
                            const char **nn = arena_alloc(p->arena, nc * sizeof(char *), _Alignof(char *));
                            AstNode **nv = arena_alloc(p->arena, nc * sizeof(AstNode *), _Alignof(AstNode *));
                            memcpy(nn, fnames, nf * sizeof(char *));
                            memcpy(nv, fvals, nf * sizeof(AstNode *));
                            fnames = nn; fvals = nv; cap = nc;
                        }
                        Token fn_tok = expect(p, TOK_IDENT, "field name");
                        expect(p, TOK_COLON, "':'");
                        fnames[nf] = arena_strndup(p->arena, fn_tok.text, fn_tok.len);
                        fvals[nf] = parse_expr(p);
                        nf++;
                    }
                    expect(p, TOK_RBRACE, "'}'");
                    n->struct_lit.field_names = fnames;
                    n->struct_lit.field_values = fvals;
                    n->struct_lit.nfields = nf;
                    return n;
                }
                /* Not a struct literal — backtrack */
                p->lexer->pos = save_pos;
                p->lexer->line = save_line;
                p->lexer->col = save_col;
                p->cur = save_cur;
            } else {
                /* Empty braces or block — backtrack */
                p->lexer->pos = save_pos;
                p->lexer->line = save_line;
                p->lexer->col = save_col;
                p->cur = save_cur;
            }
        }

        /* Plain identifier */
        AstNode *n = ast_new(p->arena, NODE_IDENT, name.loc);
        n->ident.name = arena_strndup(p->arena, name.text, name.len);
        return n;
    }

    error_at(p->cur.loc, "unexpected token '%.*s'", p->cur.len, p->cur.text);
}

/* Parse postfix: member access, indexing, method calls */
static AstNode *parse_postfix(Parser *p, AstNode *left) {
    for (;;) {
        if (check(p, TOK_DOT)) {
            SrcLoc sl = p->cur.loc;
            next(p);
            Token field = expect(p, TOK_IDENT, "field name");

            /* Method call: expr.method(args) */
            if (check(p, TOK_LPAREN)) {
                next(p);
                AstNode *n = ast_new(p->arena, NODE_CALL, sl);
                /* Encode as a call with object as first arg */
                n->call.name = arena_strndup(p->arena, field.text, field.len);
                int cap = 4, nargs = 1;
                AstNode **args = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));
                args[0] = left;  /* self */
                while (!check(p, TOK_RPAREN)) {
                    if (nargs > 1) expect(p, TOK_COMMA, "','");
                    if (nargs >= cap) {
                        int nc = cap * 2;
                        AstNode **na = arena_alloc(p->arena, nc * sizeof(AstNode *), _Alignof(AstNode *));
                        memcpy(na, args, nargs * sizeof(AstNode *));
                        args = na; cap = nc;
                    }
                    args[nargs++] = parse_expr(p);
                }
                expect(p, TOK_RPAREN, "')'");
                n->call.args = args;
                n->call.nargs = nargs;
                left = n;
                continue;
            }

            AstNode *n = ast_new(p->arena, NODE_MEMBER, sl);
            n->member.object = left;
            n->member.field = arena_strndup(p->arena, field.text, field.len);
            left = n;
            continue;
        }

        if (check(p, TOK_LBRACKET)) {
            SrcLoc sl = p->cur.loc;
            next(p);
            int is_word = 0;
            if (check(p, TOK_LBRACKET)) {
                next(p);
                is_word = 1;
            }
            AstNode *idx = parse_expr(p);
            expect(p, TOK_RBRACKET, "']'");
            if (is_word) expect(p, TOK_RBRACKET, "']]'");
            AstNode *n = ast_new(p->arena, NODE_INDEX, sl);
            n->index_.object = left;
            n->index_.index = idx;
            n->index_.is_word = is_word;
            left = n;
            continue;
        }

        break;
    }
    return left;
}

/* Pratt expression parser */
static AstNode *parse_expr_bp(Parser *p, int min_bp) {
    AstNode *left = parse_primary(p);
    left = parse_postfix(p, left);

    for (;;) {
        TokenKind op = p->cur.kind;
        int lbp = infix_bp_left(op);
        if (lbp < 0 || lbp < min_bp) break;

        SrcLoc sl = p->cur.loc;
        next(p); /* consume operator */

        AstNode *right = parse_expr_bp(p, infix_bp_right(op));
        right = parse_postfix(p, right);

        AstNode *n = ast_new(p->arena, NODE_BINOP, sl);
        n->binop.op = tok_to_binop(op);
        n->binop.left = left;
        n->binop.right = right;
        left = n;
    }
    return left;
}

static AstNode *parse_expr(Parser *p) {
    return parse_expr_bp(p, 0);
}

/* ---- Statements ---- */

static AstNode *parse_block(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_LBRACE, "'{'");
    int cap = 8, nstmts = 0;
    AstNode **stmts = arena_alloc(p->arena, cap * sizeof(AstNode *), _Alignof(AstNode *));

    while (!check(p, TOK_RBRACE) && !check(p, TOK_EOF)) {
        AstNode *stmt = NULL;

        if (check(p, TOK_RETURN)) {
            SrcLoc rl = p->cur.loc;
            next(p);
            stmt = ast_new(p->arena, NODE_RETURN, rl);
            if (!check(p, TOK_RBRACE))
                stmt->ret.expr = parse_expr(p);
            else
                stmt->ret.expr = NULL;
        } else if (check(p, TOK_LET)) {
            SrcLoc ll = p->cur.loc;
            next(p);
            int is_mut = 0;
            if (check(p, TOK_MUT)) { next(p); is_mut = 1; }
            Token name = expect(p, TOK_IDENT, "variable name");
            stmt = ast_new(p->arena, NODE_LET, ll);
            stmt->let.name = arena_strndup(p->arena, name.text, name.len);
            stmt->let.is_mut = is_mut;
            stmt->let.type_name = NULL;
            stmt->let.is_buffer = 0;
            stmt->let.init = NULL;

            /* Optional type annotation */
            if (check(p, TOK_COLON)) {
                next(p);
                stmt->let.type_name = parse_type(p);
            }

            /* Optional initializer */
            if (match(p, TOK_EQ)) {
                /* Buffer: let x = [N] */
                if (check(p, TOK_LBRACKET)) {
                    next(p);
                    Token sz = expect(p, TOK_INT, "buffer size");
                    expect(p, TOK_RBRACKET, "']'");
                    stmt->let.is_buffer = 1;
                    stmt->let.buffer_size = (int)strtol(sz.text, NULL, 10);
                } else {
                    stmt->let.init = parse_expr(p);
                }
            }
        } else if (check(p, TOK_WHILE)) {
            SrcLoc wl = p->cur.loc;
            next(p);
            stmt = ast_new(p->arena, NODE_WHILE, wl);
            stmt->while_.cond = parse_expr(p);
            stmt->while_.body = parse_block(p);
        } else if (check(p, TOK_FOR)) {
            SrcLoc fl = p->cur.loc;
            next(p);
            Token var = expect(p, TOK_IDENT, "loop variable");
            expect(p, TOK_IN, "'in'");
            AstNode *start = parse_expr(p);
            expect(p, TOK_DOTDOT, "'..'");
            AstNode *end = parse_expr(p);
            stmt = ast_new(p->arena, NODE_FOR_RANGE, fl);
            stmt->for_range.var = arena_strndup(p->arena, var.text, var.len);
            stmt->for_range.start = start;
            stmt->for_range.end = end;
            stmt->for_range.body = parse_block(p);
        } else if (check(p, TOK_MATCH)) {
            SrcLoc ml = p->cur.loc;
            next(p);
            AstNode *expr = parse_expr(p);
            expect(p, TOK_LBRACE, "'{'");
            int acap = 8, narms = 0;
            MatchArm *arms = arena_alloc(p->arena, acap * sizeof(MatchArm), _Alignof(MatchArm));

            while (!check(p, TOK_RBRACE)) {
                MatchArm arm = {0};
                /* Pattern: Ident::Ident or Ident or literal */
                Token pat = p->cur;
                next(p);
                if (check(p, TOK_COLONCOLON)) {
                    next(p);
                    Token mem = expect(p, TOK_IDENT, "variant name");
                    arm.enum_name = arena_strndup(p->arena, pat.text, pat.len);
                    arm.pattern = arena_strndup(p->arena, mem.text, mem.len);
                } else {
                    arm.pattern = arena_strndup(p->arena, pat.text, pat.len);
                    arm.enum_name = NULL;
                }
                /* Optional destructuring (IDENT) — skip for now */
                if (check(p, TOK_LPAREN)) {
                    next(p);
                    while (!check(p, TOK_RPAREN) && !check(p, TOK_EOF)) next(p);
                    match(p, TOK_RPAREN);
                }
                expect(p, TOK_FAT_ARROW, "'=>'");
                /* Arm body: block or single expression */
                if (check(p, TOK_LBRACE)) {
                    arm.body = parse_block(p);
                } else {
                    arm.body = parse_expr(p);
                }
                /* Optional comma */
                match(p, TOK_COMMA);

                if (narms >= acap) {
                    int nc = acap * 2;
                    MatchArm *na = arena_alloc(p->arena, nc * sizeof(MatchArm), _Alignof(MatchArm));
                    memcpy(na, arms, narms * sizeof(MatchArm));
                    arms = na; acap = nc;
                }
                arms[narms++] = arm;
            }
            expect(p, TOK_RBRACE, "'}'");
            stmt = ast_new(p->arena, NODE_MATCH, ml);
            stmt->match_.expr = expr;
            stmt->match_.arms = arms;
            stmt->match_.narms = narms;
        } else if (check(p, TOK_IF)) {
            stmt = parse_primary(p);  /* if is parsed as expression */
        } else if (check(p, TOK_BREAK)) {
            stmt = ast_new(p->arena, NODE_BREAK, p->cur.loc);
            next(p);
        } else if (check(p, TOK_CONTINUE)) {
            stmt = ast_new(p->arena, NODE_CONTINUE, p->cur.loc);
            next(p);
        } else {
            /* Expression statement, possibly followed by = for assignment */
            AstNode *expr = parse_expr(p);
            if (check(p, TOK_EQ)) {
                SrcLoc al = p->cur.loc;
                next(p);
                AstNode *value = parse_expr(p);
                stmt = ast_new(p->arena, NODE_ASSIGN, al);
                stmt->assign.target = expr;
                stmt->assign.value = value;
            } else {
                stmt = ast_new(p->arena, NODE_EXPR_STMT, expr->loc);
                stmt->expr_stmt.expr = expr;
            }
        }

        if (nstmts >= cap) {
            int nc = cap * 2;
            AstNode **ns = arena_alloc(p->arena, nc * sizeof(AstNode *), _Alignof(AstNode *));
            memcpy(ns, stmts, nstmts * sizeof(AstNode *));
            stmts = ns; cap = nc;
        }
        stmts[nstmts++] = stmt;
    }
    expect(p, TOK_RBRACE, "'}'");
    AstNode *block = ast_new(p->arena, NODE_BLOCK, sl);
    block->block.stmts = stmts;
    block->block.nstmts = nstmts;
    return block;
}

/* ---- Top-level declarations ---- */

static AstNode *parse_annotation(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_AT, "'@'");
    Token name = expect(p, TOK_IDENT, "annotation name");
    expect(p, TOK_LPAREN, "'('");
    Token prompt = expect(p, TOK_STRING, "annotation string");
    expect(p, TOK_RPAREN, "')'");
    AstNode *ann = ast_new(p->arena, NODE_ANNOTATION, sl);
    ann->annotation.name = arena_strndup(p->arena, name.text, name.len);
    ann->annotation.prompt = arena_strndup(p->arena, prompt.text, prompt.len);
    return ann;
}

static Param parse_param(Parser *p) {
    Param param = {0};
    Token name = expect(p, TOK_IDENT, "parameter name");
    param.name = arena_strndup(p->arena, name.text, name.len);
    expect(p, TOK_COLON, "':'");
    /* Parse type, noting & and &mut and * */
    if (check(p, TOK_AMP)) {
        next(p);
        if (check(p, TOK_MUT)) { next(p); param.is_mut_ref = 1; }
        else { param.is_ref = 1; }
        Token tn = expect(p, TOK_IDENT, "type name");
        param.type_name = arena_strndup(p->arena, tn.text, tn.len);
    } else if (check(p, TOK_STAR)) {
        next(p);
        param.is_ptr = 1;
        Token tn = expect(p, TOK_IDENT, "type name");
        param.type_name = arena_strndup(p->arena, tn.text, tn.len);
    } else {
        Token tn = expect(p, TOK_IDENT, "type name");
        param.type_name = arena_strndup(p->arena, tn.text, tn.len);
    }
    return param;
}

static AstNode *parse_fn_def(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_FN, "'fn'");
    Token name = expect(p, TOK_IDENT, "function name");
    expect(p, TOK_LPAREN, "'('");

    /* Parse parameters */
    int pcap = 4, nparams = 0;
    Param *params = arena_alloc(p->arena, pcap * sizeof(Param), _Alignof(Param));
    while (!check(p, TOK_RPAREN)) {
        if (nparams > 0) expect(p, TOK_COMMA, "','");
        if (nparams >= pcap) {
            int nc = pcap * 2;
            Param *np = arena_alloc(p->arena, nc * sizeof(Param), _Alignof(Param));
            memcpy(np, params, nparams * sizeof(Param));
            params = np; pcap = nc;
        }
        params[nparams++] = parse_param(p);
    }
    expect(p, TOK_RPAREN, "')'");

    /* Optional return type */
    const char *ret_type = NULL;
    if (check(p, TOK_ARROW)) {
        next(p);
        ret_type = parse_type(p);
    }

    AstNode *body = parse_block(p);
    AstNode *fn = ast_new(p->arena, NODE_FN_DEF, sl);
    fn->fn_def.name = arena_strndup(p->arena, name.text, name.len);
    fn->fn_def.params = params;
    fn->fn_def.nparams = nparams;
    fn->fn_def.ret_type = ret_type;
    fn->fn_def.body = body;
    return fn;
}

static AstNode *parse_struct_def(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_STRUCT, "'struct'");
    Token name = expect(p, TOK_IDENT, "struct name");
    expect(p, TOK_LBRACE, "'{'");

    int cap = 8, nfields = 0;
    Field *fields = arena_alloc(p->arena, cap * sizeof(Field), _Alignof(Field));
    while (!check(p, TOK_RBRACE)) {
        if (nfields >= cap) {
            int nc = cap * 2;
            Field *nf = arena_alloc(p->arena, nc * sizeof(Field), _Alignof(Field));
            memcpy(nf, fields, nfields * sizeof(Field));
            fields = nf; cap = nc;
        }
        Token fn_tok = expect(p, TOK_IDENT, "field name");
        expect(p, TOK_COLON, "':'");
        const char *tn = parse_type(p);
        fields[nfields].name = arena_strndup(p->arena, fn_tok.text, fn_tok.len);
        fields[nfields].type_name = tn;
        nfields++;
        match(p, TOK_COMMA);
    }
    expect(p, TOK_RBRACE, "'}'");
    AstNode *s = ast_new(p->arena, NODE_STRUCT_DEF, sl);
    s->struct_def.name = arena_strndup(p->arena, name.text, name.len);
    s->struct_def.fields = fields;
    s->struct_def.nfields = nfields;
    return s;
}

static AstNode *parse_enum_def(Parser *p) {
    SrcLoc sl = p->cur.loc;
    expect(p, TOK_ENUM, "'enum'");
    Token name = expect(p, TOK_IDENT, "enum name");
    expect(p, TOK_LBRACE, "'{'");

    int cap = 8, nvariants = 0;
    Variant *variants = arena_alloc(p->arena, cap * sizeof(Variant), _Alignof(Variant));
    while (!check(p, TOK_RBRACE)) {
        if (nvariants >= cap) {
            int nc = cap * 2;
            Variant *nv = arena_alloc(p->arena, nc * sizeof(Variant), _Alignof(Variant));
            memcpy(nv, variants, nvariants * sizeof(Variant));
            variants = nv; cap = nc;
        }
        Token vn = expect(p, TOK_IDENT, "variant name");
        variants[nvariants].name = arena_strndup(p->arena, vn.text, vn.len);
        variants[nvariants].field_types = NULL;
        variants[nvariants].nfields = 0;
        /* Optional data fields: Variant(Type, Type) */
        if (check(p, TOK_LPAREN)) {
            next(p);
            int fcap = 4, nf = 0;
            const char **ftypes = arena_alloc(p->arena, fcap * sizeof(char *), _Alignof(char *));
            while (!check(p, TOK_RPAREN)) {
                if (nf > 0) expect(p, TOK_COMMA, "','");
                const char *t = parse_type(p);
                if (nf >= fcap) {
                    int nc = fcap * 2;
                    const char **nt = arena_alloc(p->arena, nc * sizeof(char *), _Alignof(char *));
                    memcpy(nt, ftypes, nf * sizeof(char *));
                    ftypes = nt; fcap = nc;
                }
                ftypes[nf++] = t;
            }
            expect(p, TOK_RPAREN, "')'");
            variants[nvariants].field_types = ftypes;
            variants[nvariants].nfields = nf;
        }
        nvariants++;
        match(p, TOK_COMMA);
    }
    expect(p, TOK_RBRACE, "'}'");
    AstNode *e = ast_new(p->arena, NODE_ENUM_DEF, sl);
    e->enum_def.name = arena_strndup(p->arena, name.text, name.len);
    e->enum_def.variants = variants;
    e->enum_def.nvariants = nvariants;
    return e;
}

AstNode *parse(Lexer *l, Arena *a) {
    Parser p = { .lexer = l, .arena = a };
    next(&p);

    int cap = 16, ndecls = 0;
    AstNode **decls = arena_alloc(a, cap * sizeof(AstNode *), _Alignof(AstNode *));

    while (!check(&p, TOK_EOF)) {
        AstNode *decl = NULL;

        if (check(&p, TOK_AT)) {
            AstNode *ann = parse_annotation(&p);
            AstNode *fn = parse_fn_def(&p);
            ann->annotation.child = fn;
            decl = ann;
        } else if (check(&p, TOK_FN)) {
            decl = parse_fn_def(&p);
        } else if (check(&p, TOK_STRUCT)) {
            decl = parse_struct_def(&p);
        } else if (check(&p, TOK_ENUM)) {
            decl = parse_enum_def(&p);
        } else if (check(&p, TOK_LET)) {
            /* Top-level let (global constant) — parse same as block let */
            SrcLoc ll = p.cur.loc;
            next(&p);
            int is_mut = 0;
            if (check(&p, TOK_MUT)) { next(&p); is_mut = 1; }
            Token name = expect(&p, TOK_IDENT, "variable name");
            decl = ast_new(a, NODE_LET, ll);
            decl->let.name = arena_strndup(a, name.text, name.len);
            decl->let.is_mut = is_mut;
            decl->let.type_name = NULL;
            decl->let.is_buffer = 0;
            decl->let.init = NULL;
            if (check(&p, TOK_COLON)) { next(&p); decl->let.type_name = parse_type(&p); }
            if (match(&p, TOK_EQ)) {
                if (check(&p, TOK_LBRACKET)) {
                    next(&p);
                    Token sz = expect(&p, TOK_INT, "buffer size");
                    expect(&p, TOK_RBRACKET, "']'");
                    decl->let.is_buffer = 1;
                    decl->let.buffer_size = (int)strtol(sz.text, NULL, 10);
                } else {
                    decl->let.init = parse_expr(&p);
                }
            }
        } else {
            error_at(p.cur.loc, "expected top-level declaration, got '%.*s'",
                     p.cur.len, p.cur.text);
        }

        if (ndecls >= cap) {
            int nc = cap * 2;
            AstNode **nd = arena_alloc(a, nc * sizeof(AstNode *), _Alignof(AstNode *));
            memcpy(nd, decls, ndecls * sizeof(AstNode *));
            decls = nd; cap = nc;
        }
        decls[ndecls++] = decl;
    }

    AstNode *prog = ast_new(a, NODE_PROGRAM, (SrcLoc){ l->file, 1, 1 });
    prog->program.decls = decls;
    prog->program.ndecls = ndecls;
    return prog;
}
