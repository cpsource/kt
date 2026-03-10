#include "arena.h"
#include <stdio.h>
#include <sys/mman.h>
#include <string.h>
#include <stdlib.h>

#define ARENA_SIZE (1024 * 1024)  /* 1 MB */

Arena arena_new(void) {
    void *p = mmap(NULL, ARENA_SIZE, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) {
        perror("mmap");
        exit(1);
    }
    return (Arena){ .base = p, .size = ARENA_SIZE, .used = 0 };
}

void *arena_alloc(Arena *a, size_t size, size_t align) {
    size_t offset = (a->used + align - 1) & ~(align - 1);
    if (offset + size > a->size) {
        fprintf(stderr, "arena: out of memory\n");
        exit(1);
    }
    a->used = offset + size;
    return a->base + offset;
}

void arena_free(Arena *a) {
    munmap(a->base, a->size);
    a->base = NULL;
    a->size = 0;
    a->used = 0;
}

char *arena_strdup(Arena *a, const char *s) {
    size_t len = strlen(s) + 1;
    char *p = arena_alloc(a, len, 1);
    memcpy(p, s, len);
    return p;
}

char *arena_strndup(Arena *a, const char *s, size_t n) {
    char *p = arena_alloc(a, n + 1, 1);
    memcpy(p, s, n);
    p[n] = '\0';
    return p;
}
