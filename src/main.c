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

    char *src = read_file(input);
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
