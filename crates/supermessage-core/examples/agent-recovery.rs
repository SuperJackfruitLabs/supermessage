//! Give one Matrix account a recovery key, so another client can adopt its identity.
//!
//! ## Why this lives here
//!
//! It is an AgentPod operations tool, and it is in supermessage's workspace for
//! one reason: `matrix-sdk`'s `recovery()` API is the only implementation of
//! secret storage (4S) in either repository. AgentPod's bridge uses
//! `matrix-sdk-crypto` directly — the low-level machine, which has no 4S — and
//! the hub is TypeScript. Writing a second 4S implementation to avoid an odd
//! home would be the worse trade.
//!
//! ## The problem it solves
//!
//! Fourteen harness agents have a cross-signing identity published by the
//! AgentPod bridge, which held the private half. Their own client (Hermes)
//! therefore runs a device nobody can verify, and it will not fix that itself:
//! it only bootstraps an identity when the server has none, and there is no
//! documented command to reset one.
//!
//! What Hermes *does* support is `MATRIX_RECOVERY_KEY` — it imports
//! cross-signing keys from secret storage and signs its own device with them.
//! So the fix is to put a usable identity into secret storage and hand the
//! agent the key to it. That is all this does.
//!
//! ## Running it
//!
//! ```sh
//! MATRIX_HOMESERVER=https://id.agentpod.dev \
//! MATRIX_USER_ID=@agent_analyst-echo:id.agentpod.dev \
//! MATRIX_DEVICE_ID=ABCDEFGH \
//! MATRIX_ACCESS_TOKEN=<token> \
//!   cargo run -p supermessage-core --example agent-recovery
//! ```
//!
//! It prints the recovery key on stdout and nothing else there, so a caller can
//! capture it with `$(...)`. Everything else goes to stderr.
//!
//! ## Signing a device that cannot sign itself
//!
//! With `AGENT_SIGN_DEVICE=<device_id>` and `MATRIX_RECOVERY_KEY=<key>` it does
//! the other half: import the identity from secret storage and cross-sign that
//! device. That is what closes the gap for a Hermes agent, whose own client
//! never reaches its `verify_with_recovery_key` branch — reproduced on v0.21.3
//! with an empty crypto store and INFO logging, where it emits neither success,
//! failure, nor the bootstrap-skip message.
//!
//! Cross-signing does not require a device to sign *itself*. It requires the
//! user's self-signing key, and this holds it, so it signs on the agent's
//! behalf. The honest cost: the operator custodies that identity rather than
//! the agent owning it — better than an identity nobody owns, worse than one
//! the agent controls, and reversible the day Hermes fixes its side.
//!
//! **The key is the agent's history.** It is printed once and never stored by
//! this tool; the caller writes it to the agent's `.env` as
//! `MATRIX_RECOVERY_KEY` and protects that file.
use std::env;

use matrix_sdk::authentication::matrix::MatrixSession;
use matrix_sdk::encryption::recovery::RecoveryState;
use matrix_sdk::encryption::CrossSigningResetAuthType;
use matrix_sdk::ruma::api::client::uiaa::AuthData;
use matrix_sdk::ruma::OwnedUserId;
use matrix_sdk::store::RoomLoadSettings;
use matrix_sdk::{Client, SessionMeta, SessionTokens};

fn required(name: &str) -> String {
    match env::var(name) {
        Ok(v) if !v.trim().is_empty() => v.trim().to_string(),
        _ => {
            eprintln!("{name} is required");
            std::process::exit(2);
        }
    }
}

