#include "include_helper.kth"
fn main() {
    if MAGIC == 12345 {
        puts("include const: ok")
    } else {
        puts("FAIL include const")
    }
    if helper_add(3, 4) == 7 {
        puts("include fn: ok")
    } else {
        puts("FAIL include fn")
    }
}
