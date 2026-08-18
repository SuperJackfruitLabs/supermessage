//! Ownership seam for the logged-in Matrix account.
//!
//! One `matrix_sdk::Client` per account, owned here and never handed to the
//! webview. `Session` builds the client with an encrypted store (see
//! [`Session::build_client`]), drives login/restore/logout through the
//! [`AuthProvider`] trait, and hands out cheap clones of the client handle
//! to the rest of the core.
//!
//! **Session lifecycle transitions are serialized** through
//! [`Session::lifecycle`]: [`Session::login_and_start`],
//! [`Session::restore_and_start`] and [`Session::logout`] each hold that
//! mutex for their whole duration, and `restore_and_start` is a no-op when a
//! client already exists. Together those two rules are what make it
//! impossible for two `Client`s, two `SyncService`s or two room-list tasks
//! to be live at the same time — a second set would emit onto the same Tauri
//! events with its own `SeqCounter` restarting at 1, which the webview
//! cannot tell apart from a corrupted stream.

use std::path::PathBuf;
use std::sync::Arc;

use matrix_sdk::{
    ruma::{
        api::client::room::create_room::v3::{Request as CreateRoomRequest, RoomPreset},
        EventId, RoomId, RoomOrAliasId, UserId,
    },
    Client,
};
use tauri::AppHandle;
use tauri_plugin_dialog::DialogExt;
use tokio::sync::{Mutex, RwLock};

use super::attachments;
use super::auth::password::PasswordAuth;
use super::auth::AuthProvider;
use super::dto::RoomSummary;
use super::error::{CoreError, CoreResult};
use super::live;
use super::media;
use super::room_info::{self, RoomInfoDto};
use super::rooms::{self, RoomListHandle, SpaceSelection};
use super::search::{self, SearchResultDto};
use super::secrets::{generate_passphrase, SecretStore, KEY_HOMESERVER_URL, KEY_STORE_PASSPHRASE};
use super::spaces::{self, SpaceSummary};
use super::sync::{self, SyncHandle};
use super::timeline::FocusedTimeline;
use super::tls;

/// Holds the active account's client, if any.
///
/// Registered as Tauri managed state so commands can reach it.
pub struct Session {
    data_dir: PathBuf,
    store: Box<dyn SecretStore>,
    auth: PasswordAuth,
    client: RwLock<Option<Client>>,
    // Owns the running `SyncService` (see sync.rs's doc comment on
    // `SyncHandle` for why: a `SyncHandle` nobody stores stops sync the
    // moment it's dropped). `start_sync`/`stop_sync` are the only writers.
    sync: RwLock<Option<SyncHandle>>,
    // Owns the room-list streaming task (see rooms.rs's doc comment on
    // `RoomListHandle` for why it must be replaced-and-stopped, never just
    // dropped-by-overwrite). `start_room_list`/`stop_room_list` are the only
    // writers.
    rooms: RwLock<Option<RoomListHandle>>,
    // The focused room's timeline subscription. Also handed to Tauri as
    // managed state (see `Session::focused_timeline`), but owned here so
    // that `logout` can tear it down: the timeline task holds
    // `Arc<Timeline>` -> `Room` -> `Client`, which keeps the store's SQLite
    // files open, and `logout` deletes those files.
    focused: Arc<FocusedTimeline>,
    // Files the reader has picked or dropped but not yet sent. Also handed
    // to Tauri as managed state (see `Session::staged_attachments`), and
    // owned here for the same reason `focused` is: `logout` has to clear it.
    // A staging token is a path this process is holding on behalf of an
    // account; outliving that account it becomes a path held on behalf of
    // whoever logs in next.
    staged: Arc<attachments::StagedAttachments>,
    // Serializes whole session transitions (login, restore, logout) against
    // each other — see this module's doc comment.
    lifecycle: Mutex<()>,
}

impl Session {
    pub fn new(data_dir: PathBuf, store: Box<dyn SecretStore>) -> Self {
        Self {
            data_dir,
            store,
            auth: PasswordAuth,
            client: RwLock::new(None),
            sync: RwLock::new(None),
            rooms: RwLock::new(None),
            focused: Arc::new(FocusedTimeline::default()),
            staged: Arc::new(attachments::StagedAttachments::default()),
            lifecycle: Mutex::new(()),
        }
    }

    /// The focused-room timeline this session owns, for registration as
    /// Tauri managed state alongside the session itself.
    ///
    /// `FocusedTimeline` has to be reachable from the command layer (the
    /// timeline commands operate on it directly) *and* from `logout` (which
    /// must tear it down before wiping the store on disk). Handing out an
    /// `Arc` clone of the one the session owns is what keeps those two views
    /// of it the same object — registering an independent
    /// `FocusedTimeline::default()` as managed state would leave `logout`
    /// clearing a timeline nothing else ever uses.
    pub fn focused_timeline(&self) -> Arc<FocusedTimeline> {
        Arc::clone(&self.focused)
    }

    /// The staged-attachment map this session owns, for registration as
    /// Tauri managed state alongside the session itself.
    ///
    /// Same shape, and same reason, as [`Self::focused_timeline`]: the
    /// attachment commands reach it directly, and [`Self::logout`] must be
    /// able to clear it. Registering an independent
    /// `StagedAttachments::default()` as managed state would leave logout
    /// clearing a map nothing else ever touches, which is the failure mode
    /// worth naming here because it would be *silent* — the commands would
    /// work perfectly and the staged paths would simply survive the logout.
    pub fn staged_attachments(&self) -> Arc<attachments::StagedAttachments> {
        Arc::clone(&self.staged)
    }