/// Cross-sign one device with the identity held in secret storage.
///
/// `recover()` first, because the keys have to be *here* to sign with: this
/// process holds them only if it just minted them, or if it imports them from
/// 4S with the recovery key.
async fn sign_if_asked(
    client: &Client,
    user_id: &OwnedUserId,
    fresh_key: Option<&str>,
) -> Result<(), Box<dyn std::error::Error>> {
    let Ok(target) = env::var("AGENT_SIGN_DEVICE") else {
        return Ok(());
    };
    let target = target.trim();
    if target.is_empty() {
        return Ok(());
    }

    // A key passed in wins over one just generated: when both exist they are
    // the same identity, and the caller's is the one on disk for the agent.
    let key = env::var("MATRIX_RECOVERY_KEY")
        .ok()
        .filter(|k| !k.trim().is_empty())
        .or_else(|| fresh_key.map(str::to_string));
    let Some(key) = key else {
        eprintln!("AGENT_SIGN_DEVICE needs MATRIX_RECOVERY_KEY, or a run that just made one");
        std::process::exit(2);
    };

    if client.encryption().recovery().state() != RecoveryState::Enabled {
        eprintln!("importing the identity from secret storage…");
        client.encryption().recovery().recover(key.trim()).await?;
    }

    let device = client
        .encryption()
        .get_device(user_id, target.into())
        .await?;
    let Some(device) = device else {
        eprintln!("no such device: {target}");
        std::process::exit(1);
    };

    if device.is_verified_with_cross_signing() {
        eprintln!("{target} is already cross-signed");
        return Ok(());
    }

    device.verify().await?;
    eprintln!("signed {target}");
    Ok(())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let homeserver = required("MATRIX_HOMESERVER");
    let user_id: OwnedUserId = required("MATRIX_USER_ID").parse()?;
    let device_id = required("MATRIX_DEVICE_ID");
    let access_token = required("MATRIX_ACCESS_TOKEN");

    // An in-memory-ish store in a temp dir: this process holds keys for the
    // length of one bootstrap and must not leave a second crypto store behind
    // for an account whose whole problem is having two.
    let dir = std::env::temp_dir().join(format!("agent-recovery-{}", std::process::id()));
    std::fs::create_dir_all(&dir)?;
    let client = Client::builder()
        .homeserver_url(&homeserver)
        .sqlite_store(&dir, None)
        .build()
        .await?;

    client
        .matrix_auth()
        .restore_session(
            MatrixSession {
                meta: SessionMeta {
                    user_id: user_id.clone(),
                    device_id: device_id.as_str().into(),
                },
                tokens: SessionTokens {
                    access_token,
                    refresh_token: None,
                },
            },
            RoomLoadSettings::default(),
        )
        .await?;

    // One sync, so the client learns what the account already has. Without it
    // `recovery().state()` answers `Unknown` and `enable()` is working blind.
    eprintln!("syncing once to learn the account's state…");
    client.sync_once(Default::default()).await?;
    eprintln!(
        "recovery state before: {:?}",
        client.encryption().recovery().state()
    );

    // **The identity is reset before anything else, and that destroys one.**
    //
    // `enable()` alone is not enough here. Run against these accounts it
    // reaches `Incomplete`: it builds secret storage and then has no
    // cross-signing keys to put in it, because the published identity belongs
    // to the bridge and its private half is not ours. Resetting mints keys this
    // process owns, which is the whole point — an identity nobody holds the
    // private half of is worth less than none.
    //
    // Gated on `AGENT_RECOVERY_RESET=1` so it cannot happen by accident. Any
    // verification another user has done of this agent is void afterwards, and
    // the homeserver must already allow cross-signing replacement without UIA
    // for this account (agentpod#446) or the reset asks for an auth nothing
    // here can satisfy.
    if env::var("AGENT_RECOVERY_RESET").as_deref() == Ok("1") {
        eprintln!("resetting the cross-signing identity…");
        if let Some(handle) = client.encryption().recovery().reset_identity().await? {
            // The homeserver asks for user-interactive auth, and which kind
            // decides whether this can proceed at all.
            //
            // With tuwunel's JWT feature on, it offers `org.matrix.login.jwt`
            // — a step an operator key *can* satisfy, by minting a token for
            // this agent. Without it the offered flow list comes back empty,
            // which is a challenge with no answer, and the reset can only
            // time out. That is the difference between this working and not.
            let auth = match handle.auth_type() {
                CrossSigningResetAuthType::Uiaa(info) => {
                    let jwt = required("AGENT_UIA_JWT");
                    let mut data = serde_json::Map::new();
                    data.insert("token".into(), serde_json::Value::String(jwt));
                    Some(AuthData::new(
                        "org.matrix.login.jwt",
                        info.session.clone(),
                        data,
                    )?)
                }
                // OIDC-style: the SDK wants the operator to approve in a
                // browser, which nothing here can do.
                other => {
                    eprintln!("  unsupported auth type for an unattended reset: {other:?}");
                    handle.cancel().await;
                    std::process::exit(1);
                }
            };
            handle.reset(auth).await?;
        }
        eprintln!("  reset done");
    }

    // Sign-only: the identity already exists in secret storage and the caller
    // just wants a device signed. No reset, no new key, nothing destroyed.
    if env::var("AGENT_SIGN_ONLY").as_deref() == Ok("1") {
        sign_if_asked(&client, &user_id, None).await?;
        let _ = std::fs::remove_dir_all(&dir);
        return Ok(());
    }

    let key = client
        .encryption()
        .recovery()
        .enable()
        .wait_for_backups_to_upload()
        .await?;

    let after = client.encryption().recovery().state();
    eprintln!("recovery state after: {after:?}");
    if after != RecoveryState::Enabled {
        eprintln!("recovery did not end up enabled; not printing a key that may not work");
        std::process::exit(1);
    }

    // stdout carries the key and nothing else.
    println!("{key}");

    sign_if_asked(&client, &user_id, Some(&key)).await?;

    // The store held this account's keys for the length of one bootstrap; an
    // account whose whole problem is owning two crypto stores does not need a
    // third left in /tmp.
    let _ = std::fs::remove_dir_all(&dir);
    Ok(())
}
