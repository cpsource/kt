fn test_byte_index() {
    let s = "ABCD"
    if s[0] == 65 && s[1] == 66 && s[2] == 67 && s[3] == 68 {
        puts("byte index read: ok")
    } else {
        puts("FAIL byte index read")
    }
}

fn test_byte_index_write() {
    let buf = [16]
    buf[0] = 72   // H
    buf[1] = 105  // i
    buf[2] = 0
    if strcmp(buf, "Hi") == 0 {
        puts("byte index write: ok")
    } else {
        puts("FAIL byte index write")
    }
}

fn test_word_index() {
    let arr = malloc(5 * 8)
    arr[[0]] = 10
    arr[[1]] = 20
    arr[[2]] = 30
    arr[[3]] = 40
    arr[[4]] = 50
    let sum = arr[[0]] + arr[[1]] + arr[[2]] + arr[[3]] + arr[[4]]
    if sum == 150 {
        puts("word index: ok")
    } else {
        puts("FAIL word index")
    }
    free(arr)
}

fn test_string_as_array() {
    let s = "hello"
    let mut count = 0
    let mut i = 0
    while s[i] != 0 {
        count = count + 1
        i = i + 1
    }
    if count == 5 {
        puts("string iteration: ok")
    } else {
        puts("FAIL string iteration")
    }
}

fn main() {
    test_byte_index()
    test_byte_index_write()
    test_word_index()
    test_string_as_array()
}
