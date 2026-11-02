//! toadGALLERY crypto seam (net-new code, not vendored from Ente).
//!
//! The vendored photos-side modules call `ente_core::crypto::random_bytes` at
//! exactly two sites (`face_clustering/mod.rs` for cluster-id generation and
//! `ml_store/face_distances.rs`). Rather than vendoring all of `ente-core`,
//! this module reproduces that one function byte-for-byte in semantics: fill a
//! buffer from the OS CSPRNG via `getrandom::fill`, panicking if the OS cannot
//! supply cryptographic randomness (upstream's documented intent). The
//! vendored modules import it via `use crate::crypto::random_bytes;` — the
//! only edit made to their code.
//!
//! Upstream reference: `rust/crates/core/src/crypto/types.rs` @
//! `423b2d80d9ec89d45f5680cb1e1d777bac225f41`.

/// Returns `len` cryptographically secure random bytes.
pub fn random_bytes(len: usize) -> Vec<u8> {
    let mut buf = vec![0u8; len];
    fill_random(&mut buf);
    buf
}

fn fill_random(buf: &mut [u8]) {
    #[expect(
        clippy::expect_used,
        reason = "Stop if the OS cannot supply cryptographic randomness"
    )]
    getrandom::fill(buf).expect("failed to generate random bytes");
}
