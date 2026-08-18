//! Staging and sending files and images.
//!
//! Binding design: `docs/superpowers/specs/2026-08-14-attachments-design.md`.
//! The three rules that shape every line of this module:
//!
//! **§3 — a filesystem path never crosses IPC.** The webview is told
//! `{ token, filename, sizeBytes, mime, width?, height? }` and nothing else.
//! The path lives here, in [`StagedAttachments`], keyed by an opaque token.
//! That is why the picker is opened from Rust (`tauri-plugin-dialog`'s Rust
//! API, so `capabilities/default.json` needs no `dialog:*` permission at
//! all) and why drops are handled by [`on_files_dropped`] rather than by the
//! webview listening for Tauri's own `tauri://drag-drop`. This is not
//! defence against our own webview code; it is defence against the *next*
//! thing that runs in it. A token that indexes a map in this process is
//! inert in a way a path is not.
//!
//! **§4 — size is checked before the file is read.** `Room::send_attachment`
//! takes a `Vec<u8>`, so the whole file lands in RAM. A read-then-check
//! implementation turns a mis-picked disk image into an out-of-memory crash
//! rather than an error message. So: `stat`, compare against the
//! homeserver's `m.upload.size`, and only then — at *send* time, not at
//! staging time — read the bytes. A staged file occupies a path, never a
//! buffer.
//!
//! **§6 — sending goes through the send queue**, the same path
//! `FocusedTimeline::send_text` uses, so an attachment gets a local echo,
//! retry across a reconnect, and ordering against other sends, all of which
//! the timeline's existing `sendState` rendering already handles. Nothing
//! here appends to the timeline itself; the echo arrives through the same
//! diff stream every other event does.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::engine::general_purpose::URL_SAFE_NO_PAD as BASE64_URL;
use base64::Engine as _;
use matrix_sdk::attachment::{
    AttachmentInfo, BaseAudioInfo, BaseFileInfo, BaseImageInfo, BaseVideoInfo,
};
use matrix_sdk::ruma::UInt;
use matrix_sdk::Client;
use matrix_sdk_ui::timeline::{AttachmentConfig, AttachmentSource};
use mime::Mime;
use rand::rngs::OsRng;
use rand::TryRngCore as _;
use serde::Serialize;

use super::error::{CoreError, CoreResult};
use super::event::FilePicker;
use super::session::Session;
use super::timeline::FocusedTimeline;

/// Emitted when a file dropped on the window has been staged. Carries
/// exactly what [`stage_from_picker`] returns, so the webview has one
/// staged-attachment shape to handle rather than two.
///
/// A drop cannot return a value to a caller — nobody invoked anything — so
/// this is the only way the metadata reaches the composer. See
/// [`on_files_dropped`].
pub const STAGED_ATTACHMENT_EVENT: &str = "sm://attachment/staged";

/// How long a staged file stays staged before it is discarded (§3: "a bounded
/// timeout, so a staged-and-forgotten file does not pin a path forever").
///
/// Ten minutes is well past any plausible "pick a file, read the strip,
/// press send" interaction and well short of "this app has been open since
/// Tuesday". Expiry is what makes the review step §2 requires safe to leave
/// pending: an attachment the reader walked away from stops being sendable
/// rather than sitting there waiting for a stray click.
pub const STAGED_TTL: Duration = Duration::from_secs(10 * 60);

/// The size cap used when the homeserver does not advertise an
/// `m.upload.size` (§4: "fall back to a conservative local cap when the
/// server does not advertise one").
///
/// Deliberately conservative rather than generous. Being wrong in the low
/// direction costs a refusal the reader can act on ("this file is 80 MiB,
/// the limit is 50 MiB"); being wrong in the high direction means reading
/// hundreds of megabytes into a `Vec<u8>` to have the homeserver reject the
/// upload afterwards — the exact failure §4 exists to prevent. 50 MiB is
/// Synapse's own default `max_upload_size`, so on the overwhelmingly likely
/// server this is not even a guess.
pub const LOCAL_MAX_UPLOAD_BYTES: u64 = 50 * 1024 * 1024;

/// How much of a file [`probe_content`] reads to identify it.
///
/// `infer` has a `get_from_path` that would do the open for us; this module
/// does the read itself, bounded by this constant, so that "staging never
/// reads the file" is a property of *this* code rather than of a dependency's
/// current implementation. 8 KiB is far more than any signature `infer`
/// knows needs, and is a fixed cost whether the file is 4 KiB or 4 GiB.
const CONTENT_PROBE_BYTES: u64 = 8 * 1024;

/// What the webview is given for a staged file: enough to render the review
/// strip §2 requires, and nothing that identifies a location on disk.
///
/// `width`/`height` are **omitted**, not null, when absent — they exist for
/// `m.image` and nothing else (§5), so `width?: number` is the honest
/// TypeScript for them.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StagedAttachment {
    /// Opaque, unguessable, and carrying no path information — see
    /// [`mint_token`]. This is what comes back on send.
    pub token: String,
    /// The file's own name, for the review strip. Local, not
    /// sender-controlled — but it is echoed back from the homeserver once
    /// sent, at which point it is (spec §9), so the webview bounds and
    /// escapes it like every other such string.
    pub filename: String,
    pub size_bytes: u64,
    /// Detected from the file's *content* where possible (§5: "an extension
    /// is a claim, not a fact"), falling back to `application/octet-stream`.
    pub mime: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u64>,
}

