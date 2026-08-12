//! The Rust core.
//!
//! Architecture rule (docs/tech-stack.md): the Matrix client lives entirely
//! here. The webview is a dumb renderer — core state is streamed to Svelte
//! stores over Tauri events, and user intents come back down as Tauri commands.
//! Exactly one `matrix_sdk::Client` per logged-in account, owned by the core.
//!
//! Nothing here talks to Matrix yet; `session` is the seam where the client
//! will be owned once M0 login/sync lands.

pub mod auth;
pub mod dto;
pub mod error;
pub mod secrets;
pub mod session;
pub mod sync;
pub mod tls;
