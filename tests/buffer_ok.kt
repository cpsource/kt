fn main() {
    let buf = [64]
    sprintf(buf, "%s %s", "Hello", "buffer!")
    puts(buf)
}