/// A staged file: the metadata the webview holds a token for, plus the two
/// things it must never be told — where the file is, and which room it was
/// staged against.
#[derive(Debug, Clone)]
struct StagedFile {
    path: PathBuf,
    room_id: String,
    meta: StagedAttachment,
    staged_at: Instant,
}

/// The token -> staged file map. Registered as Tauri managed state, and
/// owned by [`Session`] so logout can clear it (see
/// [`Session::staged_attachments`]).
///
/// A `std::sync::Mutex`, not a `tokio` one, for the same reason
/// `FocusedTimeline` uses one: every operation here is a handful of hash
/// lookups with no await inside, so a guard is never held across a suspend
/// point and an async mutex would only buy scheduling overhead.
#[derive(Debug, Default)]
pub struct StagedAttachments {
    entries: Mutex<HashMap<String, StagedFile>>,
}

impl StagedAttachments {
    /// Stages `entry`, replacing whatever was already staged for the same
    /// room, and sweeps anything past [`STAGED_TTL`] on the way through.
    ///
    /// **Replace, not refuse** — one of the three judgement calls this task
    /// left open. A reader who opens the picker while a file is already
    /// staged has, by the only reading that makes sense, changed their mind
    /// about which file to send: the composer shows one strip, so there is
    /// nowhere for a second staged file to appear even if we kept it.
    /// Refusing would mean inventing an error for an action whose meaning is
    /// unambiguous, and would leave the reader having to find and press
    /// "remove" before they could pick again. The replaced token is dropped
    /// here rather than leaked, so a stale token the webview still holds
    /// fails closed with [`CoreError::UnknownAttachment`].
    ///
    /// The sweep is how the timeout is enforced: **no timer, no task, no
    /// thread per staged file** — the other judgement call. Expiry is
    /// checked lazily, on every operation that touches the map, against
    /// [`StagedFile::staged_at`]. A background task per staged file would
    /// mean an abort handle to store, a cancellation to get right on
    /// discard/send/logout, and a live `tokio` task holding a path across a
    /// logout — all to enforce a deadline whose only observable effect is
    /// that a *later* operation refuses. Since nothing can use an expired
    /// entry without going through one of these methods, checking at the
    /// point of use is not an approximation of the timeout; it is the whole
    /// of it. The map holds at most one entry per room and in practice
    /// exactly one, so the sweep is free.
    fn insert_at(&self, entry: StagedFile, now: Instant) {
        let mut entries = self.lock();
        entries.retain(|_, e| !is_expired(e, now) && e.room_id != entry.room_id);
        entries.insert(entry.meta.token.clone(), entry);
    }

    /// Consumes `token` for a send into `room_id`, returning the path and
    /// metadata.
    ///
    /// Enforces the two token rules §3 states, both of which fail *without*
    /// consuming anything they should not:
    ///
    /// - **Single use.** The entry is removed before this returns, so a
    ///   replayed `attachment_send` with the same token finds nothing and
    ///   gets [`CoreError::UnknownAttachment`]. Removal happens here, before
    ///   the caller reads a single byte, deliberately: the alternative —
    ///   consume on success — leaves a window during the upload in which a
    ///   second call can send the same file again. Losing a staged file to a
    ///   failed upload costs one re-pick; a duplicate send of a file cannot
    ///   be taken back, because this client has no redaction (§2).
    /// - **Bound to the room it was staged for.** A token minted while room
    ///   A was focused refuses a send into room B with the same
    ///   [`CoreError::RoomChanged`] every other room-scoped command uses,
    ///   and leaves the entry in place so the reader can switch back. The
    ///   caller has already taken `FocusedTimeline`'s own focus guard by the
    ///   time it gets here; this is the second, independent check that the
    ///   *token* means this room — the two come apart precisely when the
    ///   webview replays an old token after a room switch, which is the case
    ///   worth refusing.
    fn take_for_send_at(
        &self,
        token: &str,
        room_id: &str,
        now: Instant,
    ) -> CoreResult<(PathBuf, StagedAttachment)> {
        let mut entries = self.lock();
        entries.retain(|_, e| !is_expired(e, now));

        let entry = entries.get(token).ok_or(CoreError::UnknownAttachment)?;
        if entry.room_id != room_id {
            return Err(CoreError::RoomChanged {
                requested: room_id.to_string(),
                focused: entry.room_id.clone(),
            });
        }

        let entry = entries
            .remove(token)
            .expect("entry was present under the same lock a moment ago");
        Ok((entry.path, entry.meta))
    }

    /// Discards `token`. Silent about a token that is already gone —
    /// discarding twice, or discarding one the timeout already swept, is the
    /// same outcome the caller wanted and not something to make them handle.
    pub fn discard(&self, token: &str) {
        self.lock().remove(token);
    }

    /// Drops every staged file except those staged for `room_id` — the
    /// "discarded on room switch" half of §3.
    ///
    /// Keeping the new room's own entries rather than clearing outright
    /// matters only in the ordering `subscribe_timeline` actually produces
    /// (nothing is staged for a room the reader has just switched *to*), but
    /// it states the real rule: an entry for a room that is not focused can
    /// never be sent anyway, because [`take_for_send_at`] and
    /// `FocusedTimeline` would both refuse it. This makes it stop pinning a
    /// path as well.
    ///
    /// [`take_for_send_at`]: StagedAttachments::take_for_send_at
    pub fn retain_room(&self, room_id: &str) {
        self.lock().retain(|_, e| e.room_id == room_id);
    }

