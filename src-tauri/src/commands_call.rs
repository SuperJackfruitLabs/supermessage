//! Calls with agents: thin commands over the core's `CallController`, and `sm://call`.
//!
//! The page sends the "Calls" setting at start and on every change (`calls_set_enabled`); off,
//! `call_start` refuses and nothing about calls runs. Every change arrives whole on
//! [`CALL_EVENT`], and each command also returns the snapshot it produced.

use std::{sync::Arc, time::Duration};

use supermessage_core::calls::{
    controller::{CallController, CallObserver},
    link::{issuer_choice, ClientSource, SuperdialLinkFactory, DEV_ISSUER_ENV},
    CallSnapshot,
};
use supermessage_core::{error::CoreError, session::Session};
use tauri::{AppHandle, Emitter, Manager, Runtime, State};

use crate::call_audio::CpalAudio;

/// Every change to calls, as a whole [`CallSnapshot`].
pub const CALL_EVENT: &str = "sm://call";

/// How long quitting waits for a hang-up before giving the leave to the server's delayed leave.
pub const EXIT_HANG_UP_BOUND: Duration = Duration::from_secs(3);

/// The app's one call controller.
pub struct CallsState(pub CallController);

struct EmitObserver<R: Runtime>(AppHandle<R>);

impl<R: Runtime> CallObserver for EmitObserver<R> {
    fn changed(&self, snapshot: &CallSnapshot) {
        if let Err(e) = self.0.emit(CALL_EVENT, snapshot) {
            tracing::warn!(error = %e, "couldn't emit {CALL_EVENT}");
        }
    }
}

/// The session's client, for the link.
struct SessionClient<R: Runtime>(AppHandle<R>);

#[async_trait::async_trait]
impl<R: Runtime> ClientSource for SessionClient<R> {
    async fn client(&self) -> Option<matrix_sdk::Client> {
        let session = self.0.try_state::<Session>()?;
        session.client().await
    }
}

/// The controller over the real call link and the cpal devices, emitting on [`CALL_EVENT`].
pub fn install<R: Runtime>(app: &AppHandle<R>) {
    let issuer = issuer_choice(std::env::var(DEV_ISSUER_ENV).ok().as_deref());
    let factory = SuperdialLinkFactory::new(Arc::new(SessionClient(app.clone())), issuer);
    let controller = CallController::new(
        Arc::new(factory),
        Arc::new(CpalAudio),
        Arc::new(EmitObserver(app.clone())),
    );
    app.manage(CallsState(controller));
}

/// Hangs up while the app quits, waiting at most `bound`; the delayed leave covers the rest.
pub fn hang_up_on_exit(calls: &CallController, bound: Duration) {
    if calls.snapshot().call.is_none() {
        return;
    }
    let calls = calls.clone();
    tauri::async_runtime::block_on(async move {
        if tokio::time::timeout(bound, calls.hang_up()).await.is_err() {
            tracing::warn!("hang-up on exit timed out; the delayed leave will end the membership");
        }
    });
}

#[tauri::command]
pub async fn calls_set_enabled(
    enabled: bool,
    calls: State<'_, CallsState>,
) -> Result<CallSnapshot, CoreError> {
    Ok(calls.0.set_enabled(enabled).await)
}

/// Starts a call with the room's agent; resolves once it is live or did not start.
#[tauri::command]
pub async fn call_start(
    room_id: String,
    agent_user_id: String,
    agent_name: String,
    calls: State<'_, CallsState>,
) -> Result<CallSnapshot, CoreError> {
    Ok(calls.0.start(&room_id, &agent_user_id, &agent_name).await)
}

#[tauri::command]
pub async fn call_hang_up(calls: State<'_, CallsState>) -> Result<CallSnapshot, CoreError> {
    Ok(calls.0.hang_up().await)
}

#[tauri::command]
pub fn call_set_muted(muted: bool, calls: State<'_, CallsState>) -> CallSnapshot {
    calls.0.set_muted(muted)
}

#[tauri::command]
pub fn call_dismiss_notice(calls: State<'_, CallsState>) -> CallSnapshot {
    calls.0.dismiss_notice()
}

