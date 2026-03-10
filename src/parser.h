#ifndef PARSER_H
#define PARSER_H

#include "ast.h"
#include "lexer.h"

AstNode *parse(Lexer *l, Arena *a);

#endif