    /// Logs in fresh with a username and password, building a new client
    /// backed by an encrypted store and persisting the resulting session.
    pub async fn login(&self, homeserver: &str, username: &str, password: &str) -> CoreResult<()> {
        let client = self.build_client(homeserver).await?;
        self.auth.login(&client, username, password).await?;
        self.auth.persist(&client, self.store.as_ref()).await?;
        // The persisted session carries only auth tokens and device
        // identity, never the homeserver — a later `restore` needs this to
        // rebuild an identical client without asking the user again.
        self.store.set(KEY_HOMESERVER_URL, homeserver)?;
        *self.client.write().await = Some(client);
        Ok(())
    }

    /// Attempts to restore a previously persisted session, rebuilding the
    /// client against the same homeserver and encrypted store used at login.
    ///
    /// Returns `Ok(false)` when there is nothing to restore — the normal
    /// first-run path, not an error.
    pub async fn restore(&self) -> CoreResult<bool> {
        let Some(homeserver) = self.store.get(KEY_HOMESERVER_URL)? else {
            return Ok(false);
        };

        let client = self.build_client(&homeserver).await?;

        if !self.auth.restore(&client, self.store.as_ref()).await? {
            return Ok(false);
        }

        *self.client.write().await = Some(client);
        Ok(true)
    }

    /// Logs out and drops the active client, if any. Clears local state even
    /// if the server-side call fails, so the user is never stuck "logged in"
    /// with no way back out.
    ///
    /// Also wipes the encrypted store directory (per the M0 plan: `logout`
    /// clears "session, secrets and stores"). `PasswordAuth::logout` deletes
    /// the store passphrase, so leaving the old encrypted store on disk
    /// would make it unopenable by any later `login` (which generates a
    /// fresh passphrase) — and leaving message history, room state and
    /// crypto keys decryptable-if-you-can-reach-the-keyring on disk after
    /// logout is not acceptable for a chat client regardless.
    pub async fn logout(&self) -> CoreResult<()> {
        let _lifecycle = self.lifecycle.lock().await;
        // Tear every background consumer of the client down first, and
        // *wait* for each task to actually finish, not merely ask it to.
        // Otherwise those loops keep running against a client we're about
        // to drop, and — the reason this must complete before
        // `remove_store()` below — each of them transitively holds the
        // `Client`, so each keeps the store's SQLite files open. On POSIX an
        // unlink of an open file succeeds anyway; on Windows
        // `remove_dir_all` fails, and it would fail *after*
        // `PasswordAuth::logout` has already deleted the store passphrase,
        // leaving an encrypted store no future passphrase can ever open and
        // every subsequent login failing permanently.
        //
        // Order is dependency order: the focused timeline hangs off a
        // `Room`, the room list off the sync service's `RoomListService`,
        // and the sync service off the client.
        // Before anything else, and unconditionally: a staged file is a path
        // this process is holding for an account that is about to stop
        // existing. Nothing here can fail, so it needs no rollback and can
        // safely precede the parts that can.
        self.staged.clear();
        self.focused.clear_and_join().await;
        self.stop_room_list().await;
        self.stop_sync().await;
        // Clone the handle (cheap — `Client` is internally reference
        // counted) and drop the read lock before the network call below, so
        // a concurrent `client()`/`require_client()` is never blocked on it.
        let active = self.client().await;
        if let Some(active) = &active {
            self.auth.logout(active, self.store.as_ref()).await?;
        }
        self.store.delete(KEY_HOMESERVER_URL)?;
        *self.client.write().await = None;
        // Drop our own strong reference before touching the store directory
        // on disk, so nothing here still has the SQLite files open.
        drop(active);
        self.remove_store()?;
        Ok(())
    }

    /// Logs in and starts this session's streams, as one serialized
    /// transition — the whole of what the `login` command does.
    ///
    /// Holds [`Session::lifecycle`] throughout so it cannot interleave with
    /// a concurrent restore or logout; see this module's doc comment.
    pub async fn login_and_start(
        &self,
        homeserver: &str,
        username: &str,
        password: &str,
        app: AppHandle,
    ) -> CoreResult<()> {
        let _lifecycle = self.lifecycle.lock().await;
        self.login(homeserver, username, password).await?;
        self.start_or_roll_back(app).await
    }

    /// Starts the streams, and on failure leaves *nothing* installed rather
    /// than a client with no streams behind it.
    ///
    /// A half-started session is worse than no session: the client would
    /// still satisfy [`Self::is_active`], so a later `restore_and_start`
    /// short-circuits on it and the user sits in a logged-in UI with a dead
    /// sync and an empty room list, with no way back except signing out. The
    /// persisted session in the keyring is deliberately left alone — the
    /// next `restore_and_start` then gets a clean full attempt instead of
    /// minting another device with a fresh login.
    async fn start_or_roll_back(&self, app: AppHandle) -> CoreResult<()> {
        if let Err(err) = self.start_streams(app).await {
            self.stop_room_list().await;
            self.stop_sync().await;
            *self.client.write().await = None;
            return Err(err);
        }
        Ok(())
    }

