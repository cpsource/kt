# kt Language Specification

## Overview

kt is a systems programming language targeting x86-64/AMD64 machines. Programs compile to statically-linked ELF binaries via musl libc. The compiler is self-hosting: `ktc` (written in C) compiles `kt-src/*.kt` to produce `ktc-kt`.

## Implementation Notes

All values are 8 bytes at runtime (pointers, integers, booleans). Structs are heap-allocated via `malloc`, with each field occupying 8 bytes. There is no garbage collector; memory is managed manually or via arenas. Logical operators `&&` and `||` evaluate both sides (no short-circuit evaluation). Pointer arithmetic `p + 1` adds 1 byte, not `sizeof(*p)`.

## Lexical Elements

### Keywords
```
fn      let     mut     return
if      else    match   for
in      while   break   continue
struct  enum    impl    type
true    false
```

### Identifiers
Identifiers start with a letter or underscore, followed by letters, digits, or underscores.
```
foo  _bar  my_var  Point2D
```

### Literals

**Integer literals:**
```
42          // decimal
0xFF        // hexadecimal
0b1010      // binary
0o77        // octal
```

**String literals:**
```
"hello, world"
"line one\nline two"    // escape sequences: \n \t \r \\ \" \0
```

**Boolean literals:**
```
true
false
```

### Operators
```
+  -  *  /  %          // arithmetic
== != < > <= >=        // comparison
&& || !                // logical (non-short-circuit)
& | ^ ~ << >>         // bitwise
=                      // assignment
->                     // return type annotation
=>                     // match arm
..                     // range
```

### Punctuation
```
( )  { }  [ ]  [[ ]]  // grouping / indexing
,    :                  // separators (semicolons are ignored)
@                       // dereference / annotations
.                       // member access
::                      // path separator (Enum::Variant)
```

### Comments
```
// single-line comment

/* multi-line
   comment */
```

## Types

### Primitive Types
| Type | Description |
|------|-------------|
| `i32` | Signed 32-bit integer (stored as 8 bytes at runtime) |
| `u8` | Unsigned byte |
| `u64` | Unsigned 64-bit integer |
| `bool` | `true` or `false` (8 bytes at runtime) |
| `&str` | Pointer to null-terminated string |

### Pointer Types
```
*T          // raw pointer to T
&T          // immutable reference to T
&mut T      // mutable reference to T
```

### Array Types
```
[T; N]      // fixed-size array type annotation (used in globals)
```

### Buffer Declarations
```
let buf = [1024]        // allocate 1024 bytes on the stack
```

## Variables and Mutability

### Immutable by default
```
let x = 5
x = 10                  // ERROR: x is immutable

let mut y = 5
y = 10                  // OK: y is mutable
```

### Optional type annotations
```
let x = 42              // type inferred
let mut y: i32 = 0      // explicit type
let mut p: &str = 0     // null pointer
```

### Top-level globals
```
let MAX_SIZE: i32 = 128
let mut count: i32 = 0
let mut table: [&str; 64]   // global array
```

## Functions

```
fn add(a: i32, b: i32) -> i32 {
    return a + b
}

fn greet(name: &str) {
    puts(name)
}
```

The last expression in a function body is used as the implicit return value. Up to 6 arguments are passed in registers (System V AMD64 ABI: `%rdi`, `%rsi`, `%rdx`, `%rcx`, `%r8`, `%r9`); additional arguments go on the stack.

### Parameters
```
fn foo(x: i32, s: &str, p: *AstNode, a: &mut Arena) { }
```
Parameter types can be plain types, references (`&T`, `&mut T`), or pointers (`*T`).

## Control Flow

### if/else (expression-based)
```
if condition {
    // then
} else {
    // else
}

// As expression:
let x = if a > b { a } else { b }

// Chained:
if x == 0 {
    // ...
} else if x == 1 {
    // ...
} else {
    // ...
}
```

### while loops
```
while condition {
    // body
    if done { break }
    if skip { continue }
}
```

### for-range loops
```
for i in 0..10 {
    // i goes from 0 to 9
}
```

### match
```
match value {
    0 => { handle_zero() }
    1 => { handle_one() }
    TokenKind::EOF => { handle_eof() }
}
```
Match arms use `=>` and can be blocks or single expressions. Patterns can be integer literals, identifiers, or qualified paths (`Enum::Variant`).

### break and continue
`break` exits the innermost loop. `continue` jumps to the next iteration.

## Structs

### Definition
```
struct Point {
    x: i32,
    y: i32,
}
```
All struct fields are 8 bytes. Field offset = field index * 8.

### Struct literals
```
let p = Point { x: 10, y: 20 }
```
Struct literals allocate on the heap via `malloc`. The result is a pointer to the struct.

### Field access
```
let x = p.x
p.y = 30
```

### Nested access
```
let name = node.loc.file
```

## Enums

