struct Point {
    x: i32,
    y: i32,
}

struct Rect {
    origin: *Point,
    w: i32,
    h: i32,
}

fn test_struct_create() {
    let p = Point { x: 10, y: 20 }
    if p.x == 10 && p.y == 20 {
        puts("struct create: ok")
    } else {
        puts("FAIL struct create")
    }
}

fn test_struct_mutate() {
    let p = Point { x: 1, y: 2 }
    p.x = 100
    p.y = 200
    if p.x == 100 && p.y == 200 {
        puts("struct mutate: ok")
    } else {
        puts("FAIL struct mutate")
    }
}

fn test_nested_struct() {
    let o = Point { x: 5, y: 10 }
    let r = Rect { origin: o, w: 100, h: 50 }
    if r.origin.x == 5 && r.origin.y == 10 && r.w == 100 {
        puts("nested struct: ok")
    } else {
        puts("FAIL nested struct")
    }
}

fn make_point(x: i32, y: i32) -> *Point {
    return Point { x: x, y: y }
}

fn test_struct_return() {
    let p = make_point(42, 99)
    if p.x == 42 && p.y == 99 {
        puts("struct return: ok")
    } else {
        puts("FAIL struct return")
    }
}

fn main() {
    test_struct_create()
    test_struct_mutate()
    test_nested_struct()
    test_struct_return()
}
