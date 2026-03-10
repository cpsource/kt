#ifndef CHECK_H
#define CHECK_H

#include "ast.h"

/* Static analysis passes run between parse and codegen.
 * Errors are fatal (calls error_at and exits). */
void check_escape(AstNode *program);

#endif
