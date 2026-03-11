#include "types.kth"
// main.kt — Compiler driver: CLI args, read file, preprocess, run pipeline

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

fn dir_of(path: &str) -> &str {
    let last = strrchr(path, 47)  // '/'
    if last == 0 { return strdup(".") }
    let len = last - path
    let dir = malloc(len + 1)
    memcpy(dir, path, len)
    dir[len] = 0
    return dir
}

fn preprocess(src: &str, filepath: &str) -> &str {
    let dir = dir_of(filepath)
    let mut cap: u64 = strlen(src) * 2 + 1024
    let mut out = malloc(cap)
    let mut olen: u64 = 0
    let mut p = src

    while p[0] != 0 {
        // Check for #include at start of line
        if p[0] == 35 && starts_with(p, "#include") && (p == src || p[-1] == 10) {
            p = p + 8
            while p[0] == 32 || p[0] == 9 { p = p + 1 }
            let mut delim_end: u8 = 0
            if p[0] == 34 { delim_end = 34; p = p + 1 }
            else if p[0] == 60 { delim_end = 62; p = p + 1 }
            else {
                out[olen] = 35
                olen = olen + 1
                p = p + 1
                continue
            }
            let fname_start = p
            while p[0] != 0 && p[0] != delim_end && p[0] != 10 { p = p + 1 }
            let fname_len = p - fname_start
            if p[0] == delim_end { p = p + 1 }
            while p[0] != 0 && p[0] != 10 { p = p + 1 }
            if p[0] == 10 { p = p + 1 }

            // Build include path
            let mut incpath = malloc(1024)
            snprintf(incpath, 1024, "%s/", dir)
            let dir_len = strlen(incpath)
            memcpy(incpath + dir_len, fname_start, fname_len)
            incpath[dir_len + fname_len] = 0

            let inc_src = read_file(incpath)
            let inc_pp = preprocess(inc_src, incpath)
            let inc_len = strlen(inc_pp)
            free(incpath)

            // Grow output if needed
            while olen + inc_len + strlen(p) + 2 > cap {
                cap = cap * 2
                out = realloc(out, cap)
            }
            memcpy(out + olen, inc_pp, inc_len)
            olen = olen + inc_len
            if inc_len > 0 && inc_pp[inc_len - 1] != 10 {
                out[olen] = 10
                olen = olen + 1
            }
            free(inc_pp)
            free(inc_src)
        } else {
            if olen + 1 >= cap { cap = cap * 2; out = realloc(out, cap) }
            out[olen] = p[0]
            olen = olen + 1
            p = p + 1
        }
    }
    out[olen] = 0
    free(dir)
    return out
}

fn kt_main() {
    let mut input: &str = 0
    let mut output: &str = 0
    let mut mp_refresh: i32 = 0
    let mut mp_skip: i32 = 0
    let mut emit_llvm: i32 = 0

    let mut i: i32 = 1
    while i < argc {
        if streq(argv[[i]], "-o") && i + 1 < argc {
            i = i + 1
            output = argv[[i]]
        } else if streq(argv[[i]], "--microparse-refresh") {
            mp_refresh = 1
        } else if streq(argv[[i]], "--skip-microparse") {
            mp_skip = 1
        } else if streq(argv[[i]], "--emit-llvm") {
            emit_llvm = 1
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

    let raw_src = read_file(input)
    let src = preprocess(raw_src, input)
    free(raw_src)
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
    if emit_llvm {
        codegen_llvm(program, out)
    } else {
        codegen(program, out)
    }
    fclose(out)

    arena_free(arena)
    free(src)
}
