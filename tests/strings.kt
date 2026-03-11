fn test_escape_sequences() {
    let s = "a\tb\nc"
    // 'a' = 97, '\t' = 9, 'b' = 98, '\n' = 10, 'c' = 99
    if s[0] == 97 && s[1] == 9 && s[2] == 98 && s[3] == 10 && s[4] == 99 {
        puts("escapes: ok")
    } else {
        puts("FAIL escapes")
    }
}

fn test_null_escape() {
    let s = "AB\0CD"
    // should have null at position 2
    if s[0] == 65 && s[1] == 66 && s[2] == 0 {
        puts("null escape: ok")
    } else {
        puts("FAIL null escape")
    }
}

fn test_streq() {
    if strcmp("hello", "hello") == 0 && strcmp("hello", "world") != 0 {
        puts("streq: ok")
    } else {
        puts("FAIL streq")
    }
}

fn test_sprintf() {
    let buf = [128]
    sprintf(buf, "%d + %d = %d", 3, 4, 7)
    if strcmp(buf, "3 + 4 = 7") == 0 {
        puts("sprintf: ok")
    } else {
        puts("FAIL sprintf")
    }
}

fn test_strlen() {
    if strlen("hello") == 5 && strlen("") == 0 {
        puts("strlen: ok")
    } else {
        puts("FAIL strlen")
    }
}

fn main() {
    test_escape_sequences()
    test_null_escape()
    test_streq()
    test_sprintf()
    test_strlen()
}