#[tauri::command]
pub fn call_state(calls: State<'_, CallsState>) -> CallSnapshot {
    calls.0.snapshot()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use supermessage_core::calls::{
        controller::{AudioIo, CallAudio, CallLink, CallLinkFactory, LinkEvent},
        CallPhase, JoinFailure,
    };
    use tauri::test::{mock_app, MockRuntime};
    use tauri::{App, Listener};
    use tokio::sync::mpsc;

    struct Link {
        hang: bool,
    }

    #[async_trait::async_trait]
    impl CallLink for Link {
        async fn join(&self) -> Result<(), JoinFailure> {
            Ok(())
        }
        async fn capture(&self, _: &[i16]) -> Result<(), String> {
            Ok(())
        }
        async fn post_spoken_turn(
            &self,
            _: &supermessage_core::calls::TranscriptContent,
        ) -> Result<(), String> {
            Ok(())
        }
        async fn leave(&self) {
            if self.hang {
                std::future::pending::<()>().await;
            }
        }
    }

    struct Factory {
        hang: bool,
        events: Mutex<Vec<mpsc::UnboundedSender<LinkEvent>>>,
    }

    #[async_trait::async_trait]
    impl CallLinkFactory for Factory {
        async fn create(
            &self,
            _: &str,
            _: &str,
        ) -> Result<(Arc<dyn CallLink>, mpsc::UnboundedReceiver<LinkEvent>), JoinFailure> {
            let (tx, rx) = mpsc::unbounded_channel();
            self.events.lock().unwrap().push(tx);
            Ok((Arc::new(Link { hang: self.hang }), rx))
        }
    }

    struct Audio;
    struct Io;
    impl AudioIo for Io {
        fn play(&self, _: &[i16]) {}
    }
    impl CallAudio for Audio {
        fn open(&self, _: mpsc::UnboundedSender<Vec<i16>>) -> Result<Box<dyn AudioIo>, String> {
            Ok(Box::new(Io))
        }
    }

    fn app(hang: bool) -> (App<MockRuntime>, CallController) {
        let app = mock_app();
        let controller = CallController::new(
            Arc::new(Factory {
                hang,
                events: Mutex::new(vec![]),
            }),
            Arc::new(Audio),
            Arc::new(EmitObserver(app.handle().clone())),
        );
        app.manage(CallsState(controller.clone()));
        (app, controller)
    }

    #[tokio::test]
    async fn commands_reach_the_controller() {
        let (app, _) = app(false);
        let s = || app.state::<CallsState>();
        assert!(calls_set_enabled(true, s()).await.unwrap().enabled);
        let started = call_start("!r:s".into(), "@agent_x:s".into(), "Ada".into(), s())
            .await
            .unwrap();
        assert_eq!(started.call.as_ref().unwrap().phase, CallPhase::Live);
        assert!(call_set_muted(true, s()).call.unwrap().muted);
        assert!(call_state(s()).call.unwrap().muted);
        assert!(call_hang_up(s()).await.unwrap().call.is_none());
        assert!(call_dismiss_notice(s()).notice.is_none());
    }

    #[tokio::test]
    async fn start_is_refused_while_disabled() {
        let (app, _) = app(false);
        let s = call_start(
            "!r:s".into(),
            "@agent_x:s".into(),
            "Ada".into(),
            app.state::<CallsState>(),
        )
        .await
        .unwrap();
        assert!(!s.enabled);
        assert!(s.call.is_none());
        assert!(s.notice.is_some());
    }

    #[tokio::test]
    async fn the_observer_emits_sm_call() {
        let (app, _) = app(false);
        let heard: Arc<Mutex<Vec<serde_json::Value>>> = Arc::default();
        let into = heard.clone();
        app.listen_any(CALL_EVENT, move |e| {
            into.lock()
                .unwrap()
                .push(serde_json::from_str(e.payload()).unwrap());
        });
        calls_set_enabled(true, app.state::<CallsState>())
            .await
            .unwrap();
        let heard = heard.lock().unwrap();
        assert_eq!(heard.len(), 1);
        assert_eq!(heard[0]["enabled"], true);
        assert_eq!(heard[0]["call"], serde_json::Value::Null);
    }

    #[test]
    fn exit_hang_up_is_bounded() {
        let (_app, controller) = app(true);
        tauri::async_runtime::block_on(async {
            controller.set_enabled(true).await;
            controller.start("!r:s", "@agent_x:s", "Ada").await;
        });
        let t0 = std::time::Instant::now();
        hang_up_on_exit(&controller, Duration::from_millis(200));
        assert!(
            t0.elapsed() < Duration::from_secs(2),
            "a hung leave does not hold the exit"
        );
        assert!(controller.snapshot().call.is_none());
    }
}
