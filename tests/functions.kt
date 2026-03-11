fn square(x: i32) -> i32 {
    return x * x
}

fn max(a: i32, b: i32) -> i32 {
    if a > b { return a }
    return b
}

fn fib(n: i32) -> i32 {
    if n <= 1 { return n }
    return fib(n - 1) + fib(n - 2)
}

fn sum_six(a: i32, b: i32, c: i32, d: i32, e: i32, f: i32) -> i32 {
    return a + b + c + d + e + f
}

fn test_basic() {
    if square(7) == 49 {
        puts("square: ok")
    } else {
        puts("FAIL square")
    }
}

fn test_early_return() {
    if max(3, 7) == 7 && max(9, 2) == 9 && max(5, 5) == 5 {
        puts("early return: ok")
    } else {
        puts("FAIL early return")
    }
}

fn test_recursion() {
    // fib(10) = 55
    if fib(10) == 55 {
        puts("recursion: ok")
    } else {
        puts("FAIL recursion")
    }
}

fn test_six_args() {
    if sum_six(1, 2, 3, 4, 5, 6) == 21 {
        puts("six args: ok")
    } else {
        puts("FAIL six args")
    }
}

fn test_implicit_return() {
    let x = square(5)
    if x == 25 {
        puts("implicit return: ok")
    } else {
        puts("FAIL implicit return")
    }
}

fn main() {
    test_basic()
    test_early_return()
    test_recursion()
    test_six_args()
    test_implicit_return()
}
