fn main() {
    let buf = [16]
    sprintf(buf, "AAAAAAAABBBBBBBBCCCCCCCCDDDDDDDD")
    puts(buf)
    puts("survived the overflow")
}