### Definition
```
enum Color {
    RED,
    GREEN,
    BLUE,
}
```
Variants are assigned sequential integer values starting from 0.

### Qualified paths
```
let c = Color::RED      // value 0
let k = TokenKind::EOF  // value 56
```

When referencing enum variants across compilation units, the compiler emits `movq EnumName__Variant(%rip), %rax`, and the runtime provides these as global constants.

## Indexing

### Byte indexing `[ ]`
```
let c = str[i]          // load 1 byte (zero-extended to 8 bytes)
str[i] = 0              // store 1 byte
```

### Word indexing `[[ ]]`
```
let item = arr[[i]]     // load 8 bytes (pointer/integer array access)
arr[[i]] = value        // store 8 bytes
```

## Expressions

### Binary operators (by precedence, lowest to highest)
| Precedence | Operators |
|-----------|-----------|
| 2 | `\|\|` |
| 3 | `&&` |
| 4 | `\|` |
| 5 | `^` |
| 6 | `&` |
| 7 | `== !=` |
| 8 | `< > <= >=` |
| 9 | `<< >>` |
| 10 | `+ -` |
| 11 | `* / %` |

All binary operators are left-associative.

### Unary operators
```
-x          // negate
!x          // logical not
~x          // bitwise not
@p          // dereference
&x          // address of
&mut x      // mutable address of
```

### Parenthesized expressions
```
let x = (a + b) * c
```

### Function calls
```
puts("hello")
fprintf(stderr, "%s:%ld\n", file, line)
let p = malloc(size)
```

### Assignment
```
x = 10
arr[[i]] = value
node.field = expr
@p = 42             // dereference assignment
```

## Preprocessor

The compiler supports `#include` directives:
```
#include "types.kth"
```
Includes are resolved relative to the directory of the including file and expanded inline before parsing. Recursive includes are supported.

## Annotations

### @microparse -- AI-assisted code generation
```
@microparse("handle positive, zero, and negative cases")
fn classify(x: i32) -> &str { }
```
At compile time, the compiler sends the prompt and function signature to the Claude API. The generated function body is spliced into the AST and compiled normally. Results are cached in `.gen` files keyed by a hash of the prompt and signature.

Compiler flags:
- `--skip-microparse` -- use cached results only, error if no cache exists
- `--microparse-refresh` -- force re-generation, ignore cache

Requires `ANTHROPIC_API_KEY` environment variable.

## Static Analysis

### Escape analysis
The compiler warns when a function returns a pointer to a stack-allocated buffer:
```
fn bad() -> &str {
    let buf = [64]
    return buf              // ERROR: returning pointer to stack-allocated variable
}
```

## Compiler Diagnostics

- Error format: `file:line:col: error: message`
- All errors are fatal (compilation stops at first error)

## Grammar

```
program     = decl*
decl        = annotation fn_def
            | fn_def
            | struct_def
            | enum_def
            | let_decl

annotation  = "@" IDENT "(" STRING ")"
fn_def      = "fn" IDENT "(" params? ")" ("->" type)? block
params      = param ("," param)*
param       = IDENT ":" type
struct_def  = "struct" IDENT "{" (field ",")* "}"
field       = IDENT ":" type
enum_def    = "enum" IDENT "{" (IDENT ","?)* "}"
let_decl    = "let" "mut"? IDENT (":" type)? ("=" (expr | "[" INT "]"))?

type        = IDENT
            | "&" type
            | "&" "mut" type
            | "*" type
            | "[" type ";" INT "]"

block       = "{" stmt* "}"
stmt        = "return" expr?
            | "let" "mut"? IDENT (":" type)? ("=" (expr | "[" INT "]"))?
            | "while" expr block
            | "for" IDENT "in" expr ".." expr block
            | "match" expr "{" match_arm* "}"
            | "if" expr block ("else" (if_expr | block))?
            | "break"
            | "continue"
            | expr ("=" expr)?            // expr_stmt or assignment

match_arm   = pattern "=>" (block | expr) ","?
pattern     = IDENT ("::" IDENT)?
            | INT

expr        = expr_bp(0)                 // Pratt parser, min binding power 0
primary     = "-" expr                   // unary negate
            | "!" expr                   // logical not
            | "~" expr                   // bitwise not
            | "@" expr                   // dereference
            | "&" "mut"? expr            // address of
            | "(" expr ")"              // grouping
            | "if" expr block ("else" (if_expr | block))?
            | block                      // block expression
            | STRING
            | INT
            | "true" | "false"
            | IDENT "::" IDENT           // path (Enum::Variant)
            | IDENT "(" args? ")"        // call
            | IDENT "{" field_init* "}"  // struct literal
            | IDENT                      // variable

postfix     = expr "." IDENT            // member access
            | expr "." IDENT "(" args? ")"  // method call
            | expr "[" expr "]"          // byte index
            | expr "[[" expr "]]"        // word index

args        = expr ("," expr)*
field_init  = IDENT ":" expr ","?
```
