#ifndef MICROPARSE_H
#define MICROPARSE_H

#include "ast.h"
#include "arena.h"

/* Process all @microparse annotations in the AST.
 * Calls Claude API (or uses cache) to generate function bodies. */
void microparse_process(Arena *arena, AstNode *program, const char *source_path,
                        int force_refresh, int skip);

#endif
