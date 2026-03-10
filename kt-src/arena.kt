#include "types.kth"
// arena.kt — Bump allocator backed by mmap

let ARENA_SIZE: u64 = 1048576  // 1 MB

fn arena_new() -> Arena {
    let base = mmap(0, ARENA_SIZE, 3, 34, -1, 0)  // PROT_READ|PROT_WRITE=3, MAP_PRIVATE|MAP_ANONYMOUS=0x22
    if base == -1 {
        fprintf(stderr, "arena: mmap failed\n")
        exit(1)
    }
    Arena { base: base, size: ARENA_SIZE, used: 0 }
}

fn arena_alloc(a: &mut Arena, size: u64, align: u64) -> *u8 {
    let offset = (a.used + align - 1) & ~(align - 1)
    if offset + size > a.size {
        fprintf(stderr, "arena: out of memory\n")
        exit(1)
    }
    a.used = offset + size
    return a.base + offset
}

fn arena_free(a: &mut Arena) {
    munmap(a.base, a.size)
    a.base = 0
    a.size = 0
    a.used = 0
}

fn arena_strdup(a: &mut Arena, s: &str) -> &str {
    let len = strlen(s) + 1
    let p = arena_alloc(a, len, 1)
    memcpy(p, s, len)
    return p
}

fn arena_strndup(a: &mut Arena, s: &str, n: u64) -> &str {
    let p = arena_alloc(a, n + 1, 1)
    memcpy(p, s, n)
    p[n] = 0
    return p
}
