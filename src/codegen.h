#ifndef CODEGEN_H
#define CODEGEN_H

#include "ast.h"
#include <stdio.h>

void codegen(AstNode *program, FILE *out);

#endif