    /// Restores a persisted session and starts its streams, as one
    /// serialized transition — the whole of what the `restore_session`
    /// command does. Returns `false` when there was nothing to restore.
    ///
    /// **Idempotent by design.** When a client is already active this
    /// returns `Ok(true)` without touching anything. The webview cannot be
    /// relied on to never ask twice — `/login` navigates to `/` on success,
    /// and `/`'s own mount restores — and a second restore would be far
    /// worse than a wasted round trip: it builds a *second* `Client` against
    /// the same store and device id, and a second `SyncService` and
    /// room-list task whose `SeqCounter` restarts at 1 while the first one's
    /// task is still emitting. The webview arms its tracker for the new
    /// stream, gets an old-stream envelope at a much higher seq, reads it as
    /// a gap, resyncs off the *old* handle, and then discards the entire new
    /// stream as duplicates — a room list frozen at login for the rest of
    /// the session. Guarding here rather than at the call site fixes that
    /// for every caller, present and future.
    pub async fn restore_and_start(&self, app: AppHandle) -> CoreResult<bool> {
        let _lifecycle = self.lifecycle.lock().await;
        // Deliberately "already *running*", not merely "a client exists".
        // A client with no streams behind it is exactly the state a failed
        // start leaves behind, and short-circuiting on it would strand the
        // user in a logged-in UI with dead sync forever. `start_or_roll_back`
        // makes that state unreachable from here; this makes the guard say
        // what it actually means either way.
        if self.is_running().await {
            return Ok(true);
        }
        if !self.restore().await? {
            return Ok(false);
        }
        self.start_or_roll_back(app).await?;
        Ok(true)
    }

    /// Starts sync for the currently logged-in client and stores the
    /// resulting handle, stopping any sync already running first.
    ///
    /// Fails with [`CoreError::NotReady`] when nothing is logged in yet.
    ///
    /// **Stop-before-start, not start-then-stop.** `SyncHandle::stop` awaits
    /// `SyncService::stop()`, whose supervisor sets `State::Idle` before
    /// returning — so the outgoing handle's watcher wakes with `Idle` and
    /// emits `"offline"` on the way down. Starting the new service first
    /// would let that `"offline"` land *after* the new service has already
    /// emitted `"live"`, and since nothing re-emits until the next state
    /// transition the banner would then read "Offline" indefinitely while
    /// sync was in fact running. Tearing the old one down first puts those
    /// two emissions back in the order they describe.
    pub async fn start_sync(&self, app: AppHandle) -> CoreResult<()> {
        let client = self.require_client().await?;
        self.stop_sync().await;
        let handle = sync::start(&client, app).await?;
        *self.sync.write().await = Some(handle);
        Ok(())
    }

    /// The connection health right now, for a webview that missed the
    /// transition.
    ///
    /// Nothing re-emits [`sync::CONNECTION_EVENT`] between transitions, and a
    /// healthy session transitions to `Running` once and then stops — so a
    /// webview created after that (right-click → Reload, or an HMR module
    /// swap) has nothing to listen for and would show "Offline" over a live
    /// connection until something went wrong. Asking is the only way to
    /// learn it.
    ///
    /// No session, no sync handle, and `offline` is simply true.
    pub async fn connection_state(&self) -> sync::ConnectionPayload {
        match self.sync.read().await.as_ref() {
            Some(handle) => handle.connection(),
            None => sync::ConnectionPayload::offline(),
        }
    }

    /// Stops sync and drops the handle, if any is running. A safe no-op when
    /// nothing was started.
    pub async fn stop_sync(&self) {
        if let Some(handle) = self.sync.write().await.take() {
            handle.stop().await;
        }
    }

    /// Starts streaming the room list for the currently running sync and
    /// stores the resulting handle, stopping any room-list stream already
    /// running first.
    ///
    /// Fails with [`CoreError::NotReady`] when sync hasn't been started yet
    /// (`start_sync` must run first — the room list is projected from its
    /// `RoomListService`).
    ///
    /// **Stop-before-start, not start-then-stop.** `spawn_room_list` awaits
    /// `all_rooms()`, which can take a while; building the new handle first
    /// leaves the old task emitting envelopes — with its own independent,
    /// much higher `seq` — throughout that window, onto the very event the
    /// webview has just re-armed its tracker for. The webview reads that as
    /// a gap and resyncs off the still-installed old handle, after which the
    /// new stream's `seq: 1, 2, 3, ...` all look like duplicates and are
    /// discarded forever. Stopping first means at most one room-list task
    /// can ever be emitting.
    pub async fn start_room_list(&self, app: AppHandle) -> CoreResult<()> {
        self.stop_room_list().await;
        let handle = {
            let sync = self.sync.read().await;
            let sync_handle = sync.as_ref().ok_or(CoreError::NotReady)?;
            rooms::spawn_room_list(sync_handle, app).await?
        };
        *self.rooms.write().await = Some(handle);
        Ok(())
    }

    /// Stops room-list streaming and drops the handle, if any is running,
    /// waiting for the streaming task to actually finish before returning.
    /// A safe no-op when nothing was started.
    pub async fn stop_room_list(&self) {
        let handle = self.rooms.write().await.take();
        if let Some(mut handle) = handle {
            handle.stop_and_join().await;
        }
    }

    /// Starts sync, then room-list streaming, as one step callers can treat
    /// as atomic: if room-list streaming fails to start, sync is stopped
    /// again before the error is returned, rather than leaving an
    /// authenticated session with a sync loop running that nothing is
    /// driving cleanup for.
    ///
    /// `stop_sync` can't itself fail (it only awaits/aborts already-running
    /// tasks — see its signature), so this rollback never has a second error
    /// to weigh against the first: whatever `start_room_list` failed with is
    /// always what's returned.
    ///
    /// Used by the `login`/`restore_session` commands, which would otherwise
    /// need this same start-then-rollback sequencing themselves.
    pub async fn start_streams(&self, app: AppHandle) -> CoreResult<()> {
        // Tear the previous session's streams down in dependency order (the
        // room list is projected from the sync service's `RoomListService`,
        // not the other way around) before building anything new.
        // `start_sync`/`start_room_list` each stop their own predecessor
        // too, so these are usually no-ops — but doing it here as well is
        // what gets the *ordering between the two* right.
        self.stop_room_list().await;
        self.stop_sync().await;
        self.start_sync(app.clone()).await?;
        if let Err(err) = self.start_room_list(app.clone()).await {
            self.stop_sync().await;
            return Err(err);
        }

        // The live view of a turn in progress (`core::live`). Registered on the
        // client, so it needs no teardown of its own: `logout` builds a new
        // client and this goes with the old one. Deliberately not fallible —
        // a session without a live view is a session that works, just without
        // watching an agent think.
        if let Ok(client) = self.require_client().await {
            live::listen(&client, app);
        }
        Ok(())
    }

