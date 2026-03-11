#include "types.kth"
// lexer.kt — Tokenizer for kt source

fn lexer_new(src: &str, file: &str, arena: &mut Arena) -> Lexer {
    Lexer { src: src, file: file, pos: 0, line: 1, col: 1, arena: arena }
}

fn peek(l: &Lexer) -> u8 {
    return l.src[l.pos]
}

fn peek2(l: &Lexer) -> u8 {
    return l.src[l.pos + 1]
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
        } else if c == 47 && peek2(l) == 47 {
            // Line comment
            while peek(l) != 0 && peek(l) != 10 { advance(l) }
        } else if c == 47 && peek2(l) == 42 {
            // Block comment
            advance(l)
            advance(l)
            while peek(l) != 0 && !(peek(l) == 42 && peek2(l) == 47) { advance(l) }
            if peek(l) != 0 { advance(l); advance(l) }
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

    // Two-character operators (check before single-char)
    let c2 = peek2(l)
    if c == 45 && c2 == 62 { advance(l); advance(l); return make_tok(TokenKind::ARROW, sl, "->", 2) }
    if c == 61 && c2 == 62 { advance(l); advance(l); return make_tok(TokenKind::FAT_ARROW, sl, "=>", 2) }
    if c == 61 && c2 == 61 { advance(l); advance(l); return make_tok(TokenKind::EQEQ, sl, "==", 2) }
    if c == 33 && c2 == 61 { advance(l); advance(l); return make_tok(TokenKind::NEQ, sl, "!=", 2) }
    if c == 60 && c2 == 61 { advance(l); advance(l); return make_tok(TokenKind::LTEQ, sl, "<=", 2) }
    if c == 62 && c2 == 61 { advance(l); advance(l); return make_tok(TokenKind::GTEQ, sl, ">=", 2) }
    if c == 38 && c2 == 38 { advance(l); advance(l); return make_tok(TokenKind::AND, sl, "&&", 2) }
    if c == 124 && c2 == 124 { advance(l); advance(l); return make_tok(TokenKind::OR, sl, "||", 2) }
    if c == 60 && c2 == 60 { advance(l); advance(l); return make_tok(TokenKind::SHL, sl, "<<", 2) }
    if c == 62 && c2 == 62 { advance(l); advance(l); return make_tok(TokenKind::SHR, sl, ">>", 2) }
    if c == 46 && c2 == 46 { advance(l); advance(l); return make_tok(TokenKind::DOTDOT, sl, "..", 2) }
    if c == 58 && c2 == 58 { advance(l); advance(l); return make_tok(TokenKind::COLONCOLON, sl, "::", 2) }

    // Single-character tokens
    if c == 40 { advance(l); return make_tok(TokenKind::LPAREN, sl, "(", 1) }
    if c == 41 { advance(l); return make_tok(TokenKind::RPAREN, sl, ")", 1) }
    if c == 123 { advance(l); return make_tok(TokenKind::LBRACE, sl, "{", 1) }
    if c == 125 { advance(l); return make_tok(TokenKind::RBRACE, sl, "}", 1) }
    if c == 91 { advance(l); return make_tok(TokenKind::LBRACKET, sl, "[", 1) }
    if c == 93 { advance(l); return make_tok(TokenKind::RBRACKET, sl, "]", 1) }
    if c == 44 { advance(l); return make_tok(TokenKind::COMMA, sl, ",", 1) }
    if c == 58 { advance(l); return make_tok(TokenKind::COLON, sl, ":", 1) }
    if c == 46 { advance(l); return make_tok(TokenKind::DOT, sl, ".", 1) }
    if c == 64 { advance(l); return make_tok(TokenKind::AT, sl, "@", 1) }
    if c == 61 { advance(l); return make_tok(TokenKind::EQ, sl, "=", 1) }
    if c == 43 { advance(l); return make_tok(TokenKind::PLUS, sl, "+", 1) }
    if c == 45 { advance(l); return make_tok(TokenKind::MINUS, sl, "-", 1) }
    if c == 42 { advance(l); return make_tok(TokenKind::STAR, sl, "*", 1) }
    if c == 47 { advance(l); return make_tok(TokenKind::SLASH, sl, "/", 1) }
    if c == 37 { advance(l); return make_tok(TokenKind::PERCENT, sl, "%", 1) }
    if c == 60 { advance(l); return make_tok(TokenKind::LT, sl, "<", 1) }
    if c == 62 { advance(l); return make_tok(TokenKind::GT, sl, ">", 1) }
    if c == 33 { advance(l); return make_tok(TokenKind::NOT, sl, "!", 1) }
    if c == 38 { advance(l); return make_tok(TokenKind::AMP, sl, "&", 1) }
    if c == 124 { advance(l); return make_tok(TokenKind::PIPE, sl, "|", 1) }
    if c == 94 { advance(l); return make_tok(TokenKind::CARET, sl, "^", 1) }
    if c == 126 { advance(l); return make_tok(TokenKind::TILDE, sl, "~", 1) }

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
                if esc == 110 { buf[i] = 10; i = i + 1 }
                else if esc == 92 { buf[i] = 92; i = i + 1 }
                else if esc == 34 { buf[i] = 34; i = i + 1 }
                else if esc == 116 { buf[i] = 9; i = i + 1 }
                else if esc == 114 { buf[i] = 13; i = i + 1 }
                else if esc == 48 { buf[i] = 0; i = i + 1 }
                else { buf[i] = esc; i = i + 1 }
            } else {
                buf[i] = advance(l)
                i = i + 1
            }
        }
        buf[i] = 0
        if peek(l) == 34 { advance(l) }
        return make_tok(TokenKind::STRING, sl, buf, i)
    }

    // Integer literal
    if is_digit(c) {
        let start = l.pos
        let mut n: i32 = 0
        // Handle 0x, 0b, 0o prefixes
        if c == 48 && (peek2(l) == 120 || peek2(l) == 98 || peek2(l) == 111) {
            advance(l)
            advance(l)
            n = 2
        }
        while is_alnum(peek(l)) || peek(l) == 95 { advance(l); n = n + 1 }
        let text = arena_strndup(l.arena, l.src + start, n)
        return make_tok(TokenKind::INT, sl, text, n)
    }

    // Identifier / keyword
    if is_alpha(c) || c == 95 {
        let start = l.pos
        let mut n: i32 = 0
        while is_alnum(peek(l)) || peek(l) == 95 { advance(l); n = n + 1 }
        let text = arena_strndup(l.arena, l.src + start, n)
        // Check keywords inline
        if n == 2 && streq(text, "fn") { return make_tok(TokenKind::FN, sl, text, n) }
        if n == 3 && streq(text, "let") { return make_tok(TokenKind::LET, sl, text, n) }
        if n == 3 && streq(text, "mut") { return make_tok(TokenKind::MUT, sl, text, n) }
        if n == 6 && streq(text, "return") { return make_tok(TokenKind::RETURN, sl, text, n) }
        if n == 2 && streq(text, "if") { return make_tok(TokenKind::IF, sl, text, n) }
        if n == 4 && streq(text, "else") { return make_tok(TokenKind::ELSE, sl, text, n) }
        if n == 5 && streq(text, "while") { return make_tok(TokenKind::WHILE, sl, text, n) }
        if n == 3 && streq(text, "for") { return make_tok(TokenKind::FOR, sl, text, n) }
        if n == 2 && streq(text, "in") { return make_tok(TokenKind::IN, sl, text, n) }
        if n == 5 && streq(text, "match") { return make_tok(TokenKind::MATCH, sl, text, n) }
        if n == 5 && streq(text, "break") { return make_tok(TokenKind::BREAK, sl, text, n) }
        if n == 8 && streq(text, "continue") { return make_tok(TokenKind::CONTINUE, sl, text, n) }
        if n == 6 && streq(text, "struct") { return make_tok(TokenKind::STRUCT, sl, text, n) }
        if n == 4 && streq(text, "enum") { return make_tok(TokenKind::ENUM, sl, text, n) }
        if n == 4 && streq(text, "impl") { return make_tok(TokenKind::IMPL, sl, text, n) }
        if n == 4 && streq(text, "type") { return make_tok(TokenKind::TYPE, sl, text, n) }
        if n == 4 && streq(text, "true") { return make_tok(TokenKind::TRUE, sl, text, n) }
        if n == 5 && streq(text, "false") { return make_tok(TokenKind::FALSE, sl, text, n) }
        return make_tok(TokenKind::IDENT, sl, text, n)
    }

    error_at(sl, "unexpected character")
}
