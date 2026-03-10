#ifndef ARENA_H
#define ARENA_H

#include <stddef.h>

typedef struct Arena {
    char *base;
    size_t size;
    size_t used;
} Arena;

Arena arena_new(void);
void *arena_alloc(Arena *a, size_t size, size_t align);
void arena_free(Arena *a);
char *arena_strdup(Arena *a, const char *s);
char *arena_strndup(Arena *a, const char *s, size_t n);

#endif
