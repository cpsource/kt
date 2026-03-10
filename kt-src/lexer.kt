#include "types.kth"
// lexer.kt — Tokenizer for kt source (self-hosted)

fn lexer_new(src: &str, file: &str, arena: &mut Arena) -> Lexer {
    Lexer { src: src, file: file, pos: 0, line: 1, col: 1, arena: arena }
}

fn peek(l: &Lexer) -> u8 {
    return l.src[l.pos]
}

fn advance(l: &mut Lexer) -> u8 {
    let c = l.src[l.pos]
    l.pos = l.pos + 1
    if c == 10 {
        l.line = l.line + 1
        l.col = 1
    } else {
        l.col = l.col + 1
    }
    return c
}

fn skip_whitespace(l: &mut Lexer) {
    while peek(l) != 0 {
        let c = peek(l)
        if c == 32 || c == 9 || c == 10 || c == 13 {
            advance(l)
        } else {
            break
        }
    }
}

fn loc(l: &Lexer) -> SrcLoc {
    SrcLoc { file: l.file, line: l.line, col: l.col }
}

fn make_tok(kind: TokenKind, sl: SrcLoc, text: &str, len: i32) -> Token {
    Token { kind: kind, text: text, len: len, loc: sl }
}

fn lexer_next(l: &mut Lexer) -> Token {
    skip_whitespace(l)
    let sl = loc(l)
    let c = peek(l)

    if c == 0 { return make_tok(TokenKind::EOF, sl, "", 0) }

    // Skip semicolons
    if c == 59 {
        advance(l)
        return lexer_next(l)
    }

    // Skip line comments (// to end of line)
    if c == 47 && l.src[l.pos + 1] == 47 {
        while peek(l) != 0 && peek(l) != 10 { advance(l) }
        return lexer_next(l)
    }

    // Single-character tokens
    match c {
        40 => { advance(l); return make_tok(TokenKind::LPAREN, sl, "(", 1) }
        41 => { advance(l); return make_tok(TokenKind::RPAREN, sl, ")", 1) }
        123 => { advance(l); return make_tok(TokenKind::LBRACE, sl, "{", 1) }
        125 => { advance(l); return make_tok(TokenKind::RBRACE, sl, "}", 1) }
        91 => { advance(l); return make_tok(TokenKind::LBRACKET, sl, "[", 1) }
        93 => { advance(l); return make_tok(TokenKind::RBRACKET, sl, "]", 1) }
        44 => { advance(l); return make_tok(TokenKind::COMMA, sl, ",", 1) }
        58 => { advance(l); return make_tok(TokenKind::COLON, sl, ":", 1) }
        61 => { advance(l); return make_tok(TokenKind::EQ, sl, "=", 1) }
        64 => { advance(l); return make_tok(TokenKind::AT, sl, "@", 1) }
    }

    // String literal
    if c == 34 {
        advance(l)
        let save_pos = l.pos
        let save_line = l.line
        let save_col = l.col
        let mut len: u64 = 0
        while peek(l) != 0 && peek(l) != 34 {
            if peek(l) == 92 { advance(l); advance(l) }
            else { advance(l) }
            len = len + 1
        }
        l.pos = save_pos
        l.line = save_line
        l.col = save_col
        let buf = arena_alloc(l.arena, len + 1, 1)
        let mut i: u64 = 0
        while peek(l) != 0 && peek(l) != 34 {
            if peek(l) == 92 {
                advance(l)
                let esc = advance(l)
                match esc {
                    110 => { buf[i] = 10; i = i + 1 }
                    92  => { buf[i] = 92; i = i + 1 }
                    34  => { buf[i] = 34; i = i + 1 }
                    116 => { buf[i] = 9; i = i + 1 }
                }
            } else {
                buf[i] = advance(l)
                i = i + 1
            }
        }
        buf[i] = 0
        if peek(l) == 34 { advance(l) }
        return make_tok(TokenKind::STRING, sl, buf, i)
    }

    // Integer literal (decimal only)
    if is_digit(c) {
        let start = l.pos
        let mut n: i32 = 0
        while is_digit(peek(l)) { advance(l); n = n + 1 }
        let text = arena_strndup(l.arena, l.src + start, n)
        return make_tok(TokenKind::INT, sl, text, n)
    }

    // Identifier / keyword
    if is_alpha(c) || c == 95 {
        let start = l.pos
        let mut n: i32 = 0
        while is_alnum(peek(l)) || peek(l) == 95 { advance(l); n = n + 1 }
        let text = arena_strndup(l.arena, l.src + start, n)
        if n == 2 && streq(text, "fn") { return make_tok(TokenKind::FN, sl, text, n) }
        if n == 3 && streq(text, "let") { return make_tok(TokenKind::LET, sl, text, n) }
        if n == 6 && streq(text, "return") { return make_tok(TokenKind::RETURN, sl, text, n) }
        return make_tok(TokenKind::IDENT, sl, text, n)
    }

    error_at(sl, "unexpected character")
}
