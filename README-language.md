# kt Language Specification

## Overview

kt is a systems programming language targeting x86-64/AMD64 machines. It prioritizes safety, clarity, and direct hardware access. Programs compile to statically-linked ELF binaries via musl libc.

## Lexical Elements

### Keywords
```
fn      let     mut     return
if      else    match   for
while   break   continue
struct  enum    impl    type
true    false   none    some
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
1_000_000   // underscores for readability
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
&& || !                // logical
& | ^ ~ << >>         // bitwise
=                      // assignment
->                     // return type annotation
```

### Punctuation
```
( )  { }  [ ]          // grouping
,    :    ;             // separators
@                       // annotations
.                       // member access
::                      // path separator
```

### Comments
```
// single-line comment

/* multi-line
   comment */
```

## Types

### Primitive Types
| Type | Size | Description |
|------|------|-------------|
| `i8`, `i16`, `i32`, `i64` | 1-8 bytes | Signed integers |
| `u8`, `u16`, `u32`, `u64` | 1-8 bytes | Unsigned integers |
| `f32`, `f64` | 4-8 bytes | IEEE 754 floating point |
| `bool` | 1 byte | `true` or `false` |
| `str` | pointer+length | UTF-8 string slice |
| `void` | 0 bytes | No value |

### Pointer Types
```
*T          // raw pointer to T (unsafe)
&T          // immutable reference to T
&mut T      // mutable reference to T
```

### Array Types
```
[T; N]      // fixed-size array of N elements of type T
[T]         // slice (pointer + length)
```

### Option Type
No null pointers. Use `Option` for values that may be absent:
```
Option<T>   // either some(value) or none
```

### SIMD Types (first-class)
```
v128i8      // 16 x i8  (128-bit)
v256f32     // 8 x f32  (256-bit AVX)
v512f64     // 8 x f64  (512-bit AVX-512)
```
Lane accesses are bounds-checked.

## Type System

### Strong static typing with inference
```
let x = 42              // inferred as i32
let y: f64 = 3.14       // explicit annotation
let z = x + 1           // inferred from x
```

### No implicit coercions
```
let a: i32 = 10
let b: f64 = a           // COMPILE ERROR: no implicit i32 -> f64
let b: f64 = a.to_f64()  // OK: explicit conversion
```

### Algebraic Data Types
```
enum Shape {
    Circle(f64),                    // radius
    Rectangle(f64, f64),            // width, height
    Triangle(f64, f64, f64),        // three sides
}
```

### Tagged Unions (no raw C unions)
All enums are tagged. The compiler tracks which variant is active.

## Variables and Mutability

### Immutable by default
```
let x = 5
x = 10                  // COMPILE ERROR: x is immutable

let mut y = 5
y = 10                  // OK: y is mutable
```

### No uninitialized variables
```
let x: i32              // COMPILE ERROR: must initialize
let x: i32 = 0          // OK
```

### No implicit global state
All state must be passed explicitly through function parameters.

## Functions

```
fn add(a: i32, b: i32) -> i32 {
    a + b               // last expression is the return value
}

fn greet(name: str) {
    puts("Hello, ")
    puts(name)
}
```

### All return paths must be handled
```
fn abs(x: i32) -> i32 {
    if x >= 0 { x }
    // COMPILE ERROR: missing else branch
}

fn abs(x: i32) -> i32 {
    if x >= 0 { x }
    else { -x }         // OK: all paths return i32
}
```

### Unreachable code is a compile error
```
fn foo() -> i32 {
    return 5
    let x = 10          // COMPILE ERROR: unreachable code
}
```

## Control Flow

### if/else (expression-based)
```
let x = if condition { 1 } else { 2 }
```

### match (exhaustive pattern matching)
```
match shape {
    Circle(r) => 3.14159 * r * r,
    Rectangle(w, h) => w * h,
    Triangle(a, b, c) => {
        let s = (a + b + c) / 2.0
        (s * (s-a) * (s-b) * (s-c)).sqrt()
    }
}
// No fallthrough. Every variant must be handled or the compiler errors.
```

### Loops
```
while condition {
    // body
}

for i in 0..10 {
    // i goes from 0 to 9
}
```

## Integer Arithmetic

### Checked by default
```
let x: i32 = 2_147_483_647
let y = x + 1            // RUNTIME ERROR: integer overflow
```

### Explicit arithmetic modes
```
let a = x +% 1           // wrapping: silently wraps on overflow
let b = x +| 1           // saturating: clamps to max value
let c = x +? 1           // checked: returns Option<i32>
```

### Division by zero
```
let x = 10 / 0           // RUNTIME ERROR: division by zero (not a crash)
```

## Memory Safety

### Bounds checking
```
let arr = [1, 2, 3]
let x = arr[5]           // RUNTIME ERROR: index out of bounds
```

### Region/arena-based memory management
```
region r {
    let data = r.alloc(1024)    // allocated in region r
    // use data...
}   // entire region freed here, deterministically
```

### Ownership (for concurrency safety)
```
let data = Vec::new()
send(data, other_thread)
data.push(1)             // COMPILE ERROR: data has been moved
```

## Concurrency

### No shared mutable state without synchronization
```
let mut shared = 0
spawn {
    shared += 1          // COMPILE ERROR: shared mutable access across threads
}

let shared = Mutex::new(0)
spawn {
    let mut guard = shared.lock()
    *guard += 1          // OK: protected by mutex
}
```

## Annotations

### @microparse -- AI-assisted code generation
```
@microparse("handle positive, zero, and negative cases")
fn classify(x: i32) -> str { }
```
At compile time, the compiler sends the prompt and function signature to Claude API. The AI-generated function body is spliced into the AST and compiled normally. Results are cached in `.kt.gen` files.

## Structs and Methods

```
struct Point {
    x: f64,
    y: f64,
}

impl Point {
    fn new(x: f64, y: f64) -> Point {
        Point { x: x, y: y }
    }

    fn distance(self, other: Point) -> f64 {
        let dx = self.x - other.x
        let dy = self.y - other.y
        (dx*dx + dy*dy).sqrt()
    }
}
```

## Stack Overflow Detection

Guard pages are placed at the end of each thread's stack. Accessing beyond the stack limit triggers a fault that is caught and reported as a clear error, not a silent corruption.

## Compiler Diagnostics

- All warnings are errors by default
- Error format: `file:line:col: error: message`
- Exhaustiveness checking on match expressions
- Unreachable code detection
- Unused variable warnings

## Grammar (Milestone 1 -- minimal)

```
program    = (annotation? fn_def)*
annotation = "@" IDENT "(" STRING ")"
fn_def     = "fn" IDENT "(" params? ")" ("->" type)? block
params     = param ("," param)*
param      = IDENT ":" type
type       = IDENT
block      = "{" stmt* "}"
stmt       = expr_stmt | let_stmt | return_stmt
let_stmt   = "let" "mut"? IDENT (":" type)? "=" expr
return_stmt = "return" expr?
expr_stmt  = expr
expr       = call_expr | IDENT | STRING | INT | BOOL
call_expr  = IDENT "(" args? ")"
args       = expr ("," expr)*
```
