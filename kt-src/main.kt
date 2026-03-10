#include "types.kth"
// main.kt — Compiler driver: CLI args, read file, run pipeline

fn read_file(path: &str) -> &str {
    let f = fopen(path, "r")
    if f == 0 {
        perror(path)
        exit(1)
    }
    fseek(f, 0, 2)  // SEEK_END
    let sz = ftell(f)
    fseek(f, 0, 0)  // SEEK_SET
    let buf = malloc(sz + 1)
    let n = fread(buf, 1, sz, f)
    buf[n] = 0
    fclose(f)
    return buf
}

fn kt_main() {
    let mut input: &str = 0
    let mut output: &str = 0
    let mut mp_refresh: i32 = 0
    let mut mp_skip: i32 = 0

    let mut i: i32 = 1
    while i < argc {
        if streq(argv[[i]], "-o") && i + 1 < argc {
            i = i + 1
            output = argv[[i]]
        } else if streq(argv[[i]], "--microparse-refresh") {
            mp_refresh = 1
        } else if streq(argv[[i]], "--skip-microparse") {
            mp_skip = 1
        } else if argv[[i]][0] != 45 {  // '-'
            input = argv[[i]]
        } else {
            fprintf(stderr, "unknown option: %s\n", argv[[i]])
            exit(1)
        }
        i = i + 1
    }

    if input == 0 {
        fprintf(stderr, "usage: ktc <input.kt> -o <output.s>\n")
        exit(1)
    }
    if output == 0 {
        fprintf(stderr, "error: -o <output.s> required\n")
        exit(1)
    }

    let src = read_file(input)
    let mut arena = arena_new()

    let mut lexer = lexer_new(src, input, arena)
    let program = parse(lexer, arena)

    // Process @microparse annotations
    microparse_process(arena, program, input, mp_refresh, mp_skip)

    // Static analysis
    check_escape(program)

    let out = fopen(output, "w")
    if out == 0 {
        perror(output)
        exit(1)
    }
    codegen(program, out)
    fclose(out)

    arena_free(arena)
    free(src)
}
