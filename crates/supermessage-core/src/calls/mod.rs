//! Voice calls with an agent, over superdial (desktop only, behind the `calls` feature).
//!
//! [`model`] holds what a host shows and the rules for reading it; [`controller`] is the state
//! machine one call goes through; [`link`] is the real call, `superdial::Call` over the
//! session's client. The host supplies the microphone and speaker ([`controller::CallAudio`]).

pub mod controller;
pub mod model;

pub use model::{
    is_agent_identity, join_failure, AgentCallState, CallNotice, CallPhase, CallSnapshot, CallView,
    JoinFailure, REFUSED_COPY, STATE_ATTRIBUTE,
};
pub use superdial::TranscriptContent;
