// `timeline::FocusedTimeline::subscribe`'s spawned task nests
// `Timeline::subscribe`'s stream type (itself wrapping `TimelineWithDropHandle`
// around an `eyeball_im` subscriber stream) inside a `pin_mut!`'d `while let`
// loop inside an `async move` block passed to `tokio::spawn` — deep enough
// that computing its layout overflows rustc's default query recursion limit.
//
// It travelled here with the code that needs it: the limit belongs to the
// crate holding the timeline, not to whichever host happens to embed it.
#![recursion_limit = "256"]

//! The Rust core.
//!
//! Architecture rule (docs/tech-stack.md): the Matrix client lives entirely
//! here. The webview is a dumb renderer — core state is streamed to Svelte
//! stores over Tauri events, and user intents come back down as Tauri commands.
//! Exactly one `matrix_sdk::Client` per logged-in account, owned by the core.
//!
//! `session` owns the client; `commands` is the seam where the webview's
//! Tauri invocations reach it.

pub mod attachments;
pub mod auth;
pub mod dto;
pub mod error;
pub mod live;
pub mod media;
pub mod room_info;
pub mod rooms;
pub mod search;
pub mod secrets;
pub mod session;
pub mod spaces;
pub mod sync;
pub mod timeline;
pub mod tls;
