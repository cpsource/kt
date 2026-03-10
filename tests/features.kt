enum Color {
    Red,
    Green,
    Blue,
}

struct Point {
    x: i64,
    y: i64,
}

fn test_if() {
    let x = 10
    if x > 5 {
        puts("if: x > 5 ✓")
    } else {
        puts("if: FAIL")
    }
}

fn test_while() {
    let mut i = 0
    while i < 3 {
        i = i + 1
    }
    if i == 3 {
        puts("while: counted to 3 ✓")
    } else {
        puts("while: FAIL")
    }
}

fn test_for() {
    let mut sum = 0
    for i in 0..5 {
        sum = sum + i
    }
    // 0+1+2+3+4 = 10
    if sum == 10 {
        puts("for: sum 0..5 = 10 ✓")
    } else {
        puts("for: FAIL")
    }
}

fn test_match() {
    let c = 1  // Green
    match c {
        0 => puts("match: Red")
        1 => puts("match: Green ✓")
        2 => puts("match: Blue")
    }
}

fn test_arithmetic() {
    let a = 7
    let b = 3
    if a + b == 10 {
        puts("arith: 7+3=10 ✓")
    }
    if a - b == 4 {
        puts("arith: 7-3=4 ✓")
    }
    if a * b == 21 {
        puts("arith: 7*3=21 ✓")
    }
}

fn add(a: i64, b: i64) -> i64 {
    return a + b
}

fn test_params() {
    let result = add(17, 25)
    if result == 42 {
        puts("params: add(17,25)=42 ✓")
    }
}

fn main() {
    test_if()
    test_while()
    test_for()
    test_match()
    test_arithmetic()
    test_params()
    puts("--- all feature tests done ---")
}
