#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "arena.h"
#include "lexer.h"
#include "parser.h"
#include "codegen.h"
#include "microparse.h"
#include "check.h"

static char *read_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) { perror(path); exit(1); }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc(sz + 1);
    size_t n = fread(buf, 1, sz, f);
    buf[n] = '\0';
    fclose(f);
    return buf;
}

/* Extract directory part of a file path */
static char *dir_of(const char *path) {
    const char *last = strrchr(path, '/');
    if (!last) return strdup(".");
    size_t len = (size_t)(last - path);
    char *dir = malloc(len + 1);
    memcpy(dir, path, len);
    dir[len] = '\0';
    return dir;
}

/* Preprocess #include directives, expanding them inline.
 * Supports: #include "file.kth"
 * Resolves relative to the directory of the including file. */
static char *preprocess(const char *src, const char *filepath) {
    char *dir = dir_of(filepath);
    size_t cap = strlen(src) * 2 + 1024;
    char *out = malloc(cap);
    size_t olen = 0;
    const char *p = src;

    while (*p) {
        /* Check for #include at start of line */
        if (*p == '#' && strncmp(p, "#include", 8) == 0) {
            p += 8;
            while (*p == ' ' || *p == '\t') p++;
            char delim_end = 0;
            if (*p == '"') { delim_end = '"'; p++; }
            else if (*p == '<') { delim_end = '>'; p++; }
            else { /* not a valid include, copy literally */
                out[olen++] = '#';
                continue;
            }
            const char *fname_start = p;
            while (*p && *p != delim_end && *p != '\n') p++;
            size_t fname_len = (size_t)(p - fname_start);
            if (*p == delim_end) p++;
            while (*p && *p != '\n') p++; /* skip rest of line */
            if (*p == '\n') p++;

            /* Build include path */
            char incpath[1024];
            snprintf(incpath, sizeof(incpath), "%s/%.*s", dir, (int)fname_len, fname_start);

            /* Read and recursively preprocess the included file */
            char *inc_src = read_file(incpath);
            char *inc_pp = preprocess(inc_src, incpath);
            size_t inc_len = strlen(inc_pp);

            /* Grow output buffer if needed */
            while (olen + inc_len + strlen(p) + 2 > cap) {
                cap *= 2;
                out = realloc(out, cap);
            }
            memcpy(out + olen, inc_pp, inc_len);
            olen += inc_len;
            /* Ensure newline after include */
            if (inc_len > 0 && inc_pp[inc_len - 1] != '\n')
                out[olen++] = '\n';
            free(inc_pp);
            free(inc_src);
        } else {
            if (olen + 1 >= cap) { cap *= 2; out = realloc(out, cap); }
            out[olen++] = *p++;
        }
    }
    out[olen] = '\0';
    free(dir);
    return out;
}

int main(int argc, char **argv) {
    const char *input = NULL;
    const char *output = NULL;
    int mp_refresh = 0;
    int mp_skip = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-o") == 0 && i + 1 < argc) {
            output = argv[++i];
        } else if (strcmp(argv[i], "--microparse-refresh") == 0) {
            mp_refresh = 1;
        } else if (strcmp(argv[i], "--skip-microparse") == 0) {
            mp_skip = 1;
        } else if (argv[i][0] != '-') {
            input = argv[i];
        } else {
            fprintf(stderr, "unknown option: %s\n", argv[i]);
            return 1;
        }
    }

    if (!input) {
        fprintf(stderr, "usage: ktc <input.kt> -o <output.s>\n");
        return 1;
    }
    if (!output) {
        fprintf(stderr, "error: -o <output.s> required\n");
        return 1;
    }

    char *raw_src = read_file(input);
    char *src = preprocess(raw_src, input);
    free(raw_src);
    Arena arena = arena_new();

    Lexer lexer = lexer_new(src, input, &arena);
    AstNode *program = parse(&lexer, &arena);

    /* Process @microparse annotations */
    microparse_process(&arena, program, input, mp_refresh, mp_skip);

    /* Static analysis */
    check_escape(program);

    FILE *out = fopen(output, "w");
    if (!out) { perror(output); return 1; }
    codegen(program, out);
    fclose(out);

    arena_free(&arena);
    free(src);
    return 0;
}
