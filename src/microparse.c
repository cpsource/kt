#include "microparse.h"
#include "lexer.h"
#include "parser.h"
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

/* Simple DJB2-based hash (not cryptographic, but fine for caching) */
static unsigned long hash_str(const char *s) {
    unsigned long h = 5381;
    while (*s) h = h * 33 + (unsigned char)*s++;
    return h;
}

static char *make_hash(const char *prompt, const char *sig) {
    unsigned long h1 = hash_str(prompt);
    unsigned long h2 = hash_str(sig);
    unsigned long combined = h1 ^ (h2 * 2654435761UL);
    char *buf = malloc(32);
    snprintf(buf, 32, "%016lx", combined);
    return buf;
}

static char *gen_path(const char *source_path) {
    size_t len = strlen(source_path);
    char *path = malloc(len + 5);
    snprintf(path, len + 5, "%s.gen", source_path);
    return path;
}

/* Read entire file, returns NULL if not found */
static char *read_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc(sz + 1);
    size_t n = fread(buf, 1, sz, f);
    buf[n] = '\0';
    fclose(f);
    return buf;
}

/* Check cache for matching hash. Returns cached source or NULL. */
static char *cache_lookup(const char *source_path, const char *hash) {
    char *gpath = gen_path(source_path);
    char *content = read_file(gpath);
    free(gpath);
    if (!content) return NULL;

    /* Format: first line is "HASH:<hash>", rest is source */
    if (strncmp(content, "HASH:", 5) != 0) { free(content); return NULL; }
    char *nl = strchr(content, '\n');
    if (!nl) { free(content); return NULL; }

    char saved_hash[64] = {0};
    size_t hlen = nl - (content + 5);
    if (hlen >= sizeof(saved_hash)) hlen = sizeof(saved_hash) - 1;
    memcpy(saved_hash, content + 5, hlen);

    if (strcmp(saved_hash, hash) != 0) { free(content); return NULL; }

    char *source = strdup(nl + 1);
    free(content);
    return source;
}

static void cache_write(const char *source_path, const char *hash, const char *source) {
    char *gpath = gen_path(source_path);
    FILE *f = fopen(gpath, "w");
    free(gpath);
    if (!f) return;
    fprintf(f, "HASH:%s\n%s", hash, source);
    fclose(f);
}

/* Extract function signature from the fn_def node */
static char *extract_fn_signature(AstNode *fn) {
    /* For now, just "fn <name>()" */
    char *buf = malloc(256);
    snprintf(buf, 256, "fn %s()", fn->fn_def.name);
    return buf;
}

/* Write a JSON-escaped version of src into dst. Returns bytes written. */
static size_t json_escape(char *dst, size_t dstlen, const char *src) {
    size_t w = 0;
    for (const char *p = src; *p && w + 6 < dstlen; p++) {
        switch (*p) {
        case '"':  dst[w++] = '\\'; dst[w++] = '"'; break;
        case '\\': dst[w++] = '\\'; dst[w++] = '\\'; break;
        case '\n': dst[w++] = '\\'; dst[w++] = 'n'; break;
        case '\r': dst[w++] = '\\'; dst[w++] = 'r'; break;
        case '\t': dst[w++] = '\\'; dst[w++] = 't'; break;
        default:   dst[w++] = *p; break;
        }
    }
    dst[w] = '\0';
    return w;
}

/* Call Claude API via curl, return generated source */
static char *call_claude_api(const char *api_key, const char *prompt, const char *sig, SrcLoc loc) {
    /* JSON-escape the prompt and signature */
    char esc_prompt[2048], esc_sig[512];
    json_escape(esc_prompt, sizeof(esc_prompt), prompt);
    json_escape(esc_sig, sizeof(esc_sig), sig);

    /* Build the request JSON */
    char *req = malloc(8192);
    snprintf(req, 8192,
        "{\"model\":\"claude-sonnet-4-20250514\","
        "\"max_tokens\":1024,"
        "\"system\":\"You are a code generator for the kt language. kt syntax is similar to Rust. "
        "Respond with ONLY the function body code (the statements inside the braces), no explanation, no markdown fences.\","
        "\"messages\":[{\"role\":\"user\",\"content\":\"Given this function signature: %s\\n\\n"
        "Generate the function body for: %s\"}]"
        "}", esc_sig, esc_prompt);

    /* Write request to temp file */
    char tmppath[] = "/tmp/kt_mp_XXXXXX";
    int fd = mkstemp(tmppath);
    if (fd < 0) error_at(loc, "@microparse: failed to create temp file");
    FILE *tmpf = fdopen(fd, "w");
    fputs(req, tmpf);
    fclose(tmpf);
    free(req);

    /* Build curl command — read response to a temp output file */
    char outpath[] = "/tmp/kt_mp_out_XXXXXX";
    int ofd = mkstemp(outpath);
    close(ofd);

    char cmd[2048];
    snprintf(cmd, sizeof(cmd),
        "curl -s -X POST https://api.anthropic.com/v1/messages "
        "-H 'Content-Type: application/json' "
        "-H 'x-api-key: %s' "
        "-H 'anthropic-version: 2023-06-01' "
        "--data-binary @%s -o %s", api_key, tmppath, outpath);

    int rc = system(cmd);
    unlink(tmppath);
    if (rc != 0) error_at(loc, "@microparse: curl failed with code %d", rc);

    /* Read response from output file */
    char *resp = read_file(outpath);
    unlink(outpath);
    if (!resp) error_at(loc, "@microparse: failed to read curl output");

    /* Extract text from response JSON (simple parsing) */
    /* Look for "text":" pattern in content array */
    char *text_key = strstr(resp, "\"text\":");
    if (!text_key) {
        fprintf(stderr, "@microparse: API response:\n%s\n", resp);
        error_at(loc, "@microparse: failed to parse API response");
    }
    char *start = strchr(text_key + 7, '"');
    if (!start) error_at(loc, "@microparse: malformed API response");
    start++; /* skip opening quote */

    /* Find end of string, handling escapes */
    size_t len = 0;
    char *result = malloc(strlen(resp) + 1);
    for (const char *p = start; *p && !(*p == '"' && *(p-1) != '\\'); p++) {
        if (*p == '\\' && *(p+1)) {
            p++;
            switch (*p) {
            case 'n': result[len++] = '\n'; break;
            case 't': result[len++] = '\t'; break;
            case '\\': result[len++] = '\\'; break;
            case '"': result[len++] = '"'; break;
            default: result[len++] = *p; break;
            }
        } else {
            result[len++] = *p;
        }
    }
    result[len] = '\0';
    free(resp);
    return result;
}

