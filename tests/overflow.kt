fn main() {
    let buf = [16]
    sprintf(buf, "%s%s%s%s%s%s%s%s", "AAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBB", "CCCCCCCCCCCCCCCC", "DDDDDDDDDDDDDDDD", "EEEEEEEEEEEEEEEE", "FFFFFFFFFFFFFFFF", "GGGGGGGGGGGGGGGG", "HHHHHHHHHHHHHHHH")
    puts(buf)
    puts("if you see this, overflow was not caught")
}
