//! TLS crypto provider selection.
//!
//! `matrix-sdk` depends on `reqwest` with its `rustls` feature, which resolves
//! to `__rustls-aws-lc-rs` and therefore enables `rustls/aws_lc_rs`. Cargo
//! features are additive, so that cannot be turned off from here — aws-lc-rs is
//! compiled and linked no matter what we do in our own manifest.
//!
//! That matters because aws-lc-rs crashes on Android devices with 16 KB memory
//! pages, which Google Play now requires support for:
//! <https://github.com/matrix-org/matrix-rust-sdk/issues/6442> (still open).
//!
//! Since we additionally enable `rustls/ring`, rustls sees two providers and
//! refuses to pick one implicitly — any `ClientConfig::builder()` call would
//! panic with "no process-level CryptoProvider available". Installing ring
//! explicitly both fixes that and makes ring the provider actually used for
//! every TLS handshake, including on Android.
//!
//! Caveat to re-check at M2: this makes ring the *active* provider, but
//! aws-lc-rs is still linked into the binary. If a real 16 KB-page device still
//! crashes at load time, the remaining lever is a `[patch.crates-io]` entry
//! forcing `reqwest`'s `rustls-no-provider` feature.

use std::sync::{Once, OnceLock};

/// Set once `install_ring_provider` has run, recording whether *our* ring
/// provider is the one that won the install race.
static RING_INSTALLED: OnceLock<bool> = OnceLock::new();

/// Installs ring as the process-wide rustls crypto provider.
///
/// Must run before any TLS is set up. Calling it twice is harmless: the second
/// install fails, and we keep the first result.
pub fn install_ring_provider() {
    // `Once` rather than a bare call: this runs from every `Client` build and
    // from many tests at once, and the install can only succeed for whichever
    // caller gets there first. Without serialising, a losing caller's
    // `is_ok() == false` could land in `RING_INSTALLED` *after* the winning
    // caller's `true` — recording "ring is not active" for a process where it
    // demonstrably is. That raced in the test suite under parallel threads.
    //
    // Serialising also stops the second and later calls logging a warning
    // about an install they were never going to win.
    static INSTALL_ONCE: Once = Once::new();

    INSTALL_ONCE.call_once(|| {
        let installed = rustls::crypto::ring::default_provider()
            .install_default()
            .is_ok();

        if !installed {
            tracing::warn!(
                "another rustls crypto provider was installed before ours; ring is not active"
            );
        }

        let _ = RING_INSTALLED.set(installed);
    });
}

/// Which crypto provider is actually in force, for diagnostics.
///
/// `"other"` means some provider is installed but it is not ours — on Android
/// that would mean aws-lc-rs is live and issue #6442 applies.
pub fn active_provider() -> &'static str {
    match RING_INSTALLED.get() {
        Some(true) => "ring",
        Some(false) => "other",
        None => "none",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_becomes_the_active_provider() {
        install_ring_provider();

        assert_eq!(active_provider(), "ring");
        assert!(
            rustls::crypto::CryptoProvider::get_default().is_some(),
            "rustls must have a process-level provider, or ClientConfig::builder() panics"
        );
    }
}
