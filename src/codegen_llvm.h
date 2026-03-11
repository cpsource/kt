#ifndef CODEGEN_LLVM_H
#define CODEGEN_LLVM_H

#include "ast.h"
#include <stdio.h>

void codegen_llvm(AstNode *program, FILE *out);

#endif
