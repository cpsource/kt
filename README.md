# kt

A systems programming language for x86-64 that combines low-level control with modern safety guarantees. Compiles to static ELF binaries via musl libc.

## Goals

- **Memory safe** -- bounds checking, no null pointers, region-based memory management
- **Type safe** -- strong static types with inference, no implicit coercions, algebraic data types with exhaustive pattern matching
- **Arithmetic safe** -- checked overflow by default, division by zero is a caught error
- **Immutable by default** -- opt into mutability with `mut`
- **Concurrency safe** -- no shared mutable state without explicit synchronization
- **Close to the metal** -- direct x86-64 codegen, SIMD as first-class types, static linking via musl
- **AI-assisted** -- `@microparse` annotation generates code via Claude API at compile time

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

Prerequisites: GCC, GNU make, GNU binutils (as, ld).

```bash
# Build musl libc (one-time)
make musl

# Build the kt compiler
make ktc

# Compile and run the hello world test
make test
```

## Usage

```bash
# Compile a .kt file to x86-64 assembly
./build/ktc program.kt -o program.s

# Assemble and link against musl (the Makefile handles this for tests)
as --64 -o program.o program.s
ld -static -o program \
    build/musl/lib/crt1.o \
    build/musl/lib/crti.o \
    program.o \
    --start-group build/musl/lib/libc.a --end-group \
    build/musl/lib/crtn.o

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
| `-o <file>` | **(required)** Output path for the generated x86-64 assembly file. |
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
  src/          # compiler source (C)
  tests/        # test .kt programs
  musl/         # musl libc (v1.2.5)
  build/        # build artifacts
  Makefile      # build system
```

## Architecture

The compiler is written in C and follows a traditional pipeline:

```
source.kt --> Lexer --> Parser --> AST --> @microparse pass --> Codegen --> x86-64 assembly
```

- **Lexer** -- tokenizes kt source
- **Parser** -- recursive descent, builds AST
- **@microparse** -- calls Claude API for annotated functions, splices generated code into AST
- **Codegen** -- emits GAS x86-64 assembly (System V AMD64 ABI)
- **Linking** -- static linking with musl's crt1.o + libc.a produces standalone ELF binaries

## Documentation

- [README.plan](README.plan) -- implementation plan and milestones
- [README-language.md](README-language.md) -- language specification

## Status

Milestone 1: Hello World ELF -- in progress.
