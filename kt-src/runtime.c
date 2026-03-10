/*
 * runtime.c — kt runtime library
 *
 * Provides helper functions and enum constants needed by kt-src
 * when cross-compiling with the C-built ktc compiler.
 *
 * Compile with: gcc -c -o runtime.o runtime.c
 */

#include <string.h>
#include <ctype.h>

/* ---- String / character helpers ---- */

long streq(const char *a, const char *b) {
    if (!a || !b) return a == b;
    return strcmp(a, b) == 0;
}

long is_digit(long c) {
    return c >= '0' && c <= '9';
}

long is_alpha(long c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

long is_alnum(long c) {
    return is_alpha(c) || is_digit(c);
}

long starts_with(const char *s, const char *prefix) {
    if (!s || !prefix) return 0;
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

/* ---- Program arguments ---- */
/* These are set by _start → __libc_start_main → main(argc, argv).
 * We export them as globals so kt code can reference them. */
long kt_argc;
char **kt_argv;

/* Wrapper main that captures argc/argv, then calls kt's main */
extern void kt_main(void);

/* This symbol is what musl calls. We capture args and forward. */
int main(int ac, char **av) {
    kt_argc = ac;
    kt_argv = av;
    kt_main();
    return 0;
}

/* Provide argc/argv as global symbols */
__asm__(
    ".globl argc\n"
    "argc = kt_argc\n"
    ".globl argv\n"
    "argv = kt_argv\n"
);

/* ---- Global mutable arrays ---- */

/* check.kt: let mut check_locals: [&str; 64] */
const char *check_locals[64];

/* codegen.kt: arg_regs (no longer needed since we use get_arg_reg(), but keep for link) */
const char *arg_regs[6] = { "%rdi", "%rsi", "%rdx", "%rcx", "%r8", "%r9" };

/* ---- Enum constants ----
 *
 * When kt-src files reference Enum::Variant across compilation units,
 * the compiler emits: movq Enum__Variant(%rip), %rax
 * We provide these as 8-byte constants in .data.
 */

#define ENUM_CONST(name, val) \
    __asm__(".section .data\n" \
            ".globl " #name "\n" \
            ".align 8\n" \
            #name ":\n" \
            ".quad " #val "\n" \
            ".text\n")

/* TokenKind values (must match types.kth / src/token.h enum order) */
ENUM_CONST(TokenKind__FN,         0);
ENUM_CONST(TokenKind__LET,        1);
ENUM_CONST(TokenKind__MUT,        2);
ENUM_CONST(TokenKind__RETURN,     3);
ENUM_CONST(TokenKind__IF,         4);
ENUM_CONST(TokenKind__ELSE,       5);
ENUM_CONST(TokenKind__WHILE,      6);
ENUM_CONST(TokenKind__FOR,        7);
ENUM_CONST(TokenKind__IN,         8);
ENUM_CONST(TokenKind__MATCH,      9);
ENUM_CONST(TokenKind__BREAK,     10);
ENUM_CONST(TokenKind__CONTINUE,  11);
ENUM_CONST(TokenKind__STRUCT,    12);
ENUM_CONST(TokenKind__ENUM,      13);
ENUM_CONST(TokenKind__IMPL,      14);
ENUM_CONST(TokenKind__TYPE,      15);
ENUM_CONST(TokenKind__TRUE,      16);
ENUM_CONST(TokenKind__FALSE,     17);
ENUM_CONST(TokenKind__IDENT,     18);
ENUM_CONST(TokenKind__STRING,    19);
ENUM_CONST(TokenKind__INT,       20);
ENUM_CONST(TokenKind__LPAREN,    21);
ENUM_CONST(TokenKind__RPAREN,    22);
ENUM_CONST(TokenKind__LBRACE,    23);
ENUM_CONST(TokenKind__RBRACE,    24);
ENUM_CONST(TokenKind__LBRACKET,  25);
ENUM_CONST(TokenKind__RBRACKET,  26);
ENUM_CONST(TokenKind__COMMA,     27);
ENUM_CONST(TokenKind__COLON,     28);
ENUM_CONST(TokenKind__DOT,       29);
ENUM_CONST(TokenKind__AT,        30);
ENUM_CONST(TokenKind__ARROW,     31);
ENUM_CONST(TokenKind__FAT_ARROW, 32);
ENUM_CONST(TokenKind__DOTDOT,    33);
ENUM_CONST(TokenKind__COLONCOLON,34);
ENUM_CONST(TokenKind__EQ,        35);
ENUM_CONST(TokenKind__PLUS,      36);
ENUM_CONST(TokenKind__MINUS,     37);
ENUM_CONST(TokenKind__STAR,      38);
ENUM_CONST(TokenKind__SLASH,     39);
ENUM_CONST(TokenKind__PERCENT,   40);
ENUM_CONST(TokenKind__EQEQ,     41);
ENUM_CONST(TokenKind__NEQ,       42);
ENUM_CONST(TokenKind__LT,        43);
ENUM_CONST(TokenKind__GT,        44);
ENUM_CONST(TokenKind__LTEQ,      45);
ENUM_CONST(TokenKind__GTEQ,      46);
ENUM_CONST(TokenKind__AND,       47);
ENUM_CONST(TokenKind__OR,        48);
ENUM_CONST(TokenKind__NOT,       49);
ENUM_CONST(TokenKind__AMP,       50);
ENUM_CONST(TokenKind__PIPE,      51);
ENUM_CONST(TokenKind__CARET,     52);
ENUM_CONST(TokenKind__TILDE,     53);
ENUM_CONST(TokenKind__SHL,       54);
ENUM_CONST(TokenKind__SHR,       55);
ENUM_CONST(TokenKind__EOF,       56);

/* NodeKind values (must match types.kth / src/ast.h enum order) */
ENUM_CONST(NodeKind__PROGRAM,     0);
ENUM_CONST(NodeKind__FN_DEF,      1);
ENUM_CONST(NodeKind__STRUCT_DEF,  2);
ENUM_CONST(NodeKind__ENUM_DEF,    3);
ENUM_CONST(NodeKind__BLOCK,       4);
ENUM_CONST(NodeKind__EXPR_STMT,   5);
ENUM_CONST(NodeKind__RETURN,      6);
ENUM_CONST(NodeKind__LET,         7);
ENUM_CONST(NodeKind__ASSIGN,      8);
ENUM_CONST(NodeKind__IF,          9);
ENUM_CONST(NodeKind__WHILE,      10);
ENUM_CONST(NodeKind__FOR_RANGE,  11);
ENUM_CONST(NodeKind__MATCH,      12);
ENUM_CONST(NodeKind__BREAK,      13);
ENUM_CONST(NodeKind__CONTINUE,   14);
ENUM_CONST(NodeKind__CALL,       15);
ENUM_CONST(NodeKind__BINOP,      16);
ENUM_CONST(NodeKind__UNARY,      17);
ENUM_CONST(NodeKind__MEMBER,     18);
ENUM_CONST(NodeKind__INDEX,      19);
ENUM_CONST(NodeKind__STRING_LIT, 20);
ENUM_CONST(NodeKind__INT_LIT,    21);
ENUM_CONST(NodeKind__BOOL_LIT,   22);
ENUM_CONST(NodeKind__IDENT,      23);
ENUM_CONST(NodeKind__PATH,       24);
ENUM_CONST(NodeKind__STRUCT_LIT, 25);
ENUM_CONST(NodeKind__ADDR_OF,    26);
ENUM_CONST(NodeKind__ANNOTATION, 27);