    /// Drops everything.
    ///
    /// Called from `Session::logout` — the third judgement call. A staged
    /// token outliving the session would be a path held by a process that no
    /// longer has an account, redeemable by whoever logs in next against a
    /// room they never picked it for. There is no sensible "restore my
    /// pending attachment" behaviour to preserve here and a very obvious
    /// wrong one to avoid, so logout clears the map unconditionally.
    pub fn clear(&self) {
        self.lock().clear();
    }

    /// How many files are staged. Test-facing; nothing in the command path
    /// needs it.
    #[cfg(test)]
    fn len(&self) -> usize {
        self.lock().len()
    }

    /// Recovers from a poisoned lock rather than propagating it. Every
    /// critical section here is a few hash operations that cannot panic
    /// part-way through a mutation, so the map is never observably
    /// half-updated; refusing to stage files for the rest of the process's
    /// life because some unrelated thread panicked would be the worse
    /// failure.
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, StagedFile>> {
        self.entries
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

fn is_expired(entry: &StagedFile, now: Instant) -> bool {
    now.duration_since(entry.staged_at) >= STAGED_TTL
}

/// Mints a staging token: 32 bytes of OS randomness, base64url-encoded.
///
/// §3 requires "opaque, unguessable, no path information encoded", and each
/// clause is load-bearing. Something derived from the path (a hash, an
/// obfuscation) would leak the path's *identity* — equal paths giving equal
/// tokens is enough to test a guess. Something sequential would be guessable
/// outright, which matters because a guessed token is a send of a file the
/// reader never confirmed. 32 bytes from [`OsRng`] is the same construction
/// `core::secrets::generate_passphrase` already uses for the store
/// passphrase, for the same reason: this is the only property the token has.
fn mint_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng
        .try_fill_bytes(&mut bytes)
        .expect("OS randomness must be available");
    BASE64_URL.encode(bytes)
}

/// Refuses `size_bytes` against `limit_bytes`, naming both.
///
/// Pure over two integers on purpose, like `core::timeline::verify_room_focus`:
/// the actual rule §4 states is a comparison, and a comparison should be
/// testable without a homeserver, a picker or a file on disk.
///
/// The error names the real numbers because "upload failed" is not something
/// a reader can act on (§4). Knowing the file is 214.7 MiB against a 50.0 MiB
/// limit tells them to send a link instead; knowing it "failed" tells them to
/// try again, which will fail again.
fn check_upload_size(size_bytes: u64, limit_bytes: u64) -> CoreResult<()> {
    if size_bytes > limit_bytes {
        return Err(CoreError::AttachmentTooLarge {
            bytes: size_bytes,
            limit: limit_bytes,
        });
    }
    Ok(())
}

/// The homeserver's `m.upload.size`, or [`LOCAL_MAX_UPLOAD_BYTES`] when it
/// does not advertise one (or the fetch fails).
///
/// The SDK caches this after the first call (`Client`'s
/// `server_max_upload_size` `OnceCell`, filled by
/// `load_or_fetch_max_upload_size` from `/_matrix/client/v1/media/config`),
/// so staging a second file costs no round trip.
///
/// A fetch failure falls back rather than failing the stage: an unreachable
/// media-config endpoint says nothing about whether *this* file is
/// acceptable, and the conservative cap keeps the §4 guarantee (nothing
/// enormous is ever read into memory) without turning a transient network
/// blip into "you cannot attach files".
async fn upload_limit(client: &Client) -> u64 {
    match client.load_or_fetch_max_upload_size().await {
        Ok(size) => size.into(),
        Err(err) => {
            tracing::warn!(
                error = %err,
                fallback = LOCAL_MAX_UPLOAD_BYTES,
                "homeserver advertised no usable m.upload.size; using the local cap"
            );
            LOCAL_MAX_UPLOAD_BYTES
        }
    }
}

/// What [`probe_content`] can learn about a file without decoding it.
#[derive(Debug, Clone, PartialEq, Eq)]
struct ContentProbe {
    mime: Mime,
    dimensions: Option<(u64, u64)>,
}

/// Identifies `path` from its first bytes: mime from content, and for images,
/// width and height from the header.
///
/// Blocking I/O — callers run it on a blocking thread. Reads at most
/// [`CONTENT_PROBE_BYTES`] for the mime sniff, and `imagesize` seeks within
/// the header rather than streaming the file, so a 4 GiB video costs the same
/// here as a 4 KiB icon. That is the whole point: §4's ordering only holds if
/// staging never reads the file.
///
/// Mime falls back to `application/octet-stream` rather than to the
/// extension. §5 calls an extension "a claim, not a fact", and the honest
/// encoding of "we could not tell" is the type that means exactly that —
/// which also maps to `m.file`, the right msgtype for an unidentified blob.
/// The cost is that a plain `.txt` sends as `application/octet-stream`;
/// that renders identically (an `m.file` row) and is preferable to trusting a
/// suffix a stranger chose.
///
/// Dimensions are `None` for anything that is not an image, and also for an
/// image whose header `imagesize` cannot parse (a truncated file, or a format
/// left out of the crate's feature list — see `Cargo.toml`). Both degrade to
/// an `m.image` with no `width`/`height` in its `info`, which is a
/// layout hint recipients can live without, never a failed send.
fn probe_content(path: &Path) -> ContentProbe {
    let mime = read_header(path)
        .and_then(|header| infer::get(&header))
        .and_then(|kind| kind.mime_type().parse::<Mime>().ok())
        .unwrap_or(mime::APPLICATION_OCTET_STREAM);

    let dimensions = if mime.type_() == mime::IMAGE {
        imagesize::size(path)
            .ok()
            .map(|size| (size.width as u64, size.height as u64))
    } else {
        None
    };

    ContentProbe { mime, dimensions }
}

/// The first [`CONTENT_PROBE_BYTES`] of `path`, or `None` if it cannot be
/// opened. `Read::take` is the bound: a file larger than the cap is
/// truncated to it rather than read whole, which is the difference between
/// identifying a 4 GiB video and allocating it.
fn read_header(path: &Path) -> Option<Vec<u8>> {
    use std::io::Read as _;
    let mut header = Vec::new();
    std::fs::File::open(path)
        .ok()?
        .take(CONTENT_PROBE_BYTES)
        .read_to_end(&mut header)
        .ok()?;
    Some(header)
}

/// The Matrix msgtype the SDK will give an attachment of this mime type.
///
/// Not used to *build* anything — `Room::send_attachment`'s own
/// `make_media_type!` switches on `content_type.type_()` and picks the
/// content variant itself. This exists so the choice is written down and
/// testable, because §5 requires it to agree with
/// `core::timeline::classify_content`, which reports whatever msgtype comes
/// back from the homeserver. If the two disagreed, a file would render one
/// way when we sent it and another way when it returned.
///
/// The same three-families-then-everything-else split drives
/// [`attachment_info_for`]; keeping them in one shape is what stops an
/// `m.image` being sent with a `FileInfo` that silently drops its
/// dimensions.
fn msgtype_for_mime(mime: &Mime) -> &'static str {
    match mime.type_() {
        mime::IMAGE => "m.image",
        mime::AUDIO => "m.audio",
        mime::VIDEO => "m.video",
        _ => "m.file",
    }
}

