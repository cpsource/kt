fn test_if_expr() {
    let x = if 3 > 2 { 10 } else { 20 }
    if x == 10 {
        puts("if expr: ok")
    } else {
        puts("FAIL if expr")
    }
}

fn test_if_expr_else() {
    let x = if 1 > 2 { 10 } else { 20 }
    if x == 20 {
        puts("if expr else: ok")
    } else {
        puts("FAIL if expr else")
    }
}

fn test_if_expr_nested() {
    let a = 5
    let label = if a < 0 { "neg" } else if a == 0 { "zero" } else { "pos" }
    if strcmp(label, "pos") == 0 {
        puts("if expr nested: ok")
    } else {
        puts("FAIL if expr nested")
    }
}

fn abs(x: i32) -> i32 {
    return if x < 0 { 0 - x } else { x }
}

fn test_if_expr_in_return() {
    if abs(-7) == 7 && abs(3) == 3 {
        puts("if expr return: ok")
    } else {
        puts("FAIL if expr return")
    }
}

fn main() {
    test_if_expr()
    test_if_expr_else()
    test_if_expr_nested()
    test_if_expr_in_return()
}
