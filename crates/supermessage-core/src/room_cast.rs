//! Who a room's messages are for, and what to call them.
//!
//! This task holds the one type the typing line needs; the rest of the cast
//! (who the agents are, who a message here is addressed to) arrives with the
//! session API that reads the member list.

use serde::{Deserialize, Serialize};

/// The single agent a room's messages go to, as the header names it.
///
/// The typing line and the acknowledgement dock say "Atlas", not the bridge's
/// `atlas-bot`, because the header already does; the cast carries the header's
/// name so the three never disagree.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Counterpart {
    pub user_id: String,
    pub name: String,
}
