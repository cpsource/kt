#include "types.kth"
// microparse.kt — @microparse: AI-powered inline code generation via Claude API

fn hash_str(s: &str) -> u64 {
    let mut h: u64 = 5381
    let mut i: i32 = 0
    while s[i] != 0 {
        h = h * 33 + s[i]
        i = i + 1
    }
    return h
}

fn make_hash(prompt: &str, sig: &str) -> &str {
    let h1 = hash_str(prompt)
    let h2 = hash_str(sig)
    let combined = h1 ^ (h2 * 2654435761)
    let buf = malloc(32)
    snprintf(buf, 32, "%016lx", combined)
    return buf
}

fn gen_path(source_path: &str) -> &str {
    let len = strlen(source_path)
    let path = malloc(len + 5)
    snprintf(path, len + 5, "%s.gen", source_path)
    return path
}

fn read_file_or_null(path: &str) -> &str {
    let f = fopen(path, "r")
    if f == 0 { return 0 }
    fseek(f, 0, 2)  // SEEK_END
    let sz = ftell(f)
    fseek(f, 0, 0)  // SEEK_SET
    let buf = malloc(sz + 1)
    let n = fread(buf, 1, sz, f)
    buf[n] = 0
    fclose(f)
    return buf
}

fn cache_lookup(source_path: &str, hash: &str) -> &str {
    let gpath = gen_path(source_path)
    let content = read_file_or_null(gpath)
    free(gpath)
    if content == 0 { return 0 }

    if !starts_with(content, "HASH:") { free(content); return 0 }
    let nl = strchr(content, 10)
    if nl == 0 { free(content); return 0 }

    let mut saved_hash = malloc(64)
    memset(saved_hash, 0, 64)
    let hlen = nl - (content + 5)
    memcpy(saved_hash, content + 5, hlen)

    if !streq(saved_hash, hash) { free(saved_hash); free(content); return 0 }
    free(saved_hash)

    let source = strdup(nl + 1)
    free(content)
    return source
}

fn cache_write(source_path: &str, hash: &str, source: &str) {
    let gpath = gen_path(source_path)
    let f = fopen(gpath, "w")
    free(gpath)
    if f == 0 { return }
    fprintf(f, "HASH:%s\n%s", hash, source)
    fclose(f)
}

fn extract_fn_signature(f: *AstNode) -> &str {
    let buf = malloc(256)
    snprintf(buf, 256, "fn %s()", f.d0)
    return buf
}

fn json_escape(dst: &str, dstlen: u64, src: &str) -> u64 {
    let mut w: u64 = 0
    let mut i: i32 = 0
    while src[i] != 0 && w + 6 < dstlen {
        let c = src[i]
        if c == 34 { dst[w] = 92; dst[w+1] = 34; w = w + 2 }
        else if c == 92 { dst[w] = 92; dst[w+1] = 92; w = w + 2 }
        else if c == 10 { dst[w] = 92; dst[w+1] = 110; w = w + 2 }
        else if c == 13 { dst[w] = 92; dst[w+1] = 114; w = w + 2 }
        else if c == 9 { dst[w] = 92; dst[w+1] = 116; w = w + 2 }
        else { dst[w] = c; w = w + 1 }
        i = i + 1
    }
    dst[w] = 0
    return w
}

fn call_claude_api(api_key: &str, prompt: &str, sig: &str, loc: SrcLoc) -> &str {
    let esc_prompt = malloc(2048)
    let esc_sig = malloc(512)
    json_escape(esc_prompt, 2048, prompt)
    json_escape(esc_sig, 512, sig)

    let req = malloc(8192)
    snprintf(req, 8192,
        "{\"model\":\"claude-sonnet-4-20250514\",\"max_tokens\":1024,\"system\":\"You are a code generator for the kt language. kt syntax is similar to Rust. Respond with ONLY the function body code (the statements inside the braces), no explanation, no markdown fences.\",\"messages\":[{\"role\":\"user\",\"content\":\"Given this function signature: %s\\n\\nGenerate the function body for: %s\"}]}",
        esc_sig, esc_prompt)
    free(esc_prompt)
    free(esc_sig)

    let tmppath = malloc(32)
    memcpy(tmppath, "/tmp/kt_mp_XXXXXX", 18)
    tmppath[18] = 0
    let fd = mkstemp(tmppath)
    if fd < 0 { error_at(loc, "@microparse: failed to create temp file") }
    let tmpf = fdopen(fd, "w")
    fputs(req, tmpf)
    fclose(tmpf)
    free(req)

    let outpath = malloc(32)
    memcpy(outpath, "/tmp/kt_mp_out_XXXXXX", 22)
    outpath[22] = 0
    let ofd = mkstemp(outpath)
    close(ofd)

    let cmd = malloc(2048)
    snprintf(cmd, 2048,
        "curl -s -X POST https://api.anthropic.com/v1/messages -H 'Content-Type: application/json' -H 'x-api-key: %s' -H 'anthropic-version: 2023-06-01' --data-binary @%s -o %s",
        api_key, tmppath, outpath)

    let rc = system(cmd)
    free(cmd)
    unlink(tmppath)
    free(tmppath)
    if rc != 0 { error_at(loc, "@microparse: curl failed") }

    let resp = read_file_or_null(outpath)
    unlink(outpath)
    free(outpath)
    if resp == 0 { error_at(loc, "@microparse: failed to read curl output") }

    let text_key = strstr(resp, "\"text\":")
    if text_key == 0 { error_at(loc, "@microparse: failed to parse API response") }
    let start = strchr(text_key + 7, 34)
    if start == 0 { error_at(loc, "@microparse: malformed API response") }
    let start2 = start + 1

    let result = malloc(strlen(resp) + 1)
    let mut len: u64 = 0
    let mut p = start2
    while p[0] != 0 && !(p[0] == 34 && p[-1] != 92) {
        if p[0] == 92 && p[1] != 0 {
            p = p + 1
            if p[0] == 110 { result[len] = 10; len = len + 1 }
            else if p[0] == 116 { result[len] = 9; len = len + 1 }
            else if p[0] == 92 { result[len] = 92; len = len + 1 }
            else if p[0] == 34 { result[len] = 34; len = len + 1 }
            else { result[len] = p[0]; len = len + 1 }
        } else {
            result[len] = p[0]
            len = len + 1
        }
        p = p + 1
    }
    result[len] = 0
    free(resp)
    return result
}

