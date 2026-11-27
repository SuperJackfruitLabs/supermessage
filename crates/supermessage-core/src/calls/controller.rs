//! The state machine one call goes through, over two seams the host and the network fill in:
//! [`CallLink`] (the call itself; the real one is [`super::link::SuperdialLink`]) and
//! [`CallAudio`] (the host's microphone and speaker).
//!
//! One call at a time. [`CallController::start`] shows Connecting at once, joins, and only then
//! opens the microphone, so a refused call never touches it. Hanging up while still connecting
//! takes the call away from the starter, which leaves as soon as its join returns.
//!
//! A call that is hung up stays in `leaving` until its leave returns (or [`LEAVE_LIMIT`]
//! passes and it is given up on), and a new call waits for
//! every earlier one to leave before it makes its link: superdial's leave clears the membership
//! state key (`_{user}_{device}_m.call`) every call from this device publishes under, so an
//! overlapping leave would take the new call's membership down.

use std::{
    collections::{HashMap, HashSet},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, Weak,
    },
    time::{SystemTime, UNIX_EPOCH},
};

use async_trait::async_trait;
use superdial::TranscriptContent;
use tokio::{
    sync::{mpsc, Notify},
    task::JoinHandle,
};

use super::model::{
    is_agent_identity, AgentCallState, CallNotice, CallPhase, CallSnapshot, CallView, JoinFailure,
    STATE_ATTRIBUTE,
};

/// Samples in one 10 ms frame at 48 kHz: what superdial's `capture` takes whole.
pub const FRAME_SAMPLES: usize = 480;

/// Told when LiveKit ends the call under us.
pub const ENDED_COPY: &str = "The call ended";
/// Told when the microphone or speaker cannot be opened once joined.
pub const AUDIO_FAILED_COPY: &str = "Couldn't open the microphone or speaker";
/// Told when Call is pressed while another call runs.
pub const BUSY_COPY: &str = "Hang up the current call first";
/// Told when a start arrives with the setting off.
pub const DISABLED_COPY: &str = "Calls are turned off";

/// The longest a leave may take before it is given up on. A leave that never returns (media
/// that will not close, a homeserver that does not answer) would otherwise keep the call in
/// `leaving`, hide the Call button and park every later start until the app restarts. The
/// membership left behind expires on the homeserver through superdial's delayed leave.
pub const LEAVE_LIMIT: std::time::Duration = std::time::Duration::from_secs(45);

/// What the call reports, already narrowed to what the controller uses.
#[derive(Debug, Clone, PartialEq)]
pub enum LinkEvent {
    /// A participant's attributes, whole.
    Attributes {
        identity: String,
        attributes: HashMap<String, String>,
    },
    /// 10 ms of 48 kHz mono audio from a participant, decrypted.
    Audio { identity: String, samples: Vec<i16> },
    /// A C2 transcript the library accepted from the agent's call device.
    Transcript(TranscriptContent),
    /// A participant's frame-encryption state (diagnostic).
    E2ee { identity: String, state: String },
    /// LiveKit ended the connection.
    Disconnected { reason: String },
}

/// One call, created for a room and an agent and joined at most once.
#[async_trait]
pub trait CallLink: Send + Sync {
    /// Token, membership, media. Nothing is published when this fails.
    async fn join(&self) -> Result<(), JoinFailure>;
    /// Sends one 480-sample frame as our microphone.
    async fn capture(&self, frame: &[i16]) -> Result<(), String>;
    /// Posts a transcript as our own message, marked as spoken (C3).
    async fn post_spoken_turn(&self, transcript: &TranscriptContent) -> Result<(), String>;
    /// Leaves (idempotent), and stops routing anything to the call.
    async fn leave(&self);
}

/// Makes the link for one call. The receiver carries everything the call reports.
#[async_trait]
pub trait CallLinkFactory: Send + Sync {
    async fn create(
        &self,
        room_id: &str,
        agent_user_id: &str,
    ) -> Result<(Arc<dyn CallLink>, mpsc::UnboundedReceiver<LinkEvent>), JoinFailure>;
}

/// The host's microphone and speaker.
pub trait CallAudio: Send + Sync {
    /// Opens both. Microphone audio goes to `mic` as 48 kHz mono chunks of any length.
    fn open(&self, mic: mpsc::UnboundedSender<Vec<i16>>) -> Result<Box<dyn AudioIo>, String>;
}

/// Open devices; dropping this closes them.
pub trait AudioIo: Send + Sync {
    /// Queues 48 kHz mono audio for the speaker.
    fn play(&self, samples: &[i16]);
}

/// Told every change, with the whole snapshot. Must not block or call back in.
pub trait CallObserver: Send + Sync {
    fn changed(&self, snapshot: &CallSnapshot);
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// What closing a call still has to close: its link (when it is ours to leave) and devices.
type Leftovers = (Option<Arc<dyn CallLink>>, Option<Arc<dyn AudioIo>>);

/// What exists while a call runs; `None` in the state once it is over.
struct Active {
    id: u64,
    view: CallView,
    link: Option<Arc<dyn CallLink>>,
    audio: Option<Arc<dyn AudioIo>>,
    muted: Arc<AtomicBool>,
    tasks: Vec<JoinHandle<()>>,
}

impl Active {
    /// Stops what runs for this call and hands back what must still be closed.
    ///
    /// The link only once the call is Live. Before that `start` still owns it and leaves when
    /// its `join` returns: leaving from here too would mark the call left while `join` is still
    /// publishing the membership, and superdial's second `leave` would then do nothing,
    /// leaving that membership (and its delayed-leave restarts) up.
    fn dismantle(self) -> Leftovers {
        for t in self.tasks {
            t.abort();
        }
        let link = self.link.filter(|_| self.view.phase == CallPhase::Live);
        (link, self.audio)
    }
}

struct State {
    enabled: bool,
    active: Option<Active>,
    notice: Option<CallNotice>,
    next_id: u64,
    /// Calls taken out of the state whose leave has not returned yet.
    leaving: HashSet<u64>,
    /// The last snapshot's `seq`.
    seq: u64,
}

impl State {
    fn snapshot(&self) -> CallSnapshot {
        CallSnapshot {
            enabled: self.enabled,
            call: self.active.as_ref().map(|a| a.view.clone()),
            notice: self.notice.clone(),
            leaving: !self.leaving.is_empty(),
            seq: self.seq,
        }
    }

