//! The bindings generator, as a binary of this crate.
//!
//! UniFFI's generator has to be the *same version* as the `uniffi` crate the
//! library was built with, or the generated Swift will not match the
//! scaffolding it calls into — and the failure mode is a link error or, worse,
//! a mismatched ABI. Building it here rather than installing
//! `uniffi-bindgen` globally makes that impossible: it takes its version from
//! this crate's Cargo.toml like everything else.
fn main() {
    uniffi::uniffi_bindgen_main()
}
