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
use matrix_sdk::encryption::CrossSigningResetAuthType;
use matrix_sdk::ruma::api::client::uiaa;
use matrix_sdk::Client;
use std::time::Duration;

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

/// How long to wait for the first sync to say what recovery state we are in.
///
/// `RecoveryState::Unknown` is the answer before the server has been heard
/// from, and acting on it would be acting on "I do not know yet". Ten seconds
/// is far longer than a sync takes and still short enough that a sign-in does
/// not appear to hang; timing out leaves recovery alone, which is the safe
/// direction.
const SETTLE_TIMEOUT: Duration = Duration::from_secs(10);

/// Wait for the SDK to know, or give up.
async fn settled_state(client: &Client) -> RecoveryState {
    let recovery = client.encryption().recovery();
    let deadline = tokio::time::Instant::now() + SETTLE_TIMEOUT;
    loop {
        let state = recovery.state();
        if state != RecoveryState::Unknown {
            return state;
        }
        if tokio::time::Instant::now() >= deadline {
            return RecoveryState::Unknown;
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
}

/// Set recovery up at sign-in, if this account has none, and hand back the key.
///
/// **This exists because `auto_enable_backups` is not recovery.** It creates a
/// key backup; it does not create the secret storage that holds the backup's
/// key. An account that only ever had that has a backup nothing can restore
/// from — the reassurance without the recovery — and the only way a user
/// learned otherwise was by finding a settings screen and reading it carefully.
///
/// So the key is generated where the user already is, at sign-in, and shown
/// once. Returns `None` when there is nothing to do, which is every sign-in
/// after the first: an account that already has recovery is left alone, and so
/// is one whose state could not be determined.
pub async fn ensure(client: &Client) -> CoreResult<Option<String>> {
    match settled_state(client).await {
        RecoveryState::Disabled => enable(client).await.map(Some),
        // `Incomplete` deliberately does nothing. Enabling over the top would
        // mint a second key and orphan whatever the first one still protects.
        _ => Ok(None),
    }
}

/// Throw the old identity away and start again, returning the new key.
///
/// The way out for someone who has no recovery key and no device that holds
/// the secrets — without this, that person's account is a screen asking for
/// something they cannot produce. Element reaches the same conclusion and
/// offers the same single action: reset everything.
///
/// **This is destructive and the caller must have said so.** The old backup is
/// deleted, the cross-signing identity is replaced, and anything still
/// trusting the old one sees this account as unverified. Room keys that only
/// existed in the old backup are gone — though in the case this is built for
/// they were already unreachable, which is why the reset is worth offering.
///
/// The password is the homeserver's price for replacing an identity, and it is
/// used for that one call and dropped.
pub async fn reset(client: &Client, password: &str) -> CoreResult<String> {
    let recovery = client.encryption().recovery();

    if let Some(handle) = recovery
        .reset_identity()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?
    {
        match handle.auth_type() {
            CrossSigningResetAuthType::Uiaa(info) => {
                let user_id = client
                    .user_id()
                    .ok_or_else(|| CoreError::Protocol("not signed in".into()))?;
                let mut answer = uiaa::Password::new(
                    uiaa::UserIdentifier::Matrix(uiaa::MatrixUserIdentifier::new(
                        user_id.to_string(),
                    )),
                    password.to_owned(),
                );
                answer.session = info.session.clone();
                handle
                    .reset(Some(uiaa::AuthData::Password(answer)))
                    .await
                    .map_err(|e| CoreError::Protocol(e.to_string()))?;
            }
            // Browser approval, which an app cannot drive on the user's behalf.
            other => {
                handle.cancel().await;
                return Err(CoreError::Protocol(format!(
                    "this homeserver wants {other:?} to replace an identity, which this app cannot answer"
                )));
            }
        }
    }

    enable(client).await
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
