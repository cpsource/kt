fn test_break() {
    let mut i = 0
    while i < 100 {
        if i == 7 { break }
        i = i + 1
    }
    if i == 7 {
        puts("break: ok")
    } else {
        puts("FAIL break")
    }
}

fn test_continue() {
    let mut sum = 0
    let mut i = 0
    while i < 10 {
        i = i + 1
        if i % 2 == 0 { continue }
        sum = sum + i  // only odd: 1+3+5+7+9 = 25
    }
    if sum == 25 {
        puts("continue: ok")
    } else {
        puts("FAIL continue")
    }
}

fn test_nested_loops() {
    let mut count = 0
    for i in 0..5 {
        for j in 0..5 {
            if j == 3 { break }
            count = count + 1
        }
    }
    // 5 outer * 3 inner (j=0,1,2) = 15
    if count == 15 {
        puts("nested loops: ok")
    } else {
        puts("FAIL nested loops")
    }
}

fn test_if_else_chain() {
    let x = 42
    let mut result = 0
    if x == 1 {
        result = 1
    } else if x == 42 {
        result = 42
    } else {
        result = -1
    }
    if result == 42 {
        puts("if-else chain: ok")
    } else {
        puts("FAIL if-else chain")
    }
}

fn test_while_with_break_continue() {
    // find first multiple of 7 > 50
    let mut n = 0
    while n < 1000 {
        n = n + 1
        if n <= 50 { continue }
        if n % 7 == 0 { break }
    }
    if n == 56 {
        puts("while break+continue: ok")
    } else {
        puts("FAIL while break+continue")
    }
}

fn main() {
    test_break()
    test_continue()
    test_nested_loops()
    test_if_else_chain()
    test_while_with_break_continue()
}
