enum Direction {
    NORTH,
    SOUTH,
    EAST,
    WEST,
}

fn dir_name(d: i32) -> &str {
    if d == 0 { return "north" }
    if d == 1 { return "south" }
    if d == 2 { return "east" }
    if d == 3 { return "west" }
    return "unknown"
}

fn test_enum_values() {
    if Direction::NORTH == 0 && Direction::SOUTH == 1 && Direction::EAST == 2 && Direction::WEST == 3 {
        puts("enum values: ok")
    } else {
        puts("FAIL enum values")
    }
}

fn test_enum_match() {
    let d = Direction::EAST
    let mut buf = [64]
    match d {
        Direction::NORTH => { sprintf(buf, "match: north") }
        Direction::SOUTH => { sprintf(buf, "match: south") }
        Direction::EAST  => { sprintf(buf, "match: east") }
        Direction::WEST  => { sprintf(buf, "match: west") }
    }
    if strcmp(buf, "match: east") == 0 {
        puts("enum match: ok")
    } else {
        puts("FAIL enum match")
    }
}

fn test_enum_as_arg() {
    let name = dir_name(Direction::WEST)
    if strcmp(name, "west") == 0 {
        puts("enum as arg: ok")
    } else {
        puts("FAIL enum as arg")
    }
}

fn main() {
    test_enum_values()
    test_enum_match()
    test_enum_as_arg()
}
