//! Recovery: the key that gets a conversation back.
//!
//! Room keys live in the encrypted store on one device. Lose the device and,
//! without this, every encrypted conversation on it is unreadable forever —
//! there is no server-side copy to fall back on, because that is the point of
//! end-to-end encryption.
//!
//! `auto_enable_backups` (see `Session::build_client`) already uploads room
//! keys to server-side backup, encrypted under a key the server never sees.
//! This module is the other half: it puts that key into secret storage under a
//! **recovery key** the user holds, so a new device can ask for it.
//!
//! **The recovery key is shown once and never stored.** It is displayed when it
//! is generated and then it is gone from this app; the SDK keeps what it needs
//! and nothing writes the key to disk or to a log. A user who loses it and
//! their devices has lost the history, which is the honest cost of the
//! guarantee.

use matrix_sdk::encryption::recovery::RecoveryState;
use matrix_sdk::Client;

use super::error::{CoreError, CoreResult};

/// How recovery stands for this account, as a word the UI can switch on.
///
/// `Unknown` is not an error: it is what the SDK reports before the first sync
/// has told it anything, so a screen opened quickly enough will see it.
pub fn state_of(client: &Client) -> &'static str {
    match client.encryption().recovery().state() {
        RecoveryState::Unknown => "unknown",
        RecoveryState::Enabled => "enabled",
        RecoveryState::Disabled => "disabled",
        RecoveryState::Incomplete => "incomplete",
    }
}

/// Turn recovery on and hand back the key, once.
///
/// Returns the recovery key as a string. The caller shows it to the user and
/// drops it; there is deliberately no way to ask for it again, because an app
/// that can re-display a recovery key is an app that stored one.
pub async fn enable(client: &Client) -> CoreResult<String> {
    client
        .encryption()
        .recovery()
        .enable()
        .wait_for_backups_to_upload()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))
}

/// Use a recovery key on this device, to read what other devices already hold.
///
/// The `Incomplete` state is exactly what this fixes: secret storage exists,
/// this device simply does not have the secrets yet.
pub async fn recover(client: &Client, recovery_key: &str) -> CoreResult<()> {
    let key = recovery_key.trim();
    if key.is_empty() {
        return Err(CoreError::Protocol("a recovery key is required".into()));
    }
    client
        .encryption()
        .recovery()
        .recover(key)
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_recovery_state_has_a_word_the_ui_can_switch_on() {
        // A silent `_ => "unknown"` would turn a new SDK state into "no
        // recovery set up", which is the one answer that would make a user
        // generate a second key and orphan the first.
        for (state, word) in [
            (RecoveryState::Unknown, "unknown"),
            (RecoveryState::Enabled, "enabled"),
            (RecoveryState::Disabled, "disabled"),
            (RecoveryState::Incomplete, "incomplete"),
        ] {
            let got = match state {
                RecoveryState::Unknown => "unknown",
                RecoveryState::Enabled => "enabled",
                RecoveryState::Disabled => "disabled",
                RecoveryState::Incomplete => "incomplete",
            };
            assert_eq!(got, word);
        }
    }
}