    /// A change: the next `seq`, and the snapshot that carries it.
    fn changed(&mut self) -> CallSnapshot {
        self.seq += 1;
        self.snapshot()
    }

    fn active_mut(&mut self, id: u64) -> Option<&mut Active> {
        self.active.as_mut().filter(|a| a.id == id)
    }

    /// Takes the call out if it is still call `id`, as leaving.
    fn take(&mut self, id: u64) -> Option<Active> {
        if self.active.as_ref().is_some_and(|a| a.id == id) {
            self.take_any()
        } else {
            None
        }
    }

    /// Takes whatever call runs out, as leaving: whoever leaves it ends that with
    /// [`CallController::settled`].
    fn take_any(&mut self) -> Option<Active> {
        let taken = self.active.take();
        if let Some(a) = &taken {
            self.leaving.insert(a.id);
        }
        taken
    }

    /// Whether call `id` may go on to make its link: it was hung up (and so gives up), or no
    /// call is leaving. A call is put in `leaving` only as it stops being the active one, so
    /// the running call never waits on itself.
    fn may_go_on(&self, id: u64) -> bool {
        self.active.as_ref().is_none_or(|a| a.id != id) || self.leaving.is_empty()
    }
}

struct Inner {
    factory: Arc<dyn CallLinkFactory>,
    audio: Arc<dyn CallAudio>,
    observer: Arc<dyn CallObserver>,
    state: Mutex<State>,
    /// Told when a call finishes leaving or is hung up, for a start waiting in `wait_turn`.
    turn: Notify,
}

/// Calls, as the desktop runs them. Cheap to clone; clones share one state.
#[derive(Clone)]
pub struct CallController {
    inner: Arc<Inner>,
}

impl CallController {
    pub fn new(
        factory: Arc<dyn CallLinkFactory>,
        audio: Arc<dyn CallAudio>,
        observer: Arc<dyn CallObserver>,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                factory,
                audio,
                observer,
                state: Mutex::new(State {
                    enabled: false,
                    active: None,
                    notice: None,
                    next_id: 1,
                    leaving: HashSet::new(),
                    seq: 0,
                }),
                turn: Notify::new(),
            }),
        }
    }

    /// Changes the state under the lock and tells the observer, in order.
    fn update<T>(&self, f: impl FnOnce(&mut State) -> T) -> (T, CallSnapshot) {
        let mut state = self.inner.state.lock().expect("call state lock");
        let out = f(&mut state);
        let snapshot = state.changed();
        self.inner.observer.changed(&snapshot);
        (out, snapshot)
    }

    pub fn snapshot(&self) -> CallSnapshot {
        self.inner.state.lock().expect("call state lock").snapshot()
    }

    /// The setting. Turning it off hangs up.
    pub async fn set_enabled(&self, enabled: bool) -> CallSnapshot {
        let (taken, snapshot) = self.update(|s| {
            s.enabled = enabled;
            if enabled {
                None
            } else {
                s.notice = None;
                s.take_any()
            }
        });
        if let Some(active) = taken {
            self.inner.turn.notify_waiters();
            return self.close(active).await.unwrap_or(snapshot);
        }
        snapshot
    }

    /// Starts a call with `agent_user_id` in `room_id`. Resolves once the call is live or did
    /// not start; the observer sees Connecting before that.
    pub async fn start(
        &self,
        room_id: &str,
        agent_user_id: &str,
        agent_name: &str,
    ) -> CallSnapshot {
        let (id, snapshot) = self.update(|s| {
            if !s.enabled {
                s.notice = Some(notice(room_id, DISABLED_COPY));
                return None;
            }
            if s.active.is_some() {
                s.notice = Some(notice(room_id, BUSY_COPY));
                return None;
            }
            let id = s.next_id;
            s.next_id += 1;
            s.notice = None;
            s.active = Some(Active {
                id,
                view: CallView {
                    room_id: room_id.to_owned(),
                    agent_user_id: agent_user_id.to_owned(),
                    agent_name: agent_name.to_owned(),
                    phase: CallPhase::Connecting,
                    agent_state: None,
                    started_at_ms: None,
                    muted: false,
                },
                link: None,
                audio: None,
                muted: Arc::new(AtomicBool::new(false)),
                tasks: Vec::new(),
            });
            Some(id)
        });
        let Some(id) = id else { return snapshot };
        match self.connect(id, room_id, agent_user_id).await {
            Some(live) => live,
            // Did not go live. If it was hung up meanwhile, it has left by now (or never
            // published anything), and the next call may go on.
            None => self.settled(id),
        }
    }

    /// Joins call `id` and opens the devices: the Live snapshot, or `None` when it did not get
    /// there, having left whatever it joined.
    async fn connect(&self, id: u64, room_id: &str, agent_user_id: &str) -> Option<CallSnapshot> {
        self.wait_turn(id).await;
        if !self.is_current(id) {
            // Hung up while waiting: nothing made, nothing to leave.
            return None;
        }
        let (link, events) = match self.inner.factory.create(room_id, agent_user_id).await {
            Ok(made) => made,
            Err(failure) => {
                self.failed(id, room_id, failure.copy());
                return None;
            }
        };
        let still_wanted = match self
            .inner
            .state
            .lock()
            .expect("call state lock")
            .active_mut(id)
        {
            Some(active) => {
                active.link = Some(link.clone());
                true
            }
            None => false,
        };
        if !still_wanted {
            // Hung up before the call existed: nothing was published; stop its routing.
            leave_within_limit(link.as_ref()).await;
            return None;
        }
        if let Err(failure) = link.join().await {
            tracing::info!(?failure, "call: did not start");
            leave_within_limit(link.as_ref()).await;
            self.failed(id, room_id, failure.copy());
            return None;
        }
        if !self.is_current(id) {
            // Hung up while joining: the bar is already gone; take the membership down too.
            leave_within_limit(link.as_ref()).await;
            return None;
        }

        let (mic_tx, mic_rx) = mpsc::unbounded_channel();
        let audio: Arc<dyn AudioIo> = match self.inner.audio.open(mic_tx) {
            Ok(io) => Arc::from(io),
            Err(e) => {
                tracing::warn!(error = %e, "call: audio did not open");
                leave_within_limit(link.as_ref()).await;
                self.failed(id, room_id, AUDIO_FAILED_COPY);
                return None;
            }
        };

        let (left, snapshot) = self.update(|s| {
            let Some(active) = s.active_mut(id) else {
                return true;
            };
            active.view.phase = CallPhase::Live;
            active.view.started_at_ms = Some(now_ms());
            active.audio = Some(audio.clone());
            let muted = active.muted.clone();
            active
                .tasks
                .push(tokio::spawn(send_microphone(mic_rx, link.clone(), muted)));
            let (posts_tx, posts_rx) = mpsc::unbounded_channel();
            active
                .tasks
                .push(tokio::spawn(post_transcripts(posts_rx, link.clone())));
            active.tasks.push(tokio::spawn(follow_call(
                self.clone(),
                id,
                agent_user_id.to_owned(),
                events,
                Arc::downgrade(&audio),
                posts_tx,
            )));
            false
        });
        if left {
            // Hung up between the devices opening and now.
            drop(audio);
            leave_within_limit(link.as_ref()).await;
            return None;
        }
        Some(snapshot)
    }

    /// Waits until call `id` may make its link (see [`State::may_go_on`]).
    async fn wait_turn(&self, id: u64) {
        loop {
            let told = self.inner.turn.notified();
            tokio::pin!(told);
            // Registered before the check, so a change between the two is not missed.
            told.as_mut().enable();
            if self
                .inner
                .state
                .lock()
                .expect("call state lock")
                .may_go_on(id)
            {
                return;
            }
            told.await;
        }
    }

    /// Call `id` has finished leaving: the snapshot says so, and a start waiting may go on.
    fn settled(&self, id: u64) -> CallSnapshot {
        let snapshot = {
            let mut state = self.inner.state.lock().expect("call state lock");
            if !state.leaving.remove(&id) {
                return state.snapshot();
            }
            let snapshot = state.changed();
            self.inner.observer.changed(&snapshot);
            snapshot
        };
        self.inner.turn.notify_waiters();
        snapshot
    }

    fn is_current(&self, id: u64) -> bool {
        self.inner
            .state
            .lock()
            .expect("call state lock")
            .active
            .as_ref()
            .is_some_and(|a| a.id == id)
    }

    /// Call `id` did not start: it goes, and the room gets the reason.
    /// Called once whatever it joined has been left, so it does not count as leaving.
    fn failed(&self, id: u64, room_id: &str, text: &str) {
        self.update(|s| {
            if s.active.as_ref().is_some_and(|a| a.id == id) {
                s.active = None;
                s.notice = Some(notice(room_id, text));
            }
        });
    }

    /// The agent's state changed, as its participant says.
    fn agent_state(&self, id: u64, state: AgentCallState) {
        let changed = self
            .inner
            .state
            .lock()
            .expect("call state lock")
            .active
            .as_ref()
            .is_some_and(|a| a.id == id && a.view.agent_state != Some(state));
        if changed {
            self.update(|s| {
                if let Some(a) = s.active_mut(id) {
                    a.view.agent_state = Some(state);
                }
            });
        }
    }

    /// LiveKit ended call `id` under us.
    async fn ended(&self, id: u64) {
        let (taken, _) = self.update(|s| {
            let taken = s.take(id);
            if let Some(a) = &taken {
                s.notice = Some(notice(&a.view.room_id, ENDED_COPY));
            }
            taken
        });
        if let Some(active) = taken {
            // Not `close`: it would abort this very task before the leave. The call's own audio
            // handle (not a clone) is dropped first, so the devices close now rather than once
            // a leave on a slow homeserver returns.
            let Active {
                id: active_id,
                link,
                audio,
                tasks,
                ..
            } = active;
            drop(audio);
            if let Some(link) = link {
                leave_within_limit(link.as_ref()).await;
            }
            self.settled(active_id);
            for t in tasks {
                t.abort();
            }
        }
    }

    /// Hangs up the call, whatever phase it is in.
    pub async fn hang_up(&self) -> CallSnapshot {
        let (taken, snapshot) = self.update(|s| s.take_any());
        if let Some(active) = taken {
            self.inner.turn.notify_waiters();
            return self.close(active).await.unwrap_or(snapshot);
        }
        snapshot
    }

    /// Closes a call taken out of the state: tasks stop, devices close, then we leave. A call
    /// still connecting is its starter's to leave; then this is `None`.
    async fn close(&self, active: Active) -> Option<CallSnapshot> {
        let id = active.id;
        let (link, audio) = active.dismantle();
        drop(audio);
        let link = link?;
        leave_within_limit(link.as_ref()).await;
        Some(self.settled(id))
    }

    /// Mute sends silence rather than nothing: the agent ends an utterance on quiet frames.
    pub fn set_muted(&self, muted: bool) -> CallSnapshot {
        self.update(|s| {
            if let Some(a) = s.active.as_mut() {
                a.muted.store(muted, Ordering::SeqCst);
                a.view.muted = muted;
            }
        })
        .1
    }

    /// Forgets the notice.
    pub fn dismiss_notice(&self) -> CallSnapshot {
        self.update(|s| s.notice = None).1
    }
}

