fn main() {
    assert_eq!(tracebox::panic_strategy(), tracebox::PanicStrategy::Abort);
    panic!("abort-mode validation probe");
}