    /// A snapshot of the room list — the sequence number of the last diff
    /// folded in, and the resulting list — read out of the currently running
    /// room-list stream's own state. See `RoomListHandle::snapshot`'s doc
    /// comment for why this can't be served from a second subscription.
    ///
    /// Fails with [`CoreError::NotReady`] when room-list streaming hasn't
    /// been started yet (i.e. before [`Self::start_room_list`] has run).
    pub async fn rooms_snapshot(&self) -> CoreResult<(u64, Vec<RoomSummary>)> {
        let rooms = self.rooms.read().await;
        let handle = rooms.as_ref().ok_or(CoreError::NotReady)?;
        handle.snapshot()
    }

    /// The account's joined spaces, each with the count of joined rooms its
    /// subtree flattens to — the spaces rail's whole data set.
    ///
    /// **A one-shot fetch, not a third diff-streamed channel** (spaces-rail
    /// design §5). Spaces change far less than the room list; the frontend
    /// re-fetches on session start and after a resync. Promoting this to a
    /// stream later is a contained change, and starting there would be
    /// machinery bought before it is needed.
    ///
    /// **Deliberately not serialized through [`Self::lifecycle`]**, for the
    /// reason [`Self::room_avatar`]'s doc comment gives: this is a one-shot
    /// read that clones the client, walks the local state store and returns,
    /// leaving nothing running. Losing a race with `logout` fails the call
    /// rather than leaking a handle across the teardown.
    ///
    /// Fails with [`CoreError::NotReady`] when nothing is logged in.
    pub async fn spaces_list(&self) -> CoreResult<Vec<SpaceSummary>> {
        let client = self.require_client().await?;
        Ok(spaces::build_space_index(&client).await?.summaries())
    }