/// Leaves, giving up after [`LEAVE_LIMIT`]. Dropping the leave on time out stops it, so it
/// cannot land later on top of a newer call's membership.
async fn leave_within_limit(link: &dyn CallLink) {
    if tokio::time::timeout(LEAVE_LIMIT, link.leave())
        .await
        .is_err()
    {
        tracing::warn!(
            limit_secs = LEAVE_LIMIT.as_secs(),
            "call: the leave did not return in time; giving up on it"
        );
    }
}

fn notice(room_id: &str, text: &str) -> CallNotice {
    CallNotice {
        room_id: room_id.to_owned(),
        text: text.to_owned(),
    }
}

/// Microphone chunks, re-framed to 480 samples (superdial pads a short frame with zeros, so
/// it must only ever get whole ones), silence while muted.
async fn send_microphone(
    mut mic: mpsc::UnboundedReceiver<Vec<i16>>,
    link: Arc<dyn CallLink>,
    muted: Arc<AtomicBool>,
) {
    let mut pending: Vec<i16> = Vec::with_capacity(FRAME_SAMPLES * 4);
    let silence = [0i16; FRAME_SAMPLES];
    while let Some(chunk) = mic.recv().await {
        pending.extend_from_slice(&chunk);
        let (frames, _) = pending.as_chunks::<FRAME_SAMPLES>();
        let whole = frames.len() * FRAME_SAMPLES;
        for frame in frames {
            let frame = if muted.load(Ordering::SeqCst) {
                &silence[..]
            } else {
                &frame[..]
            };
            if let Err(e) = link.capture(frame).await {
                tracing::debug!(error = %e, "call: capture");
            }
        }
        pending.drain(..whole);
    }
}