/// Builds the `AttachmentConfig.info` for a file of this mime type (§5).
///
/// The variant **must** match the family `Room::send_attachment` derives
/// from the same mime, because the SDK converts `AttachmentInfo` into the
/// content-specific info type with a `_ => ImageInfo::new()`-style fallback:
/// hand it `AttachmentInfo::File` for an `image/png` and the resulting
/// `ImageInfo` is empty — size, width and height all silently gone. That is
/// why this switches on exactly what [`msgtype_for_mime`] switches on.
///
/// Only images carry dimensions. Video's `BaseVideoInfo` has the same two
/// fields, but reading them needs a container parser rather than a header
/// read, which is out of scope for this cut (§5) — and the timeline already
/// declines to render dimensions for `m.video` (`core::timeline::media_meta`).
fn attachment_info_for(
    mime: &Mime,
    size_bytes: u64,
    dimensions: Option<(u64, u64)>,
) -> AttachmentInfo {
    let size = UInt::new(size_bytes);
    match mime.type_() {
        mime::IMAGE => AttachmentInfo::Image(BaseImageInfo {
            width: dimensions.and_then(|(w, _)| UInt::new(w)),
            height: dimensions.and_then(|(_, h)| UInt::new(h)),
            size,
            ..Default::default()
        }),
        mime::AUDIO => AttachmentInfo::Audio(BaseAudioInfo {
            size,
            ..Default::default()
        }),
        mime::VIDEO => AttachmentInfo::Video(BaseVideoInfo {
            size,
            ..Default::default()
        }),
        _ => AttachmentInfo::File(BaseFileInfo { size }),
    }
}