    /// Scopes the roster to `space_id`'s flattened subtree, or restores every
    /// room when it is `None`.
    ///
    /// **Nothing about the focused room or its timeline is touched** (design
    /// §7). Filtering the roster is a change to a navigation surface; the
    /// room pane keeps showing whatever the reader is reading, even if the
    /// switch filters it out of the list. A space switch that re-subscribed
    /// the timeline would restart *that* channel's sequence counter behind a
    /// `DiffTracker` armed for the old one, which is the same silent
    /// corruption the room-list channel guards against — so this deliberately
    /// has no path to `subscribe_timeline`.
    ///
    /// **Refuses rather than no-ops when there is no session.**
    /// `NotReady` — the same answer every other command gives — because a
    /// selection with nothing to select from is a frontend bug, and silently
    /// swallowing it would let the rail believe the roster is scoped when no
    /// roster exists. The frontend re-fetches `spaces_list` on session start
    /// anyway, so there is no legitimate caller in that state.
    ///
    /// **A space that has vanished fails with [`CoreError::UnknownSpace`]**
    /// rather than falling back to "All rooms" — see
    /// `core::spaces::SpaceIndex::rooms_in` for why the silent fall back is
    /// the worse of the two.
    ///
    /// **Selection does not survive logout, by construction.** It lives in
    /// the room-list stream task, which `logout` stops
    /// ([`Self::stop_room_list`]); the next session's task starts at
    /// [`SpaceSelection::All`]. Nothing here persists it, and nothing should:
    /// a restored session's spaces may not be the previous account's at all.
    pub async fn select_space(&self, space_id: Option<&str>) -> CoreResult<()> {
        let client = self.require_client().await?;

        let selection = match space_id {
            None => SpaceSelection::All,
            Some(space_id) => {
                let parsed =
                    RoomId::parse(space_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
                let room_ids = spaces::build_space_index(&client)
                    .await?
                    .rooms_in(&parsed)?;
                SpaceSelection::Space { room_ids }
            }
        };

        // Resolved *before* the handle is borrowed, so the subtree walk never
        // holds the room-list lock, and so a `NotReady`/`UnknownSpace`
        // refusal leaves the running filter untouched.
        let rooms = self.rooms.read().await;
        rooms
            .as_ref()
            .ok_or(CoreError::NotReady)?
            .select_space(selection)
    }

    /// Clones the active client handle. `Client` is internally reference
    /// counted, so this is cheap and callers must not store it long-term.
    pub async fn client(&self) -> Option<Client> {
        self.client.read().await.clone()
    }

    /// Whether a client is logged in at all, regardless of whether its
    /// streams are up.
    pub async fn is_active(&self) -> bool {
        self.client.read().await.is_some()
    }

    /// Whether a client is logged in **and** both of its streams are
    /// running — the state a usable session is actually in.
    ///
    /// This is the predicate [`Self::restore_and_start`]'s idempotence guard
    /// is built on; see that method for what a second, unguarded restore
    /// costs. It deliberately checks the streams too and not just the
    /// client: the two come apart, because the client is installed before
    /// its streams start, so [`Self::is_active`] alone would also be true of
    /// a session whose start failed half way — precisely the state the guard
    /// must not mistake for a healthy one.
    pub async fn is_running(&self) -> bool {
        self.is_active().await
            && self.sync.read().await.is_some()
            && self.rooms.read().await.is_some()
    }

    /// Subscribes the focused timeline to `room_id`, serialized against the
    /// session transitions.
    ///
    /// Holds [`Session::lifecycle`] for the same reason `logout` does, and
    /// specifically against `logout`: without it, a subscribe landing in the
    /// window between `logout`'s teardown and its `remove_store()` would
    /// install a fresh timeline task holding `Arc<Timeline>` -> `Room` ->
    /// `Client`, keeping the store's SQLite files open across the very
    /// deletion the teardown exists to make safe. Harmless on POSIX, but on
    /// Windows `remove_dir_all` then fails *after* the passphrase has been
    /// dropped, leaving a store nothing can ever open again.
    ///
    /// Also the enforcement point for "a staged attachment is discarded on
    /// room switch" (attachments design §3): a token minted while room A was
    /// focused can never be redeemed once B is focused — both
    /// `FocusedTimeline`'s guard and the token's own room binding refuse it —
    /// so keeping it would pin a path nothing can ever use. The discard runs
    /// *after* the subscribe succeeds, so a failed room switch leaves the
    /// reader's staged file exactly where it was.
    pub async fn subscribe_timeline(&self, room_id: &str, app: AppHandle) -> CoreResult<()> {
        let _lifecycle = self.lifecycle.lock().await;
        let client = self.require_client().await?;
        self.focused.subscribe(&client, room_id, app).await?;
        self.staged.retain_room(room_id);
        Ok(())
    }

    /// Like [`Self::client`], but fails with [`CoreError::NotReady`] when
    /// logged out, so the UI can distinguish "not logged in yet" from a real
    /// failure.
    pub async fn require_client(&self) -> CoreResult<Client> {
        self.client().await.ok_or(CoreError::NotReady)
    }

    /// Resolves `room_id`'s avatar and fetches it as a thumbnail, encoded as
    /// a `data:` URI the webview can render directly.
    ///
    /// Resolution (`rooms::resolve_room_avatar_mxc`) consults, in order, the
    /// room's own avatar, a sole hero's avatar, and — the fallback that
    /// actually fires against this deployment, since Synapse omits heroes
    /// for named rooms — the other member's avatar in a two-person room.
    /// That step needs the room's member list, which is why this takes a
    /// room id and does the resolution itself rather than the webview
    /// passing an mxc URI it already has (`RoomSummary.avatarUrl` reflects
    /// only what `core::rooms::project_room`'s synchronous projection can
    /// determine — see `core::rooms`'s doc comments on why the async,
    /// member-based fallback can't live there). See `core::media`'s doc
    /// comment for why a `data:` URI rather than an `http(s)://` URL.
    ///
    /// **Deliberately not serialized through [`Self::lifecycle`]**, unlike
    /// [`Self::subscribe_timeline`]. What that guard protects against is a
    /// *long-lived task* getting installed into `FocusedTimeline` inside
    /// logout's teardown window — one that would keep holding
    /// `Arc<Timeline>` -> `Room` -> `Client` (and so the store's open SQLite
    /// files) indefinitely, past the point `logout` deletes them. A
    /// `room_avatar` call is a one-shot read: it clones the `Client` handle,
    /// awaits resolution plus a single fetch, and drops the clone as soon as
    /// this function returns — the same shape as
    /// `FocusedTimeline::paginate_back` and `send_text`, neither of which
    /// holds this lock either. Losing a race with a concurrent `logout` here
    /// just fails the call (`NotReady` if `logout` already cleared the
    /// client, or a network/store error if it wins mid-fetch) rather than
    /// leaking a live handle across the deletion — nothing is left running
    /// afterwards for the lock to have protected. (The race is bounded, not
    /// absent — matching the existing `paginate_back`/`send_text`
    /// precedent, not a new hazard.)
    pub async fn room_avatar(&self, room_id: &str) -> CoreResult<Option<String>> {
        let client = self.require_client().await?;
        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

        let Some(mxc_uri) = rooms::resolve_room_avatar_mxc(&room).await? else {
            return Ok(None);
        };

        media::avatar_thumbnail(&client, &mxc_uri).await
    }

    /// Fetches `event_id`'s media as a thumbnail, encoded as a `data:` URI
    /// the webview can render directly — the same shape as
    /// [`Self::room_avatar`], but for a message's `m.image`/`m.file`/
    /// `m.audio`/`m.video` content instead of a room's avatar.
    ///
    /// `event_id` is looked up against the *focused* timeline
    /// (`FocusedTimeline::media_source`), not against a room the webview
    /// names separately — the same "only one timeline is ever subscribed"
    /// model every other timeline command already relies on. Returns
    /// `Ok(None)`, never an error, for "no such event in the focused
    /// timeline", "found it, but it isn't media", and "fetched it, but the
    /// bytes don't sniff to a renderable image format" alike — the webview's
    /// `mediaCache` treats all three as "nothing to show", falling back to
    /// the informative placeholder row (see `$lib/stores/mediaCache.svelte.ts`).
    ///
    /// **Deliberately not serialized through [`Self::lifecycle`]**, for
    /// exactly the reason [`Self::room_avatar`]'s doc comment gives for
    /// itself: this is a one-shot read (clone the client, resolve the
    /// source, fetch, return) with nothing left running afterward for the
    /// lock to have protected. Losing a race with a concurrent `logout`
    /// here just fails the call rather than leaking a live handle across
    /// the teardown.
    /// Fetches an event's media in full and saves it wherever the reader
    /// chooses, returning that path — or `None` when they cancelled, or the
    /// event carries no media.
    ///
    /// The save dialog is opened from the **Rust** side, like the attachment
    /// picker (`core::attachments`) and for the same reason: a path chosen in
    /// the webview would be a path the webview controls. Here the webview
    /// names an event; everything about where the bytes land is decided out
    /// here, and the sender's filename is stripped to its last component
    /// before it is ever suggested (`core::timeline::media_filename`).
    ///
    /// Awaits a oneshot rather than blocking: the dialog stays up for as long
    /// as somebody browses their home directory, and a blocked worker thread
    /// would be held for all of it.
    /// Searches every room this account can see.
    ///
    /// Server-side (`POST /_matrix/client/v3/search`) — see `core::search` for
    /// why that rather than a local index, and for the one condition it rests
    /// on: these rooms are unencrypted, so the homeserver can index them.
    pub async fn search_messages(&self, term: &str) -> CoreResult<Vec<SearchResultDto>> {
        let client = self.require_client().await?;
        search::search_messages(&client, term).await
    }

    pub async fn media_download(
        &self,
        app: &AppHandle,
        event_id: &str,
    ) -> CoreResult<Option<String>> {
        let client = self.require_client().await?;
        let parsed_event_id =
            EventId::parse(event_id).map_err(|e| CoreError::Protocol(e.to_string()))?;

        let Some((source, filename)) = self.focused.media_descriptor(&parsed_event_id).await?
        else {
            return Ok(None);
        };

        // Fetched before the dialog, so a reader who has chosen a path is not
        // then made to wait on a 40MB download with no explanation — and so a
        // failed fetch never leaves a zero-byte file behind.
        let bytes = media::message_media_file(&client, source).await?;

        let (tx, rx) = tokio::sync::oneshot::channel();
        app.dialog()
            .file()
            .set_file_name(&filename)
            .save_file(move |picked| {
                // Gone only if this command was cancelled, in which case there
                // is nobody left to tell.
                let _ = tx.send(picked);
            });

        let Some(path) = rx
            .await
            .map_err(|_| CoreError::Protocol("the save dialog closed unexpectedly".into()))?
        else {
            return Ok(None);
        };

        let path = path
            .into_path()
            .map_err(|e| CoreError::Protocol(e.to_string()))?;

        tokio::fs::write(&path, &bytes)
            .await
            .map_err(|e| CoreError::Protocol(format!("could not write {}: {e}", path.display())))?;

        Ok(Some(path.display().to_string()))
    }

    pub async fn media_fetch(&self, event_id: &str) -> CoreResult<Option<String>> {
        let client = self.require_client().await?;
        let parsed_event_id =
            EventId::parse(event_id).map_err(|e| CoreError::Protocol(e.to_string()))?;

        let Some(source) = self.focused.media_source(&parsed_event_id).await? else {
            return Ok(None);
        };

        media::message_media_thumbnail(&client, source).await
    }

    /// Builds `room_id`'s [`RoomInfoDto`] — name, topic, canonical alias,
    /// alt aliases, room id and joined member list — for the room-info
    /// panel.
    ///
    /// **Room-scoped and focus-checked**, following the same pattern every
    /// timeline command in `core::commands` already takes for the reason
    /// `core::timeline::verify_room_focus`'s doc comment gives: a command
    /// that silently resolved against "whatever room is current" could act
    /// on the wrong room after a switch the caller lost a race against. Here
    /// that would mean showing one room's topic/members under another
    /// room's header — a real, if less damaging, wrong-recipient bug, so
    /// this takes the same guard rather than being the one command in this
    /// codebase that doesn't.
    ///
    /// The check reads the focused room id out of
    /// [`FocusedTimeline::snapshot`] rather than a purpose-built accessor:
    /// `core::timeline` is intentionally not touched by this change (see
    /// this codebase's session-scoped edit rules at the time this was
    /// written), and `snapshot` is the only existing `pub` method on
    /// `FocusedTimeline` that reveals the focused room id without also
    /// performing a live send/paginate against it. The trade-off, noted
    /// rather than hidden: `snapshot` clones the whole materialized item
    /// list to get there, which this call then discards unused. That cost is
    /// paid once per explicit "open the room-info panel" action, not on
    /// every keystroke or every timeline diff, so it was judged acceptable
    /// rather than justifying a new `core::timeline` accessor mid-edit of a
    /// file another change was actively working in.
    ///
    /// Fails with [`CoreError::NotReady`] when no room is focused at all, or
    /// [`CoreError::RoomChanged`] when `room_id` isn't the one that is.
    pub async fn room_info(&self, room_id: &str) -> CoreResult<RoomInfoDto> {
        let client = self.require_client().await?;
        // Check-only: `snapshot()` would clone the whole materialised item
        // list just to read one room id.
        self.focused.verify_focus(room_id)?;

        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

        room_info::build_room_info(&room).await
    }

    /// Accepts an invitation to `room_id`.
    ///
    /// Issue #1: every bridged agent room arrives as an invitation, and
    /// until this existed there was no way to act on one — the client built
    /// for rooms whose other occupants are agents could not enter a single
    /// one of them.
    ///
    /// Deliberately not guarded on the room's current state: `Room::join`
    /// against an already-joined room is a no-op the homeserver answers
    /// happily, and a double-click on Accept is not an error worth
    /// reporting. What it must not do is silently succeed when the
    /// homeserver refuses — a failed join is returned, so the invitation
    /// stays on screen rather than the row quietly turning into a room the
    /// account is not in.
    /// Creates a room and returns its id.
    ///
    /// `invite` is a list of user ids to invite at creation rather than
    /// afterwards, because that is the only place the DM flag can be set — the
    /// same reason AgentPod's bridge creates its agent rooms that way.
    ///
    /// `is_direct` decides which half of a client's list this lands in. A room
    /// with one other person in it is a DM, and a client that files it as a
    /// group room is one where thirty agent conversations bury the three group
    /// rooms that matter.
    pub async fn create_room(
        &self,
        name: &str,
        invite: &[String],
        is_direct: bool,
    ) -> CoreResult<String> {
        let client = self.require_client().await?;

        let mut request = CreateRoomRequest::new();
        let trimmed = name.trim();
        if !trimmed.is_empty() {
            request.name = Some(trimmed.to_string());
        }
        request.is_direct = is_direct;
        request.preset = Some(if is_direct {
            RoomPreset::TrustedPrivateChat
        } else {
            RoomPreset::PrivateChat
        });
        request.invite = invite
            .iter()
            .filter_map(|id| UserId::parse(id).ok())
            .collect();

        let room = client
            .create_room(request)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;

        Ok(room.room_id().to_string())
    }

    /// Joins a room by id **or alias** — `#agentpod_missions:id.agentpod.dev`
    /// is the shape an operator is actually given.
    ///
    /// Separate from [`Self::join_room`], which accepts an invitation to a room
    /// the client already knows about: this one reaches a room it has never
    /// seen, which is a different request to the homeserver and a different
    /// failure when it is refused.
    pub async fn join_room_by_alias(&self, alias_or_id: &str) -> CoreResult<String> {
        let client = self.require_client().await?;
        let target = RoomOrAliasId::parse(alias_or_id.trim())
            .map_err(|e| CoreError::Protocol(e.to_string()))?;

        let room = client
            .join_room_by_id_or_alias(&target, &[])
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))?;

