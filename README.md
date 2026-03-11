# kt

A systems programming language for x86-64. Compiles to static ELF binaries via musl libc. The compiler is self-hosting: `ktc` (written in C) compiles `kt-src/*.kt` to produce `ktc-kt`.

## Features

- **Immutable by default** -- opt into mutability with `mut`
- **Close to the metal** -- direct x86-64 codegen or LLVM IR backend, static linking via musl
- **AI-assisted** -- `@microparse` annotation generates code via Claude API at compile time
- **Self-hosting** -- the compiler can compile itself
- **Escape analysis** -- warns when returning pointers to stack-allocated buffers

## Example

```kt
fn main() {
    puts("Hello, world!")
}
```

With AI-assisted code generation:

```kt
@microparse("handle positive, zero, and negative cases")
fn classify(x: i32) -> str { }
```

The compiler calls Claude to fill in the function body, caches the result, and compiles it.

## Building

Prerequisites: GCC, GNU make, GNU binutils (as, ld). For the LLVM backend: LLVM 18 (`llc-18`).

```bash
# Build musl libc (one-time)
make musl

# Build the C compiler
make ktc

# Build the self-hosted compiler (uses ktc to compile kt-src/*.kt)
make ktc-kt

# Run all tests with both compilers
make test

# Run tests with just one compiler
make test-ktc
make test-ktc-kt

# Run tests with the LLVM IR backend
make test-llvm        # C compiler, LLVM backend
make test-llvm-kt     # self-hosted compiler, LLVM backend
```

## Usage

```bash
# Compile a .kt file to x86-64 assembly
./build/ktc program.kt -o program.s

# Or compile to LLVM IR
./build/ktc program.kt -o program.ll --emit-llvm

# Assemble and link against musl (the Makefile handles this for tests)
as --64 -o program.o program.s
ld -static -o program \
    build/musl/lib/crt1.o \
    build/musl/lib/crti.o \
    program.o \
    --start-group build/musl/lib/libc.a --end-group \
    build/musl/lib/crtn.o

# For LLVM IR: compile .ll to .s first, then assemble and link as above
llc-18 program.ll -o program.s

./program
```

For `@microparse` support, set your API key:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./build/ktc program.kt -o program.s
```

Compiler flags:
- `--skip-microparse` -- skip AI generation, use cached results only
- `--microparse-refresh` -- force re-generation, ignore cache

## How To

### Compile a kt program

```bash
./build/ktc <input.kt> -o <output.s>
```

### Command-line switches

| Switch | Description |
|---|---|
| `-o <file>` | **(required)** Output path for the generated assembly (`.s`) or LLVM IR (`.ll`) file. |
| `--emit-llvm` | Emit LLVM IR (`.ll`) instead of x86-64 assembly. Use `llc-18` to compile to assembly. |
| `--microparse-refresh` | Force all `@microparse` annotations to re-call the Claude API, ignoring any cached `.kt.gen` files. |
| `--skip-microparse` | Skip API calls entirely. Uses cached results from `.kt.gen` files; errors if no cache exists for an annotation. |

### Assemble and link

The Makefile handles this for `make test`, but to do it manually:

```bash
as --64 -o program.o program.s
ld -static -o program \
    build/musl/lib/crt1.o \
    build/musl/lib/crti.o \
    program.o \
    --start-group build/musl/lib/libc.a --end-group \
    build/musl/lib/crtn.o
```

### Use @microparse

Set your API key and compile normally:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./build/ktc program.kt -o program.s
```

On first compile, the compiler calls Claude to generate function bodies for `@microparse`-annotated functions. Results are cached in `<source>.kt.gen` so subsequent compiles are instant.

To force regeneration:
```bash
./build/ktc program.kt -o program.s --microparse-refresh
```

To compile offline (cache only, no API calls):
```bash
./build/ktc program.kt -o program.s --skip-microparse
```

## Project Structure

```
kt/
  src/          # compiler source (C) — ktc
    codegen.c         # x86-64 assembly backend
    codegen_llvm.c    # LLVM IR backend
    main.c            # compiler driver
    ...
  kt-src/       # compiler source (kt) — ktc-kt (self-hosted)
    codegen.kt        # x86-64 assembly backend
    codegen_llvm.kt   # LLVM IR backend
    main.kt           # compiler driver
    ...
  tests/        # test .kt programs
  musl/         # musl libc (v1.2.5)
  build/        # build artifacts
    ktc          # C-built compiler
    kt-src/
      ktc-kt     # self-hosted compiler
  Makefile      # build system
```

## Architecture

The compiler follows a traditional pipeline:

```
source.kt --> Preprocessor --> Lexer --> Parser --> AST --> @microparse --> Escape analysis --> Codegen
```

- **Preprocessor** -- expands `#include` directives
- **Lexer** -- tokenizes kt source
- **Parser** -- Pratt parser (precedence climbing) for expressions, recursive descent for statements
- **@microparse** -- calls Claude API for annotated functions, splices generated code into AST
- **Escape analysis** -- warns on returning pointers to stack-allocated buffers
- **Codegen** -- two backends:
  - **x86-64** (default) -- emits GAS assembly directly (System V AMD64 ABI)
  - **LLVM IR** (`--emit-llvm`) -- emits textual `.ll` files, compiled to assembly via `llc-18`
- **Linking** -- static linking with musl's crt1.o + libc.a produces standalone ELF binaries

Two compiler implementations exist, each with both backends:
- **ktc** (`src/*.c`) -- the original C implementation
- **ktc-kt** (`kt-src/*.kt`) -- self-hosted, compiled by ktc

## Documentation

- [README-language.md](README-language.md) -- language specification