/* Splice generated source into the function body */
static void splice_body(Arena *arena, AstNode *fn, const char *source, const char *file) {
    /* Wrap in braces to make it a block */
    size_t len = strlen(source);
    char *wrapped = arena_alloc(arena, len + 4, 1);
    wrapped[0] = '{';
    memcpy(wrapped + 1, source, len);
    wrapped[len + 1] = '}';
    wrapped[len + 2] = '\0';

    Lexer l = lexer_new(wrapped, file, arena);
    /* Parse just a block */
    Token t = lexer_next(&l);
    (void)t;
    /* We need a mini-parse here. For now, re-parse as a full program won't work.
     * Simple approach: lex and build statements manually. */

    /* For MVP, just re-lex the source and build call nodes */
    Lexer l2 = lexer_new(source, file, arena);
    int cap = 8, nstmts = 0;
    AstNode **stmts = arena_alloc(arena, cap * sizeof(AstNode *), _Alignof(AstNode *));

    Token tok = lexer_next(&l2);
    while (tok.kind != TOK_EOF) {
        if (tok.kind == TOK_IDENT) {
            Token name = tok;
            tok = lexer_next(&l2);
            if (tok.kind == TOK_LPAREN) {
                /* It's a call */
                AstNode *call = ast_new(arena, NODE_CALL, name.loc);
                call->call.name = arena_strndup(arena, name.text, name.len);
                int acap = 4, nargs = 0;
                AstNode **args = arena_alloc(arena, acap * sizeof(AstNode *), _Alignof(AstNode *));
                tok = lexer_next(&l2);
                while (tok.kind != TOK_RPAREN && tok.kind != TOK_EOF) {
                    if (tok.kind == TOK_COMMA) { tok = lexer_next(&l2); continue; }
                    if (tok.kind == TOK_STRING) {
                        AstNode *s = ast_new(arena, NODE_STRING_LIT, tok.loc);
                        s->string_lit.value = arena_strndup(arena, tok.text, tok.len);
                        args[nargs++] = s;
                    } else if (tok.kind == TOK_INT) {
                        AstNode *n = ast_new(arena, NODE_INT_LIT, tok.loc);
                        n->int_lit.text = arena_strndup(arena, tok.text, tok.len);
                        n->int_lit.value = strtol(n->int_lit.text, NULL, 10);
                        args[nargs++] = n;
                    }
                    tok = lexer_next(&l2);
                }
                call->call.args = args;
                call->call.nargs = nargs;
                AstNode *stmt = ast_new(arena, NODE_EXPR_STMT, call->loc);
                stmt->expr_stmt.expr = call;
                stmts[nstmts++] = stmt;
                tok = lexer_next(&l2);
                continue;
            }
        }
        tok = lexer_next(&l2);
    }

    fn->fn_def.body->block.stmts = stmts;
    fn->fn_def.body->block.nstmts = nstmts;
}

void microparse_process(Arena *arena, AstNode *program, const char *source_path,
                        int force_refresh, int skip) {
    for (int i = 0; i < program->program.ndecls; i++) {
        AstNode *decl = program->program.decls[i];
        if (decl->kind != NODE_ANNOTATION) continue;
        if (strcmp(decl->annotation.name, "microparse") != 0) continue;

        AstNode *fn = decl->annotation.child;
        if (!fn || fn->kind != NODE_FN_DEF) continue;

        /* Check if body is empty */
        if (fn->fn_def.body->block.nstmts > 0 && !force_refresh) continue;

        char *sig = extract_fn_signature(fn);
        char *hash = make_hash(decl->annotation.prompt, sig);

        if (!force_refresh) {
            char *cached = cache_lookup(source_path, hash);
            if (cached) {
                splice_body(arena, fn, cached, source_path);
                free(cached);
                free(sig);
                free(hash);
                continue;
            }
        }

        if (skip) {
            error_at(decl->loc, "@microparse: no cache available and --skip-microparse is set");
        }

        const char *api_key = getenv("ANTHROPIC_API_KEY");
        if (!api_key) {
            error_at(decl->loc, "@microparse requires ANTHROPIC_API_KEY environment variable");
        }

        char *generated = call_claude_api(api_key, decl->annotation.prompt, sig, decl->loc);
        splice_body(arena, fn, generated, source_path);
        cache_write(source_path, hash, generated);
        free(generated);
        free(sig);
        free(hash);
    }
}
