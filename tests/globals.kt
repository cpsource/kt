let MAX: i32 = 100
let mut counter: i32 = 0

fn increment() {
    counter = counter + 1
}

fn test_global_const() {
    if MAX == 100 {
        puts("global const: ok")
    } else {
        puts("FAIL global const")
    }
}

fn test_global_mut() {
    counter = 0
    increment()
    increment()
    increment()
    if counter == 3 {
        puts("global mut: ok")
    } else {
        puts("FAIL global mut")
    }
}

fn main() {
    test_global_const()
    test_global_mut()
}