/// Renders a byte count the way the refusal message needs it: binary units,
/// one decimal place. Homeserver limits are powers of two (Synapse's default
/// is 52428800), so decimal units would make a file of exactly the limit
/// print as over it.
pub(crate) fn format_bytes(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit + 1 < UNITS.len() {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

/// Opens the native picker for `room_id` and stages whatever comes back.
/// `Ok(None)` when the reader cancelled.
///
/// **Cancelling is not an error** (§7). It is the most common outcome of
/// opening a picker, and a frontend that has to `try`/`catch` around the
/// normal case will eventually catch a real failure with it.
///
/// The room is checked *before* the picker opens, not only when the file
/// comes back. Both matter: opening a file chooser for a room the reader has
/// already left is wasted and confusing, and a room switch during the (long,
/// human-paced) time the dialog is up must not produce a token bound to the
/// old room. The second check is [`StagedAttachments::insert_at`]'s room
/// binding plus the guard `send_staged` takes later; this first one just
/// avoids the pointless dialog.
///
/// The picker is opened through the plugin's **callback** API bridged to a
/// oneshot channel, not `blocking_pick_file`. The blocking variant parks the
/// calling thread until the reader chooses — that is a tokio worker thread
/// held for however long someone spends browsing their home directory, and on
/// the main thread it deadlocks the event loop outright. Awaiting a oneshot
/// costs nothing while the dialog is up.
pub async fn stage_from_picker(
    picker: &std::sync::Arc<dyn FilePicker>,
    session: &Session,
    focused: &FocusedTimeline,
    staged: &StagedAttachments,
    room_id: &str,
) -> CoreResult<Option<StagedAttachment>> {
    focused.verify_focus(room_id)?;
    let client = session.require_client().await?;

    // Which file is the host's question to ask — a Tauri dialog on desktop, a
    // document picker on iOS. Cancelling is an ordinary answer.
    let Some(path) = picker.pick_file().await else {
        return Ok(None);
    };

    stage_path(&client, staged, room_id, path).await.map(Some)
}

/// Stats, size-checks and stages `path` for `room_id`, in the order §4
/// requires.
///
/// Nothing after the size check reads more than a header, and nothing here
/// reads the file body at all — that happens in [`send_staged`]. A staged
/// file is a path and a few dozen bytes of metadata.
pub async fn stage_path(
    client: &Client,
    staged: &StagedAttachments,
    room_id: &str,
    path: PathBuf,
) -> CoreResult<StagedAttachment> {
    // 1. `stat`. Before anything opens the file, and before the picker's
    //    answer is trusted to be a file at all — a directory can be dropped
    //    on the window even though the picker will not return one.
    let metadata = tokio::fs::metadata(&path)
        .await
        .map_err(|e| CoreError::Store(format!("cannot read that file: {e}")))?;
    if !metadata.is_file() {
        return Err(CoreError::Store("that is not a file".into()));
    }
    let size_bytes = metadata.len();

    // 2. Compare against the homeserver's limit, before any read.
    check_upload_size(size_bytes, upload_limit(client).await)?;

    let filename = path
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| CoreError::Store("that file has no usable name".into()))?
        .to_string();

    // 3. Header-only probe, on a blocking thread: two small reads and a few
    //    seeks, not a decode and not a whole-file read.
    let probe_path = path.clone();
    let probe = tokio::task::spawn_blocking(move || probe_content(&probe_path))
        .await
        .map_err(|e| CoreError::Store(format!("could not inspect that file: {e}")))?;

    let meta = StagedAttachment {
        token: mint_token(),
        filename,
        size_bytes,
        mime: probe.mime.to_string(),
        width: probe.dimensions.map(|(w, _)| w),
        height: probe.dimensions.map(|(_, h)| h),
    };

    staged.insert_at(
        StagedFile {
            path,
            room_id: room_id.to_string(),
            meta: meta.clone(),
            staged_at: Instant::now(),
        },
        Instant::now(),
    );

    tracing::debug!(
        room_id,
        filename = meta.filename,
        size_bytes,
        mime = meta.mime,
        "staged an attachment"
    );
    Ok(meta)
}

/// Reads, uploads and sends the file `token` stands for, consuming the token.
///
/// Guard order is deliberate:
///
/// 1. `FocusedTimeline::active_timeline_for` — the same room guard
///    `send_text`/`send_reply`/`toggle_reaction` take, resolving "is this
///    still the room the caller means" and handing back the `Timeline` under
///    one lock acquisition so a room switch cannot land between the two.
/// 2. The token's own room binding, inside
///    [`StagedAttachments::take_for_send_at`]. Neither check subsumes the
///    other: the first catches a stale *send*, the second catches a stale
///    *token*.
/// 3. `stat` and size again, then read. Re-checking is not paranoia about
///    our own bookkeeping — the file on disk can grow between staging and
///    sending (a download completing, a log file, a video still rendering),
///    and the check that matters for §4 is the one immediately before the
///    read that puts the bytes in memory.
///
/// The bytes are handed over as `AttachmentSource::Data`, never as a path.
/// Passing the path would let the SDK read the file itself, after our size
/// check and outside it.
pub async fn send_staged(
    session: &Session,
    focused: &FocusedTimeline,
    staged: &StagedAttachments,
    room_id: &str,
    token: &str,
) -> CoreResult<()> {
    let timeline = focused.active_timeline_for(room_id)?;
    let client = session.require_client().await?;

    let (path, meta) = staged.take_for_send(token, room_id)?;

    let metadata = tokio::fs::metadata(&path)
        .await
        .map_err(|e| CoreError::Store(format!("cannot read that file: {e}")))?;
    check_upload_size(metadata.len(), upload_limit(&client).await)?;

    let bytes = tokio::fs::read(&path)
        .await
        .map_err(|e| CoreError::Store(format!("cannot read that file: {e}")))?;

    // Re-parse rather than carry a `Mime` on the staged entry: the string is
    // what crossed IPC and what the recipient will see in `info.mimetype`, so
    // parsing it back is the one thing that guarantees those agree.
    let mime: Mime = meta.mime.parse().unwrap_or(mime::APPLICATION_OCTET_STREAM);
    let info = attachment_info_for(&mime, bytes.len() as u64, meta.dimensions());

    let config = AttachmentConfig {
        info: Some(info),
        ..Default::default()
    };

    tracing::debug!(
        room_id,
        filename = meta.filename,
        msgtype = msgtype_for_mime(&mime),
        "sending an attachment through the send queue"
    );

    // `use_send_queue` is §6: local echo, retry across a reconnect, and
    // ordering with respect to other sends. Without it this is a blocking
    // upload with no timeline item until it finishes — which for a large
    // file means a composer that looks like it did nothing for a minute.
    timeline
        .send_attachment(
            AttachmentSource::Data {
                bytes,
                filename: meta.filename.clone(),
            },
            mime,
            config,
        )
        .use_send_queue()
        .await
        .map_err(|e| CoreError::Protocol(e.to_string()))?;

    Ok(())
}

impl StagedAttachment {
    fn dimensions(&self) -> Option<(u64, u64)> {
        Some((self.width?, self.height?))
    }
}

impl StagedAttachments {
    /// [`Self::take_for_send_at`] against the wall clock.
    fn take_for_send(&self, token: &str, room_id: &str) -> CoreResult<(PathBuf, StagedAttachment)> {
        self.take_for_send_at(token, room_id, Instant::now())
    }
}

/// Stages a file dropped on the window and announces it on
/// [`STAGED_ATTACHMENT_EVENT`].
///
/// This is the Rust-side drag-drop handler §3 requires. It runs from
/// `lib.rs`'s `on_window_event`, resolves the room itself from
/// `FocusedTimeline` (a drop names no room — it lands on whatever the reader
/// is looking at), and emits the same payload [`stage_from_picker`] returns.
///
/// **A caveat worth stating rather than hiding.** Tauri's own drag-drop
/// handling cannot be split: `disable_drag_drop_handler()` turns the OS
/// handler off entirely, so Rust would stop seeing drops too. With it on,
/// Tauri also emits its built-in `tauri://drag-drop` — carrying the raw
/// paths — to the webview, and there is no hook to suppress just that. What
/// this module guarantees is that *the core's own IPC surface* never carries
/// a path: no command returns one, no `sm://` event contains one, and
/// nothing the webview can invoke will read a path it supplies. The frontend
/// must not listen for `tauri://drag-drop`; it listens for
/// [`STAGED_ATTACHMENT_EVENT`], which is the whole reason this handler
/// exists.
///
/// Only the first file is staged. Multiple files in one send are explicitly
/// out of scope (§1) and the composer shows a single strip, so a
/// three-file drop stages one file and logs the rest — visible in the review
/// step §2 requires, rather than three unrecallable sends.
///
/// Failures are logged, not surfaced: there is no invocation to fail, and a
/// dropped directory or an oversized file should not become a dialog the

#[cfg(test)]
mod tests {
    use super::*;

    fn staged_file(token: &str, room_id: &str, at: Instant) -> StagedFile {
        StagedFile {
            path: PathBuf::from("/private/somewhere/secret.png"),
            room_id: room_id.to_string(),
            meta: StagedAttachment {
                token: token.to_string(),
                filename: "secret.png".into(),
                size_bytes: 1234,
                mime: "image/png".into(),
                width: Some(10),
                height: Some(20),
            },
            staged_at: at,
        }
    }

    // ---- token rules (§3) -------------------------------------------------

    #[test]
    fn a_token_is_opaque_and_encodes_no_path() {
        let token = mint_token();
        // 32 bytes, base64url, unpadded.
        assert_eq!(token.len(), 43);
        assert!(!token.contains('/'));
        assert!(!token.contains('\\'));
        assert!(!token.contains('='));
    }

    #[test]
    fn two_tokens_are_never_the_same() {
        assert_ne!(mint_token(), mint_token());
    }

    #[test]
    fn a_token_staged_for_one_room_refuses_a_send_into_another() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        let err = staged
            .take_for_send_at("tok", "!b:x.org", now)
            .expect_err("a token bound to !a must not send into !b");

        match err {
            CoreError::RoomChanged { requested, focused } => {
                assert_eq!(requested, "!b:x.org");
                assert_eq!(focused, "!a:x.org");
            }
            other => panic!("expected CoreError::RoomChanged, got {other:?}"),
        }
    }

    #[test]
    fn a_refused_room_mismatch_leaves_the_token_usable_in_its_own_room() {
        // A mismatch must not consume the token: the reader can switch back
        // and send the file they staged.
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        assert!(staged.take_for_send_at("tok", "!b:x.org", now).is_err());
        assert!(staged.take_for_send_at("tok", "!a:x.org", now).is_ok());
    }

    #[test]
    fn a_token_can_only_be_sent_once() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        let (path, _) = staged
            .take_for_send_at("tok", "!a:x.org", now)
            .expect("the first send resolves the token");
        assert_eq!(path, PathBuf::from("/private/somewhere/secret.png"));

        // The replay: same token, same room, immediately afterwards.
        let err = staged
            .take_for_send_at("tok", "!a:x.org", now)
            .expect_err("a replayed token must not re-send the file");
        assert!(matches!(err, CoreError::UnknownAttachment), "got {err:?}");
        assert_eq!(staged.len(), 0);
    }

    #[test]
    fn an_unknown_token_is_refused_rather_than_resolved() {
        let staged = StagedAttachments::default();
        let err = staged
            .take_for_send_at("never-minted", "!a:x.org", Instant::now())
            .expect_err("an unknown token has no file behind it");
        assert!(matches!(err, CoreError::UnknownAttachment), "got {err:?}");
    }

    #[test]
    fn a_discarded_token_no_longer_resolves() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);
        staged.discard("tok");
        assert!(staged.take_for_send_at("tok", "!a:x.org", now).is_err());
    }

    #[test]
    fn discarding_an_unknown_token_is_not_an_error() {
        // Discarding twice, or discarding one the timeout already swept, is
        // the outcome the caller asked for either way.
        let staged = StagedAttachments::default();
        staged.discard("tok");
        staged.discard("tok");
    }

    #[test]
    fn a_token_expires_after_the_bounded_timeout() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        let later = now + STAGED_TTL + Duration::from_secs(1);
        let err = staged
            .take_for_send_at("tok", "!a:x.org", later)
            .expect_err("a staged-and-forgotten file must stop being sendable");
        assert!(matches!(err, CoreError::UnknownAttachment), "got {err:?}");
        assert_eq!(staged.len(), 0, "the expired entry must not pin its path");
    }

    #[test]
    fn a_token_still_resolves_just_short_of_the_timeout() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        let later = now + STAGED_TTL - Duration::from_secs(1);
        assert!(staged.take_for_send_at("tok", "!a:x.org", later).is_ok());
    }

    #[test]
    fn staging_a_second_file_for_the_same_room_replaces_the_first() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("first", "!a:x.org", now), now);
        staged.insert_at(staged_file("second", "!a:x.org", now), now);

        assert_eq!(staged.len(), 1);
        assert!(staged.take_for_send_at("first", "!a:x.org", now).is_err());
        assert!(staged.take_for_send_at("second", "!a:x.org", now).is_ok());
    }

    #[test]
    fn staging_sweeps_entries_that_have_already_expired() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("old", "!a:x.org", now), now);

        let later = now + STAGED_TTL + Duration::from_secs(1);
        staged.insert_at(staged_file("new", "!b:x.org", later), later);

        assert_eq!(staged.len(), 1, "the expired !a entry must be swept");
        assert!(staged.take_for_send_at("new", "!b:x.org", later).is_ok());
    }

    #[test]
    fn a_room_switch_discards_tokens_staged_for_other_rooms() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("a", "!a:x.org", now), now);
        staged.insert_at(staged_file("b", "!b:x.org", now), now);

        staged.retain_room("!b:x.org");

        assert_eq!(staged.len(), 1);
        assert!(staged.take_for_send_at("a", "!a:x.org", now).is_err());
        assert!(staged.take_for_send_at("b", "!b:x.org", now).is_ok());
    }

    #[test]
    fn logout_clears_every_staged_token() {
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("a", "!a:x.org", now), now);
        staged.insert_at(staged_file("b", "!b:x.org", now), now);

        staged.clear();

        assert_eq!(staged.len(), 0);
        assert!(staged.take_for_send_at("a", "!a:x.org", now).is_err());
    }

    // ---- size (§4) --------------------------------------------------------

    #[test]
    fn a_file_within_the_limit_is_accepted() {
        assert!(check_upload_size(1024, 1024).is_ok());
        assert!(check_upload_size(0, 1024).is_ok());
    }

    #[test]
    fn an_oversized_file_is_refused_naming_both_sizes() {
        let err = check_upload_size(200 * 1024 * 1024, 50 * 1024 * 1024)
            .expect_err("200 MiB against a 50 MiB limit must be refused");

        match err {
            CoreError::AttachmentTooLarge { bytes, limit } => {
                assert_eq!(bytes, 200 * 1024 * 1024);
                assert_eq!(limit, 50 * 1024 * 1024);
            }
            other => panic!("expected CoreError::AttachmentTooLarge, got {other:?}"),
        }
    }

    #[test]
    fn the_refusal_message_names_the_actual_and_permitted_sizes() {
        // "Upload failed" is not something a reader can act on (§4).
        let err = check_upload_size(200 * 1024 * 1024, 50 * 1024 * 1024).unwrap_err();
        let message = err.to_string();
        assert!(message.contains("200.0 MiB"), "got {message}");
        assert!(message.contains("50.0 MiB"), "got {message}");
    }

    #[test]
    fn the_refusal_carries_its_own_error_kind() {
        let err = check_upload_size(2, 1).unwrap_err();
        assert_eq!(err.kind(), "attachmentTooLarge");
    }

    #[test]
    fn byte_counts_render_in_binary_units() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(1024), "1.0 KiB");
        assert_eq!(format_bytes(52_428_800), "50.0 MiB");
        assert_eq!(format_bytes(3 * 1024 * 1024 * 1024), "3.0 GiB");
    }

    // ---- metadata (§5) ----------------------------------------------------

    #[test]
    fn msgtypes_follow_the_mime_family() {
        // These four strings are exactly what `core::timeline::classify_content`
        // reports for the content the SDK builds from the same mime family.
        assert_eq!(msgtype_for_mime(&mime::IMAGE_PNG), "m.image");
        assert_eq!(msgtype_for_mime(&"audio/ogg".parse().unwrap()), "m.audio");
        assert_eq!(msgtype_for_mime(&"video/mp4".parse().unwrap()), "m.video");
        assert_eq!(msgtype_for_mime(&mime::APPLICATION_PDF), "m.file");
        assert_eq!(msgtype_for_mime(&mime::APPLICATION_OCTET_STREAM), "m.file");
        assert_eq!(msgtype_for_mime(&mime::TEXT_PLAIN), "m.file");
    }

    #[test]
    fn an_image_carries_its_dimensions_and_size() {
        let info = attachment_info_for(&mime::IMAGE_PNG, 4096, Some((800, 600)));
        match info {
            AttachmentInfo::Image(image) => {
                assert_eq!(image.width, UInt::new(800));
                assert_eq!(image.height, UInt::new(600));
                assert_eq!(image.size, UInt::new(4096));
            }
            other => panic!("expected AttachmentInfo::Image, got {other:?}"),
        }
    }

    #[test]
    fn a_non_image_carries_size_only() {
        // The variant has to match the mime family: `ImageInfo::from` has a
        // `_ =>` arm that returns an *empty* info, so a mismatched variant
        // would silently drop the size the recipient lays out with.
        match attachment_info_for(&mime::APPLICATION_PDF, 4096, None) {
            AttachmentInfo::File(file) => assert_eq!(file.size, UInt::new(4096)),
            other => panic!("expected AttachmentInfo::File, got {other:?}"),
        }
        match attachment_info_for(&"audio/ogg".parse().unwrap(), 99, None) {
            AttachmentInfo::Audio(audio) => assert_eq!(audio.size, UInt::new(99)),
            other => panic!("expected AttachmentInfo::Audio, got {other:?}"),
        }
        match attachment_info_for(&"video/mp4".parse().unwrap(), 99, None) {
            AttachmentInfo::Video(video) => {
                assert_eq!(video.size, UInt::new(99));
                assert_eq!(video.width, None, "video dimensions are out of scope (§5)");
            }
            other => panic!("expected AttachmentInfo::Video, got {other:?}"),
        }
    }

    /// A 1x1 PNG, written byte for byte so the probe has a real header to
    /// read rather than a fixture file in the repository.
    const ONE_BY_ONE_PNG: &[u8] = &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR length + type
        0x00, 0x00, 0x00, 0x01, // width  = 1
        0x00, 0x00, 0x00, 0x01, // height = 1
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89,
    ];

    /// A 3x2 GIF87a — a second format, so the probe is not just "PNG works".
    const THREE_BY_TWO_GIF: &[u8] = &[
        0x47, 0x49, 0x46, 0x38, 0x37, 0x61, // GIF87a
        0x03, 0x00, // width  = 3 (little endian)
        0x02, 0x00, // height = 2
        0x00, 0x00, 0x00,
    ];

    fn temp_file(name: &str, bytes: &[u8]) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "supermessage-attachment-tests-{}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let path = dir.join(name);
        std::fs::write(&path, bytes).expect("temp file");
        path
    }

    #[test]
    fn a_png_is_identified_from_its_content_and_yields_its_dimensions() {
        let path = temp_file("one.png", ONE_BY_ONE_PNG);
        let probe = probe_content(&path);
        assert_eq!(probe.mime, mime::IMAGE_PNG);
        assert_eq!(probe.dimensions, Some((1, 1)));
    }

    #[test]
    fn a_gif_is_identified_from_its_content_and_yields_its_dimensions() {
        let path = temp_file("three.gif", THREE_BY_TWO_GIF);
        let probe = probe_content(&path);
        assert_eq!(probe.mime, mime::IMAGE_GIF);
        assert_eq!(probe.dimensions, Some((3, 2)));
    }

    #[test]
    fn a_lying_extension_does_not_decide_the_mime_type() {
        // §5: "an extension is a claim, not a fact". A PNG named `.pdf` is a
        // PNG, and — the case that actually matters — a executable named
        // `.png` must not be announced to the room as an image.
        let path = temp_file("actually-a-png.pdf", ONE_BY_ONE_PNG);
        let probe = probe_content(&path);
        assert_eq!(probe.mime, mime::IMAGE_PNG);
        assert_eq!(msgtype_for_mime(&probe.mime), "m.image");
    }

    #[test]
    fn an_unrecognised_file_falls_back_to_octet_stream_with_no_dimensions() {
        let path = temp_file("notes.txt", b"just some text, no magic number here");
        let probe = probe_content(&path);
        assert_eq!(probe.mime, mime::APPLICATION_OCTET_STREAM);
        assert_eq!(probe.dimensions, None);
        assert_eq!(msgtype_for_mime(&probe.mime), "m.file");
    }

    #[test]
    fn a_truncated_image_header_yields_no_dimensions_rather_than_failing() {
        let path = temp_file("truncated.png", &ONE_BY_ONE_PNG[..10]);
        let probe = probe_content(&path);
        assert_eq!(probe.mime, mime::IMAGE_PNG);
        assert_eq!(probe.dimensions, None);
    }

    // ---- the wire shape (§7) ---------------------------------------------

    #[test]
    fn staged_metadata_serializes_camel_case_and_carries_no_path() {
        let meta = StagedAttachment {
            token: "tok".into(),
            filename: "holiday.png".into(),
            size_bytes: 4096,
            mime: "image/png".into(),
            width: Some(800),
            height: Some(600),
        };
        let json = serde_json::to_value(&meta).unwrap();

        assert_eq!(json["token"], "tok");
        assert_eq!(json["filename"], "holiday.png");
        assert_eq!(json["sizeBytes"], 4096);
        assert_eq!(json["mime"], "image/png");
        assert_eq!(json["width"], 800);
        assert_eq!(json["height"], 600);

        // §3: the payload is exactly these six fields, no more. A field
        // added to `StagedAttachment` without thinking about what crosses
        // IPC — a `path`, a `dir`, a `source` — fails here rather than
        // silently shipping a location on disk to the webview.
        let mut fields: Vec<&str> = json
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        fields.sort_unstable();
        assert_eq!(
            fields,
            ["filename", "height", "mime", "sizeBytes", "token", "width"]
        );
    }

    #[test]
    fn the_staged_entry_holds_the_path_that_the_wire_shape_does_not() {
        // The other half of the same rule: the path exists, it is just on
        // the core's side of the boundary. `take_for_send_at` is the only
        // way back to it, and it is not reachable from a command's return
        // value.
        let staged = StagedAttachments::default();
        let now = Instant::now();
        staged.insert_at(staged_file("tok", "!a:x.org", now), now);

        let (path, meta) = staged.take_for_send_at("tok", "!a:x.org", now).unwrap();
        assert_eq!(path, PathBuf::from("/private/somewhere/secret.png"));
        let serialized = serde_json::to_string(&meta).unwrap();
        assert!(
            !serialized.contains("/private/somewhere"),
            "the staged path must not be reachable through the wire shape: {serialized}"
        );
    }

    #[test]
    fn a_non_image_omits_width_and_height_entirely() {
        let meta = StagedAttachment {
            token: "tok".into(),
            filename: "report.pdf".into(),
            size_bytes: 4096,
            mime: "application/pdf".into(),
            width: None,
            height: None,
        };
        let json = serde_json::to_value(&meta).unwrap();
        let object = json.as_object().unwrap();
        assert_eq!(object.len(), 4);
        assert!(!object.contains_key("width"));
        assert!(!object.contains_key("height"));
    }
}