fn splice_body(arena: &mut Arena, f: *AstNode, source: &str, file: &str) {
    let mut l = lexer_new(source, file, arena)
    let mut cap: i32 = 8
    let mut nstmts: i32 = 0
    let mut stmts = arena_alloc(arena, cap * 8, 8)

    let mut tok = lexer_next(l)
    while tok.kind != TokenKind::EOF {
        if tok.kind == TokenKind::IDENT {
            let name = tok
            tok = lexer_next(l)
            if tok.kind == TokenKind::LPAREN {
                let call: *AstNode = ast_new(arena, NodeKind::CALL, name.loc)
                call.d0 = arena_strndup(arena, name.text, name.len)
                let mut acap: i32 = 4
                let mut nargs: i32 = 0
                let mut args = arena_alloc(arena, acap * 8, 8)
                tok = lexer_next(l)
                while tok.kind != TokenKind::RPAREN && tok.kind != TokenKind::EOF {
                    if tok.kind == TokenKind::COMMA { tok = lexer_next(l); continue }
                    if tok.kind == TokenKind::STRING {
                        let s = ast_new(arena, NodeKind::STRING_LIT, tok.loc)
                        s.d0 = arena_strndup(arena, tok.text, tok.len)
                        args[[nargs]] = s
                        nargs = nargs + 1
                    } else if tok.kind == TokenKind::INT {
                        let n = ast_new(arena, NodeKind::INT_LIT, tok.loc)
                        n.d1 = arena_strndup(arena, tok.text, tok.len)
                        n.d0 = kt_strtol(n.d1)
                        args[[nargs]] = n
                        nargs = nargs + 1
                    }
                    tok = lexer_next(l)
                }
                call.d1 = args
                call.d2 = nargs
                let stmt = ast_new(arena, NodeKind::EXPR_STMT, call.loc)
                stmt.d0 = call
                stmts[[nstmts]] = stmt
                nstmts = nstmts + 1
                tok = lexer_next(l)
                continue
            }
        }
        tok = lexer_next(l)
    }

    let body: *AstNode = f.d1
    body.d0 = stmts
    body.d1 = nstmts
}

fn microparse_process(arena: &mut Arena, program: *AstNode, source_path: &str, force_refresh: i32, skip: i32) {
    let mut i: i32 = 0
    while i < program.d1 {
        let decl: *AstNode = program.d0[[i]]
        if decl.kind != NodeKind::ANNOTATION { i = i + 1; continue }
        if !streq(decl.d0, "microparse") { i = i + 1; continue }

        let f: *AstNode = decl.d2
        if f == 0 || f.kind != NodeKind::FN_DEF { i = i + 1; continue }

        let body: *AstNode = f.d1
        if body.d1 > 0 && force_refresh == 0 { i = i + 1; continue }

        let sig = extract_fn_signature(f)
        let hash = make_hash(decl.d1, sig)

        if force_refresh == 0 {
            let cached = cache_lookup(source_path, hash)
            if cached != 0 {
                splice_body(arena, f, cached, source_path)
                free(cached)
                free(sig)
                free(hash)
                i = i + 1
                continue
            }
        }

        if skip != 0 {
            error_at(decl.loc, "@microparse: no cache available and --skip-microparse is set")
        }

        let api_key = getenv("ANTHROPIC_API_KEY")
        if api_key == 0 {
            error_at(decl.loc, "@microparse requires ANTHROPIC_API_KEY environment variable")
        }

        let generated = call_claude_api(api_key, decl.d1, sig, decl.loc)
        splice_body(arena, f, generated, source_path)
        cache_write(source_path, hash, generated)
        free(generated)
        free(sig)
        free(hash)
        i = i + 1
    }
}
