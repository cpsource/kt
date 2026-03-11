fn test_comparison() {
    if 3 != 4 && !(3 != 3) {
        puts("!= : ok")
    } else {
        puts("FAIL !=")
    }
    if 3 < 4 && !(4 < 3) && !(3 < 3) {
        puts("<  : ok")
    } else {
        puts("FAIL <")
    }
    if 4 > 3 && !(3 > 4) && !(3 > 3) {
        puts(">  : ok")
    } else {
        puts("FAIL >")
    }
    if 3 <= 4 && 3 <= 3 && !(4 <= 3) {
        puts("<= : ok")
    } else {
        puts("FAIL <=")
    }
    if 4 >= 3 && 3 >= 3 && !(3 >= 4) {
        puts(">= : ok")
    } else {
        puts("FAIL >=")
    }
}

fn test_divmod() {
    if 10 / 3 == 3 && 10 % 3 == 1 {
        puts("div/mod: ok")
    } else {
        puts("FAIL div/mod")
    }
}

fn test_logical() {
    // && and || (non-short-circuit)
    if true && true && !(true && false) {
        puts("&& : ok")
    } else {
        puts("FAIL &&")
    }
    if (true || false) && (false || true) && !(false || false) {
        puts("|| : ok")
    } else {
        puts("FAIL ||")
    }
    if !false && !!true {
        puts("!  : ok")
    } else {
        puts("FAIL !")
    }
}

fn test_bitwise() {
    // AND
    if (0xFF & 0x0F) == 0x0F {
        puts("&  : ok")
    } else {
        puts("FAIL &")
    }
    // OR
    if (0xF0 | 0x0F) == 0xFF {
        puts("|  : ok")
    } else {
        puts("FAIL |")
    }
    // XOR
    if (0xFF ^ 0x0F) == 0xF0 {
        puts("^  : ok")
    } else {
        puts("FAIL ^")
    }
    // Shift
    if (1 << 8) == 256 && (256 >> 4) == 16 {
        puts("shifts: ok")
    } else {
        puts("FAIL shifts")
    }
    // Bitwise NOT (lower 8 bits)
    if (~0 & 0xFF) == 0xFF {
        puts("~  : ok")
    } else {
        puts("FAIL ~")
    }
}

fn test_unary_negate() {
    let x = 5
    let y = -x
    if y + 5 == 0 {
        puts("negate: ok")
    } else {
        puts("FAIL negate")
    }
}

fn test_precedence() {
    // * before +
    if 2 + 3 * 4 == 14 {
        puts("prec *+: ok")
    } else {
        puts("FAIL prec *+")
    }
    // parens override
    if (2 + 3) * 4 == 20 {
        puts("prec (): ok")
    } else {
        puts("FAIL prec ()")
    }
    // comparison lower than arithmetic
    if 2 + 3 == 5 {
        puts("prec cmp: ok")
    } else {
        puts("FAIL prec cmp")
    }
}

fn main() {
    test_comparison()
    test_divmod()
    test_logical()
    test_bitwise()
    test_unary_negate()
    test_precedence()
}