/// Transcripts, posted one at a time so the room has them in the order they were said.
async fn post_transcripts(
    mut posts: mpsc::UnboundedReceiver<TranscriptContent>,
    link: Arc<dyn CallLink>,
) {
    while let Some(t) = posts.recv().await {
        if let Err(e) = link.post_spoken_turn(&t).await {
            tracing::warn!(error = %e, turn_id = %t.turn_id, "call: posting a spoken turn");
        }
    }
}

/// What the call reports, for as long as call `id` runs.
async fn follow_call(
    calls: CallController,
    id: u64,
    agent_user_id: String,
    mut events: mpsc::UnboundedReceiver<LinkEvent>,
    // Weak: the call's own handle is the one strong one, so hanging up closes the devices
    // then, not whenever the runtime gets round to dropping this aborted task.
    audio: Weak<dyn AudioIo>,
    posts: mpsc::UnboundedSender<TranscriptContent>,
) {
    while let Some(ev) = events.recv().await {
        match ev {
            LinkEvent::Attributes {
                identity,
                attributes,
            } if is_agent_identity(&identity, &agent_user_id) => {
                if let Some(state) = attributes
                    .get(STATE_ATTRIBUTE)
                    .and_then(|v| AgentCallState::from_attribute(v))
                {
                    calls.agent_state(id, state);
                }
            }
            LinkEvent::Audio { identity, samples }
                if is_agent_identity(&identity, &agent_user_id) =>
            {
                if let Some(audio) = audio.upgrade() {
                    audio.play(&samples);
                }
            }
            LinkEvent::Transcript(t) => {
                let _ = posts.send(t);
            }
            LinkEvent::Disconnected { .. } => break,
            _ => {}
        }
    }
    calls.ended(id).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::calls::model::REFUSED_COPY;
    use std::time::Duration;
    use tokio::sync::Notify;

    // ---- fakes ----------------------------------------------------------------------------

    #[derive(Clone, Copy, PartialEq)]
    enum JoinPlan {
        Ok,
        Refuse,
        Block,
    }

    struct FakeLink {
        plan: JoinPlan,
        release: Notify,
        /// When set, `leave` waits for `leave_release` (a stalled homeserver).
        hold_leave: bool,
        leave_release: Notify,
        /// Which link this is (1 for the first made), for `log`.
        n: usize,
        /// Joins and leaves of every link, in the order they happened.
        log: Arc<Mutex<Vec<String>>>,
        joins: Mutex<u32>,
        leaves: Mutex<u32>,
        captured: Mutex<Vec<Vec<i16>>>,
        posted: Mutex<Vec<String>>,
    }

    #[async_trait]
    impl CallLink for FakeLink {
        async fn join(&self) -> Result<(), JoinFailure> {
            self.log.lock().unwrap().push(format!("join {}", self.n));
            *self.joins.lock().unwrap() += 1;
            match self.plan {
                JoinPlan::Ok => Ok(()),
                JoinPlan::Refuse => Err(JoinFailure::Refused),
                JoinPlan::Block => {
                    self.release.notified().await;
                    Ok(())
                }
            }
        }
        async fn capture(&self, frame: &[i16]) -> Result<(), String> {
            self.captured.lock().unwrap().push(frame.to_vec());
            Ok(())
        }
        async fn post_spoken_turn(&self, t: &TranscriptContent) -> Result<(), String> {
            self.posted.lock().unwrap().push(t.text.clone());
            Ok(())
        }
        async fn leave(&self) {
            *self.leaves.lock().unwrap() += 1;
            if self.hold_leave {
                self.leave_release.notified().await;
            }
            self.log.lock().unwrap().push(format!("left {}", self.n));
        }
    }

    struct FakeFactory {
        plan: Mutex<JoinPlan>,
        hold_leave: Mutex<bool>,
        log: Arc<Mutex<Vec<String>>>,
        created: Mutex<Vec<(String, String)>>,
        links: Mutex<Vec<Arc<FakeLink>>>,
        events: Mutex<Option<mpsc::UnboundedSender<LinkEvent>>>,
    }

    impl FakeFactory {
        fn new(plan: JoinPlan) -> Arc<Self> {
            Arc::new(Self {
                plan: Mutex::new(plan),
                hold_leave: Mutex::new(false),
                log: Arc::default(),
                created: Mutex::new(vec![]),
                links: Mutex::new(vec![]),
                events: Mutex::new(None),
            })
        }
        fn log(&self) -> Vec<String> {
            self.log.lock().unwrap().clone()
        }
        fn link(&self) -> Arc<FakeLink> {
            self.links.lock().unwrap().last().cloned().expect("a link")
        }
        fn send(&self, ev: LinkEvent) {
            let _ = self
                .events
                .lock()
                .unwrap()
                .as_ref()
                .expect("events")
                .send(ev);
        }
    }

    #[async_trait]
    impl CallLinkFactory for FakeFactory {
        async fn create(
            &self,
            room_id: &str,
            agent_user_id: &str,
        ) -> Result<(Arc<dyn CallLink>, mpsc::UnboundedReceiver<LinkEvent>), JoinFailure> {
            self.created
                .lock()
                .unwrap()
                .push((room_id.into(), agent_user_id.into()));
            let link = Arc::new(FakeLink {
                plan: *self.plan.lock().unwrap(),
                release: Notify::new(),
                hold_leave: *self.hold_leave.lock().unwrap(),
                leave_release: Notify::new(),
                n: self.links.lock().unwrap().len() + 1,
                log: self.log.clone(),
                joins: Mutex::new(0),
                leaves: Mutex::new(0),
                captured: Mutex::new(vec![]),
                posted: Mutex::new(vec![]),
            });
            self.links.lock().unwrap().push(link.clone());
            let (tx, rx) = mpsc::unbounded_channel();
            *self.events.lock().unwrap() = Some(tx);
            Ok((link, rx))
        }
    }

    #[derive(Default)]
    struct FakeAudio {
        fail: bool,
        opens: Mutex<u32>,
        open_now: Arc<Mutex<u32>>,
        mic: Mutex<Option<mpsc::UnboundedSender<Vec<i16>>>>,
        played: Arc<Mutex<Vec<i16>>>,
    }

    struct FakeIo {
        played: Arc<Mutex<Vec<i16>>>,
        open_now: Arc<Mutex<u32>>,
    }

    impl AudioIo for FakeIo {
        fn play(&self, samples: &[i16]) {
            self.played.lock().unwrap().extend_from_slice(samples);
        }
    }

    impl Drop for FakeIo {
        fn drop(&mut self) {
            *self.open_now.lock().unwrap() -= 1;
        }
    }

    impl CallAudio for FakeAudio {
        fn open(&self, mic: mpsc::UnboundedSender<Vec<i16>>) -> Result<Box<dyn AudioIo>, String> {
            *self.opens.lock().unwrap() += 1;
            if self.fail {
                return Err("no device".into());
            }
            *self.mic.lock().unwrap() = Some(mic);
            *self.open_now.lock().unwrap() += 1;
            Ok(Box::new(FakeIo {
                played: self.played.clone(),
                open_now: self.open_now.clone(),
            }))
        }
    }

    impl FakeAudio {
        fn speak(&self, samples: Vec<i16>) {
            let _ = self
                .mic
                .lock()
                .unwrap()
                .as_ref()
                .expect("mic")
                .send(samples);
        }
    }

    #[derive(Default)]
    struct Recorder(Mutex<Vec<CallSnapshot>>);

    impl CallObserver for Recorder {
        fn changed(&self, s: &CallSnapshot) {
            self.0.lock().unwrap().push(s.clone());
        }
    }

    struct Rig {
        calls: CallController,
        factory: Arc<FakeFactory>,
        audio: Arc<FakeAudio>,
        seen: Arc<Recorder>,
    }

    fn rig_with(plan: JoinPlan, audio: FakeAudio) -> Rig {
        let factory = FakeFactory::new(plan);
        let audio = Arc::new(audio);
        let seen = Arc::new(Recorder::default());
        let calls = CallController::new(factory.clone(), audio.clone(), seen.clone());
        Rig {
            calls,
            factory,
            audio,
            seen,
        }
    }

    async fn enabled(plan: JoinPlan) -> Rig {
        let rig = rig_with(plan, FakeAudio::default());
        rig.calls.set_enabled(true).await;
        rig
    }

    const ROOM: &str = "!r:s";
    const AGENT: &str = "@agent_x:s";
    const AGENT_ID: &str = "@agent_x:s:SUPERDIAL";

    async fn eventually(what: &str, mut cond: impl FnMut() -> bool) {
        for _ in 0..200 {
            if cond() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        panic!("timed out waiting for: {what}");
    }

    /// Awaits a spawned start or hang-up, failing (rather than hanging) after two seconds.
    async fn within<T>(what: &str, task: JoinHandle<T>) -> T {
        tokio::time::timeout(Duration::from_secs(2), task)
            .await
            .unwrap_or_else(|_| panic!("timed out waiting for: {what}"))
            .unwrap()
    }

    fn attrs(state: &str) -> HashMap<String, String> {
        HashMap::from([(STATE_ATTRIBUTE.to_owned(), state.to_owned())])
    }

    // ---- tests ----------------------------------------------------------------------------

    #[tokio::test]
    async fn disabled_start_is_refused_without_a_link() {
        let rig = rig_with(JoinPlan::Ok, FakeAudio::default());
        let s = rig.calls.start(ROOM, AGENT, "Ada").await;
        assert!(!s.enabled);
        assert!(s.call.is_none());
        assert_eq!(s.notice.unwrap().text, DISABLED_COPY);
        assert!(rig.factory.created.lock().unwrap().is_empty());
        assert_eq!(*rig.audio.opens.lock().unwrap(), 0);
    }

    #[tokio::test]
    async fn refused_join_shows_the_copy_and_opens_nothing() {
        let rig = enabled(JoinPlan::Refuse).await;
        let s = rig.calls.start(ROOM, AGENT, "Ada").await;
        assert!(s.call.is_none());
        let n = s.notice.expect("a notice");
        assert_eq!(n.text, "Calls are not available for this agent yet");
        assert_eq!(n.text, REFUSED_COPY);
        assert_eq!(n.room_id, ROOM);
        assert_eq!(*rig.audio.opens.lock().unwrap(), 0, "no microphone");
        assert_eq!(*rig.factory.link().joins.lock().unwrap(), 1);
    }

    #[tokio::test]
    async fn join_goes_connecting_then_live_with_a_start_time() {
        let rig = enabled(JoinPlan::Ok).await;
        let s = rig.calls.start(ROOM, AGENT, "Ada").await;
        let call = s.call.expect("live");
        assert_eq!(call.phase, CallPhase::Live);
        assert!(call.started_at_ms.is_some());
        assert_eq!(call.agent_name, "Ada");
        assert_eq!(call.agent_state, None);
        let seen = rig.seen.0.lock().unwrap();
        let connecting = seen
            .iter()
            .find_map(|s| s.call.as_ref().filter(|c| c.phase == CallPhase::Connecting));
        let connecting = connecting.expect("Connecting was shown first");
        assert_eq!(connecting.started_at_ms, None);
        assert_eq!(
            rig.factory.created.lock().unwrap().as_slice(),
            &[(ROOM.to_owned(), AGENT.to_owned())]
        );
    }

    #[tokio::test]
    async fn agent_state_follows_the_agent_participant_only() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        // Our own participant, and a look-alike with a port, say "speaking": ignored.
        rig.factory.send(LinkEvent::Attributes {
            identity: "@u:s:DEV".into(),
            attributes: attrs("speaking"),
        });
        rig.factory.send(LinkEvent::Attributes {
            identity: "@agent_x:s:8448:DEV".into(),
            attributes: attrs("speaking"),
        });
        rig.factory.send(LinkEvent::Attributes {
            identity: AGENT_ID.into(),
            attributes: attrs("thinking"),
        });
        let calls = rig.calls.clone();
        eventually("thinking", || {
            calls.snapshot().call.unwrap().agent_state == Some(AgentCallState::Thinking)
        })
        .await;
        // No snapshot ever carried the non-agent's "speaking".
        assert!(rig.seen.0.lock().unwrap().iter().all(|s| s
            .call
            .as_ref()
            .is_none_or(|c| c.agent_state != Some(AgentCallState::Speaking))));
        rig.factory.send(LinkEvent::Attributes {
            identity: AGENT_ID.into(),
            attributes: attrs("nonsense"),
        });
        rig.factory.send(LinkEvent::Attributes {
            identity: AGENT_ID.into(),
            attributes: attrs("listening"),
        });
        eventually("listening", || {
            calls.snapshot().call.unwrap().agent_state == Some(AgentCallState::Listening)
        })
        .await;
    }

    #[tokio::test]
    async fn agent_audio_is_played_and_others_are_not() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        rig.factory.send(LinkEvent::Audio {
            identity: "@u:s:DEV".into(),
            samples: vec![9; 480],
        });
        rig.factory.send(LinkEvent::Audio {
            identity: AGENT_ID.into(),
            samples: vec![7; 480],
        });
        let played = rig.audio.played.clone();
        eventually("agent audio", || played.lock().unwrap().len() == 480).await;
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(played.lock().unwrap().iter().all(|s| *s == 7));
    }

    #[tokio::test]
    async fn mic_chunks_are_reframed_to_480() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        rig.audio.speak((0..300).map(|i| i as i16).collect());
        rig.audio.speak((300..1000).map(|i| i as i16).collect());
        let link = rig.factory.link();
        eventually("two frames", || link.captured.lock().unwrap().len() == 2).await;
        tokio::time::sleep(Duration::from_millis(20)).await;
        let frames = link.captured.lock().unwrap().clone();
        assert_eq!(frames.len(), 2, "the last 40 samples wait for more");
        assert!(frames.iter().all(|f| f.len() == FRAME_SAMPLES));
        assert_eq!(frames[0][0], 0);
        assert_eq!(frames[1][0], 480);
        assert_eq!(frames[1][479], 959);
    }

    #[tokio::test]
    async fn muted_frames_are_silence_and_unmute_resumes() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        let link = rig.factory.link();
        let s = rig.calls.set_muted(true);
        assert!(s.call.unwrap().muted);
        rig.audio.speak(vec![1000; 480]);
        eventually("a muted frame", || link.captured.lock().unwrap().len() == 1).await;
        assert!(
            link.captured.lock().unwrap()[0].iter().all(|s| *s == 0),
            "muted sends silence, so the agent's utterance can end"
        );
        let s = rig.calls.set_muted(false);
        assert!(!s.call.unwrap().muted);
        rig.audio.speak(vec![1000; 480]);
        eventually("an unmuted frame", || {
            link.captured.lock().unwrap().len() == 2
        })
        .await;
        assert!(link.captured.lock().unwrap()[1].iter().all(|s| *s == 1000));
    }

    #[tokio::test]
    async fn transcript_is_posted_as_spoken_turn() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        for text in ["first thing", "second thing"] {
            rig.factory.send(LinkEvent::Transcript(TranscriptContent {
                room_id: ROOM.into(),
                turn_id: format!("t-{text}"),
                text: text.into(),
                lang: Some("en".into()),
                audio_ms: 1200,
                sent_ts: 1,
            }));
        }
        let link = rig.factory.link();
        eventually("posted", || link.posted.lock().unwrap().len() == 2).await;
        assert_eq!(
            link.posted.lock().unwrap().as_slice(),
            &["first thing".to_owned(), "second thing".to_owned()],
            "in the order they were said"
        );
    }

    #[tokio::test]
    async fn hang_up_leaves_and_closes_audio() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        assert_eq!(*rig.audio.open_now.lock().unwrap(), 1);
        let s = rig.calls.hang_up().await;
        assert!(s.call.is_none());
        assert!(s.notice.is_none());
        assert_eq!(*rig.factory.link().leaves.lock().unwrap(), 1);
        assert_eq!(*rig.audio.open_now.lock().unwrap(), 0, "devices closed");
        // A second hang-up does nothing more.
        rig.calls.hang_up().await;
        assert_eq!(*rig.factory.link().leaves.lock().unwrap(), 1);
    }

    #[tokio::test]
    async fn hang_up_while_joining_leaves_after_join_and_opens_no_audio() {
        let rig = enabled(JoinPlan::Block).await;
        let calls = rig.calls.clone();
        let starting = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        let factory = rig.factory.clone();
        eventually("joining", || {
            factory
                .links
                .lock()
                .unwrap()
                .last()
                .is_some_and(|l| *l.joins.lock().unwrap() == 1)
        })
        .await;
        let s = rig.calls.hang_up().await;
        assert!(s.call.is_none(), "the bar goes at once");
        let link = rig.factory.link();
        link.release.notify_one();
        let s = starting.await.unwrap();
        assert!(s.call.is_none());
        assert_eq!(
            *link.leaves.lock().unwrap(),
            1,
            "left once the join returned"
        );
        assert_eq!(
            *rig.audio.opens.lock().unwrap(),
            0,
            "never opened the microphone"
        );
        assert!(rig.calls.snapshot().call.is_none());
    }

    #[tokio::test]
    async fn audio_failure_after_join_leaves_and_reports() {
        let rig = rig_with(
            JoinPlan::Ok,
            FakeAudio {
                fail: true,
                ..Default::default()
            },
        );
        rig.calls.set_enabled(true).await;
        let s = rig.calls.start(ROOM, AGENT, "Ada").await;
        assert!(s.call.is_none());
        assert_eq!(s.notice.unwrap().text, AUDIO_FAILED_COPY);
        assert_eq!(
            *rig.factory.link().leaves.lock().unwrap(),
            1,
            "membership cleared now, not left to the delayed leave"
        );
    }

    #[tokio::test]
    async fn second_start_is_refused_while_a_call_runs() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        let s = rig.calls.start("!other:s", "@agent_y:s", "Bo").await;
        let call = s.call.expect("the first call is untouched");
        assert_eq!(call.room_id, ROOM);
        assert_eq!(call.phase, CallPhase::Live);
        let n = s.notice.unwrap();
        assert_eq!(n.text, BUSY_COPY);
        assert_eq!(n.room_id, "!other:s");
        assert_eq!(rig.factory.created.lock().unwrap().len(), 1);
        assert_eq!(*rig.factory.link().leaves.lock().unwrap(), 0);
    }

    #[tokio::test]
    async fn disabling_hangs_up() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        let s = rig.calls.set_enabled(false).await;
        assert!(!s.enabled);
        assert!(s.call.is_none());
        assert_eq!(*rig.factory.link().leaves.lock().unwrap(), 1);
        assert_eq!(*rig.audio.open_now.lock().unwrap(), 0);
    }

    #[tokio::test]
    async fn disconnect_ends_the_call_with_a_notice() {
        let rig = enabled(JoinPlan::Ok).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        rig.factory.send(LinkEvent::Disconnected {
            reason: "ServerShutdown".into(),
        });
        let calls = rig.calls.clone();
        eventually("ended", || calls.snapshot().call.is_none()).await;
        let s = rig.calls.snapshot();
        assert_eq!(s.notice.unwrap().text, ENDED_COPY);
        let link = rig.factory.link();
        eventually("left", || *link.leaves.lock().unwrap() == 1).await;
        let open_now = rig.audio.open_now.clone();
        eventually("closed", || *open_now.lock().unwrap() == 0).await;
    }

    #[tokio::test]
    async fn disconnect_closes_the_devices_before_leaving() {
        let rig = enabled(JoinPlan::Ok).await;
        *rig.factory.hold_leave.lock().unwrap() = true;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        assert_eq!(*rig.audio.open_now.lock().unwrap(), 1);
        rig.factory.send(LinkEvent::Disconnected {
            reason: "ServerShutdown".into(),
        });
        let link = rig.factory.link();
        eventually("leaving", || *link.leaves.lock().unwrap() == 1).await;
        // The leave is stalled (a homeserver that does not answer): the microphone is
        // already closed, not held open until the leave returns.
        let open_now = *rig.audio.open_now.lock().unwrap();
        assert_eq!(open_now, 0, "devices closed before the leave");
        link.leave_release.notify_one();
    }

    /// Hung up while connecting, then Call again: superdial's leave clears the membership key
    /// both calls share (`_{user}_{device}_m.call`), so the second call must not join until
    /// the first has left.
    #[tokio::test]
    async fn a_new_call_waits_until_the_abandoned_one_has_left() {
        let rig = enabled(JoinPlan::Block).await;
        let calls = rig.calls.clone();
        let first = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        let factory = rig.factory.clone();
        eventually("joining", || factory.log() == ["join 1"]).await;
        let s = rig.calls.hang_up().await;
        assert!(s.call.is_none(), "the bar goes at once");
        assert!(s.leaving, "the abandoned call is still leaving");

        *rig.factory.plan.lock().unwrap() = JoinPlan::Ok;
        let calls = rig.calls.clone();
        let second = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        let calls = rig.calls.clone();
        eventually("second connecting", || calls.snapshot().call.is_some()).await;
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(
            rig.factory.created.lock().unwrap().len(),
            1,
            "the second call waits"
        );
        let s = rig.calls.snapshot();
        assert_eq!(s.call.unwrap().phase, CallPhase::Connecting);
        assert!(s.leaving);

        rig.factory.links.lock().unwrap()[0].release.notify_one();
        // The first start's answer already shows the second call connecting.
        within("the first start", first).await;
        let s = within("the second start", second).await;
        assert_eq!(s.call.unwrap().phase, CallPhase::Live);
        assert!(!s.leaving);
        assert_eq!(rig.factory.log(), ["join 1", "left 1", "join 2"]);
    }

    #[tokio::test]
    async fn a_new_call_waits_for_the_last_ones_leave_to_return() {
        let rig = enabled(JoinPlan::Ok).await;
        *rig.factory.hold_leave.lock().unwrap() = true;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        let calls = rig.calls.clone();
        let hanging = tokio::spawn(async move { calls.hang_up().await });
        let first = rig.factory.link();
        eventually("leaving", || *first.leaves.lock().unwrap() == 1).await;
        let s = rig.calls.snapshot();
        assert!(s.call.is_none());
        assert!(s.leaving, "the leave has not returned");

        *rig.factory.hold_leave.lock().unwrap() = false;
        let calls = rig.calls.clone();
        let second = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(
            rig.factory.log(),
            ["join 1"],
            "nothing joins while the leave runs"
        );

        first.leave_release.notify_one();
        assert!(!within("the hang-up", hanging).await.leaving);
        let s = within("the second start", second).await;
        assert_eq!(s.call.unwrap().phase, CallPhase::Live);
        assert_eq!(rig.factory.log(), ["join 1", "left 1", "join 2"]);
    }

    /// A leave that never returns (a homeserver or LiveKit that hangs) is given up on after
    /// [`LEAVE_LIMIT`]: `leaving` clears, and the next call can start rather than waiting
    /// until the app restarts.
    #[tokio::test(start_paused = true)]
    async fn a_leave_that_never_returns_is_given_up_on() {
        let rig = enabled(JoinPlan::Ok).await;
        *rig.factory.hold_leave.lock().unwrap() = true;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        let calls = rig.calls.clone();
        let hanging = tokio::spawn(async move { calls.hang_up().await });
        let first = rig.factory.link();
        eventually("leaving", || *first.leaves.lock().unwrap() == 1).await;
        assert!(rig.calls.snapshot().leaving);

        // Never released. The clock is paused, so the wait costs no real time.
        let s = tokio::time::timeout(LEAVE_LIMIT * 2, hanging)
            .await
            .expect("the hang-up waited on the leave forever")
            .unwrap();
        assert!(
            !s.leaving,
            "a leave that never returned still shows as leaving"
        );

        *rig.factory.hold_leave.lock().unwrap() = false;
        let s = rig.calls.start(ROOM, AGENT, "Ada").await;
        assert_eq!(s.call.unwrap().phase, CallPhase::Live);
    }

    /// A call hung up while it waits for an earlier one to leave gives up at once, without
    /// making a link; the next still waits for the first.
    #[tokio::test]
    async fn a_call_hung_up_while_waiting_gives_up_at_once() {
        let rig = enabled(JoinPlan::Block).await;
        let calls = rig.calls.clone();
        let first = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        let factory = rig.factory.clone();
        eventually("joining", || factory.log() == ["join 1"]).await;
        rig.calls.hang_up().await;
        *rig.factory.plan.lock().unwrap() = JoinPlan::Ok;
        let calls = rig.calls.clone();
        let second = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        let calls = rig.calls.clone();
        eventually("second connecting", || calls.snapshot().call.is_some()).await;
        rig.calls.hang_up().await;
        let s = within("the second call to give up", second).await;
        assert!(s.call.is_none());
        let calls = rig.calls.clone();
        let third = tokio::spawn(async move { calls.start(ROOM, AGENT, "Ada").await });
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(
            rig.factory.log(),
            ["join 1"],
            "the third waits for the first"
        );
        rig.factory.links.lock().unwrap()[0].release.notify_one();
        within("the first start", first).await;
        let s = within("the third start", third).await;
        assert_eq!(s.call.unwrap().phase, CallPhase::Live);
        assert!(!s.leaving);
        assert_eq!(
            rig.factory.created.lock().unwrap().len(),
            2,
            "the second never made a link"
        );
        assert_eq!(rig.factory.log(), ["join 1", "left 1", "join 2"]);
    }

    /// `start` answers with the Live snapshot, and the agent's state arrives on the observer
    /// after it; the host can only order them by `seq`.
    #[tokio::test]
    async fn every_change_has_a_higher_seq_than_the_one_before() {
        let rig = enabled(JoinPlan::Ok).await;
        let live = rig.calls.start(ROOM, AGENT, "Ada").await;
        rig.factory.send(LinkEvent::Attributes {
            identity: AGENT_ID.into(),
            attributes: attrs("listening"),
        });
        let calls = rig.calls.clone();
        eventually("listening", || {
            calls.snapshot().call.unwrap().agent_state == Some(AgentCallState::Listening)
        })
        .await;
        rig.calls.set_muted(true);
        rig.calls.hang_up().await;
        let seen = rig.seen.0.lock().unwrap().clone();
        assert!(
            seen.len() >= 5,
            "enable, connecting, live, listening, mute, hang-up"
        );
        for pair in seen.windows(2) {
            assert!(
                pair[1].seq > pair[0].seq,
                "{} then {}",
                pair[0].seq,
                pair[1].seq
            );
        }
        let listening = seen
            .iter()
            .find(|s| {
                s.call
                    .as_ref()
                    .is_some_and(|c| c.agent_state == Some(AgentCallState::Listening))
            })
            .expect("the agent's state was told");
        assert!(listening.seq > live.seq, "the state came after the answer");
        // Asking is not a change.
        let last = seen.last().unwrap().seq;
        assert_eq!(rig.calls.snapshot().seq, last);
        assert_eq!(rig.calls.snapshot().seq, last);
    }

    #[tokio::test]
    async fn dismissing_the_notice_clears_it() {
        let rig = enabled(JoinPlan::Refuse).await;
        rig.calls.start(ROOM, AGENT, "Ada").await;
        assert!(rig.calls.dismiss_notice().notice.is_none());
    }
}
