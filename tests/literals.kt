fn test_hex() {
    if 0xFF == 255 && 0x10 == 16 && 0xDEAD == 57005 {
        puts("hex: ok")
    } else {
        puts("FAIL hex")
    }
}

fn test_binary() {
    if 0b1010 == 10 && 0b11111111 == 255 && 0b1 == 1 {
        puts("binary: ok")
    } else {
        puts("FAIL binary")
    }
}

fn test_octal() {
    if 0o77 == 63 && 0o10 == 8 && 0o777 == 511 {
        puts("octal: ok")
    } else {
        puts("FAIL octal")
    }
}

fn test_bool() {
    let t = true
    let f = false
    if t && !f {
        puts("bool: ok")
    } else {
        puts("FAIL bool")
    }
}

fn test_zero() {
    if 0 == 0 {
        puts("zero: ok")
    } else {
        puts("FAIL zero")
    }
}

fn main() {
    test_hex()
    test_binary()
    test_octal()
    test_bool()
    test_zero()
}
