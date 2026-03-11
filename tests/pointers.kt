struct Box {
    value: i32,
}

fn test_deref_assign() {
    let p = malloc(8)
    @p = 42
    if @p == 42 {
        puts("deref assign: ok")
    } else {
        puts("FAIL deref assign")
    }
    free(p)
}

fn test_struct_as_pointer() {
    let b = Box { value: 42 }
    if b.value == 42 {
        puts("struct pointer: ok")
    } else {
        puts("FAIL struct pointer")
    }
}

fn mutate_box(b: *Box, val: i32) {
    b.value = val
}

fn test_pass_and_mutate() {
    let b = Box { value: 0 }
    mutate_box(b, 99)
    if b.value == 99 {
        puts("pass and mutate: ok")
    } else {
        puts("FAIL pass and mutate")
    }
}

fn test_pointer_arith() {
    let buf = [32]
    buf[0] = 65  // A
    buf[1] = 66  // B
    buf[2] = 67  // C
    buf[3] = 0
    let p = buf + 1
    if p[0] == 66 && p[1] == 67 {
        puts("pointer arith: ok")
    } else {
        puts("FAIL pointer arith")
    }
}

fn test_addr_of() {
    let mut x = 10
    let p = &mut x
    @p = 20
    if x == 20 {
        puts("addr of: ok")
    } else {
        puts("FAIL addr of")
    }
}

fn test_malloc_array() {
    let arr = malloc(4 * 8)
    arr[[0]] = 100
    arr[[1]] = 200
    arr[[2]] = 300
    arr[[3]] = 400
    if arr[[0]] + arr[[3]] == 500 {
        puts("malloc array: ok")
    } else {
        puts("FAIL malloc array")
    }
    free(arr)
}

fn main() {
    test_deref_assign()
    test_struct_as_pointer()
    test_pass_and_mutate()
    test_pointer_arith()
    test_addr_of()
    test_malloc_array()
}