        Ok(room.room_id().to_string())
    }

    /// Invites `user_id` to `room_id`.
    pub async fn invite_user(&self, room_id: &str, user_id: &str) -> CoreResult<()> {
        let client = self.require_client().await?;
        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let parsed_user_id =
            UserId::parse(user_id.trim()).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

        room.invite_user_by_id(&parsed_user_id)
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    pub async fn join_room(&self, room_id: &str) -> CoreResult<()> {
        let client = self.require_client().await?;
        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

        room.join()
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Declines an invitation to `room_id`, or leaves a room already joined.
    ///
    /// One call for both, because Matrix has one: declining an invitation
    /// *is* `POST /leave`. The webview distinguishes them in its wording
    /// (`Decline` on an invitation) rather than in the protocol.
    ///
    /// The room does not disappear from the roster as a result — the room
    /// list is filtered with `new_filter_non_left`, so the SDK drops it on
    /// the next sync, which is the diff the roster is already built to fold
    /// in.
    pub async fn leave_room(&self, room_id: &str) -> CoreResult<()> {
        let client = self.require_client().await?;
        let parsed_room_id =
            RoomId::parse(room_id).map_err(|e| CoreError::Protocol(e.to_string()))?;
        let room = client
            .get_room(&parsed_room_id)
            .ok_or_else(|| CoreError::Protocol("unknown room".into()))?;

        room.leave()
            .await
            .map_err(|e| CoreError::Protocol(e.to_string()))
    }

    /// Fetches a room member's avatar as a `data:` URI, given the raw
    /// `mxc://` URI already carried on their `RoomInfoDto` member entry (see
    /// [`Self::room_info`]).
    ///
    /// A thin wrapper over [`media::avatar_thumbnail`] — the exact same
    /// authenticated-media fetch [`Self::room_avatar`] already uses for a
    /// room's own avatar, called directly on the mxc URI the room-info panel
    /// already has, rather than a second fetch path. Unlike
    /// [`Self::room_avatar`], there is no resolution step to do first: a
    /// member's avatar mxc URI (when they have one) is already the right
    /// answer, with no hero/two-person fallback chain to walk — that
    /// chain exists only because a *room's* avatar can be implicit, not a
    /// member's.
    ///
    /// **Not room-scoped or focus-checked**, deliberately: like
    /// [`Self::room_avatar`]/[`Self::media_fetch`], this fetches a resource
    /// named explicitly by the caller (an mxc URI, not "whichever room is
    /// current"), so there is no ambient "ran against the wrong room"
    /// failure mode for a room switch to trigger — unlike [`Self::room_info`]
    /// above, which shows an entire room's identity under a header and so
    /// does take that guard.
    pub async fn member_avatar(&self, mxc_uri: &str) -> CoreResult<Option<String>> {
        let client = self.require_client().await?;
        media::avatar_thumbnail(&client, mxc_uri).await
    }

    /// Builds a `Client` against `homeserver`, backed by the encrypted store
    /// at [`Self::store_path`]. Used identically by [`Self::login`] and
    /// [`Self::restore`] so the two can never diverge on where the store
    /// lives.
    ///
    /// "Encrypted" here means what matrix-sdk-sqlite 0.18 actually does: a
    /// plain SQLite file whose keys and values are individually
    /// AEAD-encrypted with `matrix_sdk_store_encryption::StoreCipher`,
    /// derived from the passphrase below. It is **not** SQLCipher — the file
    /// itself is an ordinary readable SQLite database; what it contains is
    /// ciphertext. The passphrase is held in the OS keyring, so the security
    /// property (state, message history and crypto keys are encrypted at
    /// rest) is the one §2 of the design spec asked for.
    async fn build_client(&self, homeserver: &str) -> CoreResult<Client> {
        // Before any TLS is constructed — see core::tls.
        tls::install_ring_provider();
        let passphrase = load_or_create_passphrase(self.store.as_ref())?;
        Client::builder()
            .homeserver_url(homeserver)
            .sqlite_store(self.store_path(), Some(&passphrase))
            .build()
            .await
            .map_err(|e| CoreError::Network(e.to_string()))
    }

    /// Where the encrypted store lives on disk.
    fn store_path(&self) -> PathBuf {
        self.data_dir.join("store")
    }

    /// Removes the encrypted store directory from disk. Tolerant of it not
    /// existing, so logging out when nothing was ever written stays a safe
    /// no-op.
    fn remove_store(&self) -> CoreResult<()> {
        match std::fs::remove_dir_all(self.store_path()) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }
}

/// Returns the passphrase for the SDK's encrypted stores, generating and
/// persisting one on first use.
///
/// Must return the *same* passphrase across calls: generating a fresh one on
/// each launch would orphan the existing encrypted store and silently lose
/// all local state.
fn load_or_create_passphrase(store: &dyn SecretStore) -> CoreResult<String> {
    if let Some(existing) = store.get(KEY_STORE_PASSPHRASE)? {
        return Ok(existing);
    }

    let passphrase = generate_passphrase();
    store.set(KEY_STORE_PASSPHRASE, &passphrase)?;
    Ok(passphrase)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::secrets::MemoryStore;

    #[tokio::test]
    async fn require_client_reports_not_ready_before_login() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test"),
            Box::new(MemoryStore::default()),
        );
        let err = session.require_client().await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn the_store_passphrase_is_generated_once_and_reused() {
        let store = MemoryStore::default();
        let first = super::load_or_create_passphrase(&store).unwrap();
        let second = super::load_or_create_passphrase(&store).unwrap();
        assert_eq!(
            first, second,
            "a new passphrase would orphan the existing encrypted store"
        );
    }

    #[tokio::test]
    async fn restore_reports_false_when_nothing_was_ever_logged_in() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test-never-logged-in"),
            Box::new(MemoryStore::default()),
        );
        assert!(!session.restore().await.unwrap());
    }

    #[tokio::test]
    async fn logout_without_a_prior_login_is_a_safe_no_op() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test-logout-noop"),
            Box::new(MemoryStore::default()),
        );
        session.logout().await.unwrap();
        assert!(session.client().await.is_none());
    }

    #[tokio::test]
    async fn login_then_restore_reuse_the_same_encrypted_store() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        tls::install_ring_provider();
        let server = MockServer::start().await;

        // The client negotiates the API version before it can pick a login
        // path (r0 vs v3); without this mock the request never gets sent.
        Mock::given(method("GET"))
            .and(path("/_matrix/client/versions"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "versions": ["r0.6.0"],
            })))
            .mount(&server)
            .await;

        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "abc123",
                "device_id": "GHTYAJCE",
                "user_id": "@alice:localhost",
            })))
            .mount(&server)
            .await;

        let data_dir =
            std::env::temp_dir().join(format!("sm-session-test-{}", rand::random::<u64>()));
        let session = Session::new(data_dir.clone(), Box::new(MemoryStore::default()));

        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap();

        // The predicate `restore_and_start`'s idempotence guard reads. A
        // login leaves the session active, so the redundant
        // `restore_session` the webview fires on `/`'s mount right after
        // `/login` navigates there short-circuits instead of building a
        // second `Client` and a second set of streams — see
        // `Session::restore_and_start`.
        assert!(
            session.is_active().await,
            "a completed login must leave the session active"
        );

        // `login` installs the client but starts no streams, which is
        // exactly the shape a *failed* `start_streams` would leave behind.
        // The idempotence guard must not read that as a healthy session:
        // if it did, the redundant `restore_session` would short-circuit and
        // the user would sit in a logged-in UI with dead sync and an empty
        // room list, recoverable only by signing out.
        assert!(
            !session.is_running().await,
            "a client with no streams behind it must not count as running"
        );

        // A genuine app-relaunch would build a brand new `Session` reading
        // the same persisted secrets; `restore` always builds a brand new
        // `Client` regardless of instance, so calling it here on the same
        // `Session` exercises exactly the same code path.
        let restored = session.restore().await.unwrap();
        assert!(
            restored,
            "restore must succeed using the homeserver persisted at login"
        );
        assert_eq!(
            session
                .client()
                .await
                .unwrap()
                .user_id()
                .map(|id| id.to_string()),
            Some("@alice:localhost".to_string()),
        );

        // The load-bearing assertion: exactly one store directory exists.
        // If `login` and `restore` ever computed different store paths this
        // would find two, catching the regression the shared `build_client`
        // helper is meant to prevent.
        let entries: Vec<_> = std::fs::read_dir(&data_dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(
            entries,
            vec![std::ffi::OsString::from("store")],
            "login and restore must build the encrypted store at the same path"
        );

        // `logout`'s server-side call 404s against this mock (no endpoint
        // registered for it), which must not stop local state from clearing.
        session.logout().await.unwrap();
        assert!(
            session.client().await.is_none(),
            "logout must drop the active client even when the server call fails"
        );
        assert!(
            !session.is_active().await,
            "logout must leave the session inactive, so the next restore actually restores"
        );

        let _ = std::fs::remove_dir_all(&data_dir);
    }

    #[tokio::test]
    async fn login_after_logout_succeeds_at_the_same_data_dir() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        tls::install_ring_provider();
        let server = MockServer::start().await;

        Mock::given(method("GET"))
            .and(path("/_matrix/client/versions"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "versions": ["r0.6.0"],
            })))
            .mount(&server)
            .await;

        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "abc123",
                "device_id": "GHTYAJCE",
                "user_id": "@alice:localhost",
            })))
            .mount(&server)
            .await;

        let data_dir =
            std::env::temp_dir().join(format!("sm-session-relogin-test-{}", rand::random::<u64>()));
        let session = Session::new(data_dir.clone(), Box::new(MemoryStore::default()));

        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap();

        session.logout().await.unwrap();

        // `PasswordAuth::logout` deletes `KEY_STORE_PASSPHRASE`, so a second
        // `login` generates a *fresh* passphrase. If `logout` left the old
        // encrypted store directory in place, opening it with the new
        // passphrase fails with a `StoreCipher` `aead::Error` — `logout` must
        // wipe the store directory too, so the second login starts clean.
        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap_or_else(|e| panic!("second login after logout must succeed, got: {e:?}"));

        let _ = std::fs::remove_dir_all(&data_dir);
    }
}
