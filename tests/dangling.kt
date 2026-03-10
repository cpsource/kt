fn get_buf() {
    let buf = [16]
    sprintf(buf, "dangling ptr!")
    return buf
}

fn main() {
    puts(get_buf())
    puts("did it survive?")
}
