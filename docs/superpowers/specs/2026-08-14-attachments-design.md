# Sending files and images — design

**Status:** Decided (14 Aug 2026). Binding for the first cut.
**Why now:** `docs/parity-gap-analysis.md` ranks this second of three. The
client can send a message, a reply, a reaction, a typing notice and a read
receipt — and nothing else. There is no upload command, picker, drag-drop or
paste anywhere in the codebase.

---

## 1. Scope of the first cut

**In:** a native file picker, drag-and-drop onto the window, a confirm step,
correct `info` metadata on the sent event (dimensions for images), and the
homeserver's own size limit enforced before anything is read.

**Out, deliberately:** paste from clipboard (a genuinely different path — the
clipboard hands the webview bytes, not a path), generated thumbnails, captions,
multiple files in one send, and upload progress beyond the local echo.

---

## 2. The confirm step is required, not a nicety

Pick-then-send-immediately is simpler and it is what several clients do. It is
wrong here for one specific reason: **this client cannot delete a message.**
There is no redaction command, so a file sent by a mis-click is permanent and
visible to everyone in the room, with no recourse inside the app.

So the flow is **pick → review → send**. The review step shows what is about to
be sent and offers a way out. When redaction exists, this decision is worth
revisiting; until then it is the only thing standing between a stray
double-click and an unrecallable send.

---

## 3. Paths never cross IPC

The webview is never told where a file lives on disk, and is never granted a
filesystem capability.

- **The picker opens from Rust**, not from JavaScript. `tauri-plugin-dialog`
  has both a JS and a Rust API; using the Rust one means no dialog capability
  is exposed to the webview at all.
- **Drag-and-drop is handled by a Rust window handler**, which stages the
  dropped file and emits an event carrying its metadata. Tauri would otherwise
  deliver the dropped paths straight to the webview.
- **The core stages a file behind an opaque token** and hands the frontend only
  `{ token, filename, sizeBytes, mime, width?, height? }`. The token is what
  comes back on send.

This is not defence against the webview — it is our own code. It is defence
against the *next* thing that runs in it: a `{@html}` regression, a dependency
with a script, anything that gets to call `invoke`. A token that indexes a
server-side map is inert in a way a filesystem path is not, and this codebase
has already shipped one HTML-injection scare.

### What this does not achieve — corrected after implementation

The heading above overstates it, and the honest version matters more than the
tidy one. **Tauri's own `tauri://drag-drop` event still reaches the webview
carrying raw paths, and cannot be suppressed** while keeping the Rust handler:
`disable_drag_drop_handler()` turns off both at once. So a dropped file's path
*is* available in the webview whether we want it or not.

What actually holds is narrower, and still worth having:

- **Our IPC surface carries no paths.** Every command and event we define
  passes tokens. Nothing we expose can be asked to read an arbitrary file.
- **The frontend listens for `sm://attachment/staged` and never for
  `tauri://drag-drop`.** That is a discipline, enforced by review rather than
  by the platform — so it belongs in a comment at the listener, where someone
  adding a second drop handler will read it.
- **No filesystem capability is granted** to the webview, so knowing a path
  does not confer the ability to read it.

The residual is that a compromised webview learns the paths of files the user
drops. That is a real reduction from the guarantee this section originally
claimed, and it is a platform limitation rather than a design choice.

### Token rules

- Minted per staged file; opaque, unguessable, no path information encoded.
- **Bound to a room at staging time.** Sending resolves the token against the
  room it was staged for, and refuses on mismatch — the same `RoomChanged`
  guard every other room-scoped command already takes, and for the same reason:
  the reader can switch rooms between staging and sending.
- **Single use.** Consumed on send, so a replay cannot re-send a file.
- Discarded explicitly on cancel, on room switch, and on a bounded timeout, so
  a staged-and-forgotten file does not pin a path forever.

---

## 4. Size is checked before the file is read

`Room::send_attachment` takes `Vec<u8>` — the entire file goes into memory. A
naive implementation reads first and discovers the problem afterwards, which
turns a mis-picked disk image into an out-of-memory crash.

Order, at staging time:

1. `stat` the file. If it exceeds the limit, refuse with a typed error naming
   the actual and permitted sizes. Nothing is read.
2. Compare against the homeserver's `m.upload.size`, which the SDK already
   tracks (`Client::server_max_upload_size`). Prefer the server's number; fall
   back to a conservative local cap when the server does not advertise one.
3. Only then read the bytes, at send time rather than at staging time, so a
   staged file occupies a path and not a buffer.

A file that is refused must say so in terms the reader can act on. "Upload
failed" is not that.

---

## 5. Metadata

`AttachmentConfig.info` carries the type-specific metadata other clients use to
lay out a message before the media arrives.

- **Mime type** is detected from content where cheap, not from the extension
  alone — an extension is a claim, not a fact.
- **Images** carry width and height, read from the file header. Use a small
  header-reading crate rather than a full decoder: we need two integers, not a
  decoded bitmap, and a full decode of an arbitrary file is a much larger
  attack surface for no benefit here.
- **Everything else** carries size and mime.
- The msgtype follows the mime family: `m.image`, `m.audio`, `m.video`,
  `m.file`. This must agree with `core::timeline`'s existing classification, or
  a file will render one way when we send it and another way when it comes
  back.

**No thumbnails in this cut.** Recipients download the full image to preview
it. Revisit with the `image` crate when the cost is worth it.

---

## 6. Send through the send queue

Use the same send-queue path text sending already uses, not the direct room
API. That gives a local echo in the timeline immediately, retry across a
reconnect, and ordering with respect to other sends — all of which the
timeline's existing `sendState` handling already renders (`notSentYet`,
`sendingFailed`).

Nothing appends to `timelineStore.items` directly. The echo arrives through the
diff stream like every other event, which is a rule this codebase has broken
once and paid for.

---

## 7. Interface

Three commands and one event:

| Command | Purpose |
|---|---|
| `attachment_stage(roomId)` | Opens the native picker. Returns staged metadata, or `null` if the reader cancelled. |
| `attachment_send(roomId, token)` | Reads, uploads and sends. Consumes the token. |
| `attachment_discard(token)` | Cancels a staged file. |

| Event | Purpose |
|---|---|
| `sm://attachment/staged` | A file was dropped on the window and staged. Same payload shape as `attachment_stage` returns. |

Cancelling the picker is **not** an error. It is the most common outcome of
opening one, and must return a normal empty result rather than something the
frontend has to catch.

---

## 8. Interface, frontend

- An attach control in the composer, left of the input. A glyph, not an icon —
  the design ships no icon set (spec §11) and one control is not a reason to
  start one. It needs a real accessible name; a glyph alone is not a label.
- **A staged-attachment strip above the composer**, reusing the reply strip's
  shape: filename, human-readable size, and a way to remove it. It is the
  review step §2 requires, so it must be unmissable rather than subtle.
- Send is disabled while a file is staged but not yet sent, or repurposed to
  send it — decide during implementation and say which, but the two must never
  be ambiguous at the same moment.
- Drag-and-drop needs a visible drop state on the window, or the reader cannot
  tell the app accepts drops at all. Reuse `--color-accent`; this is not a
  decision, so it does not get the signal colour.
- Everything stays on design tokens and the existing type ranks.

---

## 9. What must not regress

- The composer's per-room draft handling, and the typing-notice lifecycle.
- The reading column alignment: the staged strip lines up with the timeline
  like every other composer strip.
- `--color-signal` stays reserved for a pending decision.
- No new `{@html}`. A filename is sender-controlled once it is echoed back from
  the homeserver, and is bounded and escaped like every other such string.
