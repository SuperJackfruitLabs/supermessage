// The composer's staged-attachment logic, kept out of `Composer.svelte` for
// the same reason `draftTracker.ts` is: this project's vitest runs with
// `environment: "node"` and has no component-testing setup, so anything that
// has to be *proved* rather than looked at lives in a plain module and the
// component stays a thin reactive wrapper over it.
//
// Three separable things live here, and they are separate on purpose:
//
// 1. **What the review strip says** — `stagedStripView`, plus the
//    `formatAttachmentSize`/`sanitizeFilename` primitives under it. The strip
//    is the confirm step the attachments design's §2 requires ("this client
//    cannot delete a message", so a file sent by a mis-click is permanent),
//    which makes the text on it load-bearing rather than decorative.
// 2. **Which room a staged file belongs to** — `StagedAttachmentTracker`.
//    Same hazard, same shape and the same reason as `DraftTracker`: the
//    composer is deliberately *not* remounted on a room switch, so nothing
//    resets its local state for it. A staged attachment that leaked across a
//    switch would be strictly worse than the stale-draft bug that tracker
//    exists to fix — it would offer to send a file into a room the reader
//    never picked it for, and this client cannot take that back.
// 3. **How a refusal reads** — `attachmentFailure`. The core distinguishes
//    "this file is 200.0 MiB, the limit is 50.0 MiB" from "that token names
//    nothing" from "you switched rooms", and the whole value of those typed
//    kinds is lost if the webview renders them all as "upload failed".
//
// Nothing here touches the DOM, Svelte, a store or the clock.

import type { CoreError, StagedAttachment } from "$lib/ipc";

/**
 * Binary units, one decimal place, matching
 * `core::attachments::format_bytes` **exactly** — including `"512 B"` with
 * no decimal for sub-kibibyte sizes.
 *
 * The agreement is the point, not a coincidence worth tolerating: an
 * `attachmentTooLarge` refusal names the limit in the core's units ("at most
 * 50.0 MiB"), and it appears on screen directly under a strip that has just
 * named the file's own size. Two formatters would eventually disagree about
 * the same number, and the first place a reader would notice is a message
 * telling them a 50.0 MB file is over a 50.0 MiB limit.
 *
 * Binary rather than decimal for the reason that message gives: homeserver
 * limits are powers of two (Synapse's default `max_upload_size` is
 * 52428800), so in decimal units a file of exactly the limit prints as over
 * it.
 */
const SIZE_UNITS = ["B", "KiB", "MiB", "GiB", "TiB"] as const;

/**
 * Renders `bytes` as a human-readable size.
 *
 * Returns the literal `"unknown size"` for anything that is not a
 * non-negative finite number. The core always sends a real `u64`, so this is
 * defence in depth rather than an expected path — but the honest rendering
 * of "we do not have a number" is a phrase that says so, not `"0 B"`, which
 * is a specific and wrong claim about a file the reader is about to send
 * irrevocably.
 */
export function formatAttachmentSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "unknown size";

  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit + 1 < SIZE_UNITS.length) {
    value /= 1024;
    unit += 1;
  }
  if (unit === 0) return `${Math.trunc(bytes)} B`;
  return `${value.toFixed(1)} ${SIZE_UNITS[unit]}`;
}

/**
 * Cap, in code points, on a rendered filename. POSIX allows 255 *bytes* per
 * path component, so a legitimate name can already be 255 characters, and
 * the name is echoed back from the homeserver once the file is sent — at
 * which point it is sender-controlled text like any other (spec §9) and
 * nothing stops it being megabytes long.
 *
 * The cap is a layout-safety bound on the value, not a display excerpt: no
 * ellipsis is appended, because CSS (`truncate`) owns visual truncation the
 * same way it does for every other bounded string in this codebase (see
 * `roomIdentity.ts`'s `MAX_NAME_CHARS`, which this mirrors).
 */
const MAX_FILENAME_CHARS = 120;

/** The literal shown when a filename sanitizes away to nothing at all. */
const UNNAMED_FILE = "Unnamed file";

/**
 * Characters removed from a filename before it is rendered, in two groups
 * that are removed for two different reasons.
 *
 * **C0/C1 controls** (U+0000–U+001F, U+007F–U+009F) because a filename on
 * POSIX may legally contain any byte except `/` and NUL — a newline in a
 * filename is unusual but perfectly creatable, and one in the review strip
 * would break a single-line row into two and push the size out of view.
 *
 * **Bidi marks, embeddings, isolates and overrides** (U+200E, U+200F,
 * U+202A–U+202E, U+2066–U+2069) because this strip is a *confirm* step, and
 * the whole point of a confirm step is that what it shows is what gets sent.
 * A right-to-left override is the classic filename spoof: a name written
 * `holiday<RLO>gnp.exe` renders as `holidayexe.png` while remaining an
 * executable. Stripping the explicit formatters is the standard mitigation
 * and costs nothing real — Arabic and Hebrew filenames are made of RTL
 * *letters*, which are untouched here and still lay out correctly on their
 * own.
 *
 * Deliberately not a general "printable characters only" filter: rejecting
 * anything unfamiliar would mangle every non-Latin filename, which is a far
 * more common case than a spoof.
 */
const FILENAME_STRIP = /[\u0000-\u001F\u007F-\u009F\u200E\u200F\u202A-\u202E\u2066-\u2069]/gu;

/** Any run of whitespace, collapsed to a single space — a tab or a stray double space in a filename must not become a gap in the strip. */
const WHITESPACE_RUN = /\s+/gu;

/**
 * Neutralizes and bounds `filename` for rendering. Never returns an empty
 * string: a name that sanitizes away entirely becomes {@link UNNAMED_FILE},
 * so a caller can render the result directly without an extra check and the
 * strip can never appear with a blank line where the filename should be.
 *
 * Slices by code point (`[...s]`), not by `String.prototype.slice`, which
 * counts UTF-16 code units and can cut a surrogate pair in half — the same
 * cut `roomIdentity.ts` documents at length. An emoji-heavy filename reaches
 * a 120-character boundary easily.
 */
export function sanitizeFilename(filename: string): string {
  const cleaned = sanitizeInline(filename, MAX_FILENAME_CHARS);
  return cleaned === "" ? UNNAMED_FILE : cleaned;
}

/**
 * Collapses whitespace, strips {@link FILENAME_STRIP}'s characters and bounds
 * to `maxPoints` **code points**. May return `""`; callers decide what an
 * empty result means.
 *
 * Whitespace first, controls second, and the order is not arbitrary: a
 * newline is *both*, and stripping it as a control would turn
 * `two\nlines.txt` into `twolines.txt` — a filename the reader is about to
 * send irrevocably, silently reported as a different one. Collapsing it to a
 * space keeps every word the reader can check.
 */
function sanitizeInline(value: string, maxPoints: number): string {
  const cleaned = value.replace(WHITESPACE_RUN, " ").replace(FILENAME_STRIP, "").trim();
  const points = [...cleaned];
  return points.length <= maxPoints ? cleaned : points.slice(0, maxPoints).join("");
}

/** The separator between the facts on the strip's second line. Mirrors the roster row's own `·`. */
const SUMMARY_SEPARATOR = " · ";

/** What `Composer.svelte` renders in the staged-attachment strip. */
export interface StagedStripView {
  /** The filename, sanitized and bounded — safe to render as text (never `{@html}`). */
  filename: string;
  /**
   * The facts under the filename: the human-readable size, the pixel
   * dimensions when the core reported them, and the mime type.
   *
   * **The mime type earns its place here specifically because this is a
   * confirm step.** The core sniffs it from the file's *content*, not from
   * the extension (design §5: "an extension is a claim, not a fact"), so it
   * is the one line on the strip a misleading filename cannot lie about —
   * and it is what a truncated filename hides first, since the extension is
   * at the end. A `holiday.png` that reads `application/octet-stream` is not
   * a photo. Dimensions are here for the milder version of the same
   * service: an image the recipient's client will lay out with them, and an
   * "image" that has none is worth a second look before an unrecallable
   * send.
   */
  summary: string;
}

/**
 * Cap, in code points, on the rendered mime type. The core produces this
 * from a fixed table (`infer`'s known signatures) so it is not hostile
 * input today — but it is a string on a line that must stay one line, and
 * the longest real mime types run to ~70 characters.
 */
const MAX_MIME_CHARS = 80;

/**
 * Composes the strip's text from a staged attachment.
 *
 * Dimensions are included only when **both** are present and are positive
 * finite numbers. The core omits the keys entirely for a non-image and for
 * an image whose header it could not parse (see {@link StagedAttachment}), so
 * a half-present pair is not a case that can arise today — it is guarded
 * anyway, because the alternative rendering (`1920 × undefined`) is the kind
 * of thing that reaches a screenshot.
 */
export function stagedStripView(staged: StagedAttachment): StagedStripView {
  const parts = [formatAttachmentSize(staged.sizeBytes)];
  const { width, height } = staged;
  if (isPositiveInteger(width) && isPositiveInteger(height)) {
    parts.push(`${width} × ${height}`);
  }
  const mime = sanitizeInline(staged.mime ?? "", MAX_MIME_CHARS);
  if (mime !== "") parts.push(mime);
  return {
    filename: sanitizeFilename(staged.filename),
    summary: parts.join(SUMMARY_SEPARATOR),
  };
}

function isPositiveInteger(value: number | undefined): value is number {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
}

/**
 * The line the strip adds when Send could otherwise be read two ways, or
 * `null` when it could not.
 *
 * Captions are out of scope for this cut (design §1) and an attachment is
 * never sent as a reply (`attachment_send` takes no `in_reply_to`), so a
 * staged file makes Send do exactly one thing — but a composer that *also*
 * holds draft text, or a pending reply target, has two other things on
 * screen that a reader could reasonably expect Send to include. §8 requires
 * only that the two readings are never ambiguous at the same moment, so the
 * strip says which one applies, and says it only when there is something to
 * disambiguate: on an otherwise empty composer, "Send file" is already
 * unambiguous and this line would be noise on the one strip a reader most
 * needs to take in at a glance.
 *
 * Both leftovers are stated as *still waiting* rather than as discarded,
 * because that is what actually happens: sending an attachment touches
 * neither the draft nor the reply target.
 */
export function sendCaveat(hasDraft: boolean, hasReply: boolean): string | null {
  if (hasDraft && hasReply) {
    return "Send sends this file on its own. Your message text and your reply are still waiting.";
  }
  if (hasDraft) return "Send sends this file, not your message text. The text stays in the draft.";
  if (hasReply) return "Send sends this file on its own, not as a reply. Your reply is still waiting.";
  return null;
}

/**
 * Which half of the flow a failure came from. The same `CoreError` kind can
 * arrive from either — `attachmentTooLarge` is checked at staging time *and*
 * again immediately before the bytes are read — and the reader's situation
 * is not the same in both, so the wording is not either.
 */
export type AttachmentPhase = "attach" | "send";

/** A refusal, as the composer's error strip renders it: a `--text-label` rank eyebrow and a sentence. */
export interface AttachmentFailure {
  /**
   * The eyebrow. Written in sentence case and uppercased by CSS — uppercase
   * here is a typographic rank, never shouting in a sentence (spec §10).
   */
  label: string;
  /** The sentence under it. Plain text, bounded, rendered in a `role="alert"`. */
  message: string;
}

/**
 * Cap on a core-supplied message. Generous — these are sentences the core
 * writes, not payloads a stranger does — but a `store`-kind error interpolates
 * an OS error string, and an OS error string is not something this file gets
 * to make promises about.
 */
const MAX_FAILURE_CHARS = 300;

/**
 * Turns a rejected attachment command into something the reader can act on.
 *
 * The two kinds the attachments work added are the ones this function exists
 * for, and the distinction between them is the reason they are two kinds
 * rather than one (see `CoreErrorKind` in `$lib/ipc.ts`):
 *
 * - **`unknownAttachment`** — the file is *gone*. Spent, discarded, expired,
 *   or dropped by a room switch or logout. Nothing is recoverable and the
 *   only move is to attach it again, so that is what the message says.
 * - **`roomChanged`** — the file is *fine*; it is the room that moved. The
 *   token is still staged for the room it was picked in and nothing was
 *   consumed. The message names the room switch as the cause, because a
 *   reader who is told "attach it again" without being told why will attach
 *   the same file into the same wrong room.
 *
 * `attachmentTooLarge` passes the **core's own message** through, because it
 * already names both real sizes in matching units and a generic replacement
 * would delete the only part a reader can act on. It is only capitalized:
 * the core writes lowercase sentence fragments (`"that file is 200.0 MiB,
 * but…"`) and this codebase's copy is sentence case.
 *
 * Everything else falls back to a phase-appropriate label and, where the
 * core supplied a message, that message — a `store`-kind "cannot read that
 * file: permission denied" is far more useful than "something went wrong".
 */
export function attachmentFailure(err: unknown, phase: AttachmentPhase): AttachmentFailure {
  const error = err as CoreError | undefined;
  const kind = typeof error?.kind === "string" ? error.kind : undefined;
  // Only a value that actually looks like a `CoreError` gets its `message`
  // shown. Every JS `Error` has one too, and "undefined is not a function"
  // is developer text that must never reach the composer — the `kind` is
  // what distinguishes a sentence the core wrote for a reader from a
  // stack-adjacent string that happens to live on the same property.
  const coreMessage = kind !== undefined && typeof error?.message === "string" ? error.message.trim() : "";

  switch (kind) {
    case "attachmentTooLarge":
      return {
        label: "File too large",
        message: capitalize(
          bound(coreMessage) ||
            "That file is bigger than this homeserver will accept.",
        ),
      };
    case "unknownAttachment":
      return {
        label: "File not staged",
        message: "That file is no longer staged. Attach it again.",
      };
    case "roomChanged":
      return {
        label: "Wrong room",
        message:
          phase === "send"
            ? "Not sent — you switched rooms before this went through. Attach the file again in the room you meant."
            : "You switched rooms before that file was attached. Try again in the room you meant.",
      };
    default:
      return {
        label: phase === "send" ? "Send failed" : "Couldn't attach",
        message: capitalize(
          bound(coreMessage) ||
            (phase === "send"
              ? "That file wasn't sent. Attach it again."
              : "That file couldn't be attached."),
        ),
      };
  }
}

/** Truncates to {@link MAX_FAILURE_CHARS} code units with an ellipsis — the same display-truncation shape `customEvents.ts` uses. */
function bound(value: string): string {
  if (value.length <= MAX_FAILURE_CHARS) return value;
  return `${value.slice(0, MAX_FAILURE_CHARS)}…`;
}

/** Uppercases the first *code point*, so a message starting with an astral character is not split in half. */
function capitalize(value: string): string {
  const points = [...value];
  if (points.length === 0) return value;
  return points[0].toUpperCase() + points.slice(1).join("");
}

/**
 * The one staged attachment the composer is holding, and which room it
 * belongs to.
 *
 * **One, not a list.** The core holds at most one staged file per room and
 * *replaces* rather than refuses when a second is staged for the same room
 * (`StagedAttachments::insert_at` — its own judgement call, recorded there),
 * dropping the superseded token so it fails closed. A list here would
 * therefore be a list of tokens that mostly do not resolve, rendered as a
 * queue that cannot be sent.
 *
 * **Room-scoped, and that is the whole reason this is a class rather than a
 * bare `$state` in the component.** Every read names the room it is asking
 * about and gets `null` if the staged file is not that room's, so the
 * composer cannot show — or send — one room's attachment under another
 * room's header. That failure has shipped in this codebase before for the
 * draft text (`draftTracker.ts`) and for the reply target
 * (`replyTarget.svelte.ts`); for an attachment it would mean sending a file
 * to the wrong people, with no redaction command to undo it.
 *
 * **Why a switch discards rather than preserving per room**, unlike a draft:
 * the core has already discarded it. `Session::subscribe_timeline` calls
 * `StagedAttachments::retain_room(new_room)` after every successful
 * subscribe, so a token minted for the room being left stops resolving the
 * moment the switch completes. Preserving it here would keep a strip on
 * screen offering to send a file whose token is already dead — the strip
 * would be a lie, and pressing Send would produce `unknownAttachment` for a
 * file the reader can plainly see. A draft is the opposite case: nothing
 * server-side expires it, so keeping it costs nothing and saves retyping.
 */
export class StagedAttachmentTracker {
  #roomId: string | null = null;
  #attachment: StagedAttachment | null = null;

  /**
   * Records `attachment` as staged for `roomId`, replacing whatever was held
   * before — for this room or any other.
   *
   * Returns the superseded attachment, or `null`. The caller **must** discard
   * the returned one's token (`attachmentDiscard` never rejects, so this is
   * unconditional and cheap): for a same-room replacement the core has
   * already dropped it and the discard is a documented no-op, but for the
   * cross-room case — a `sm://attachment/staged` event arriving as the reader
   * switches rooms — it is the only thing that stops a path being pinned for
   * the rest of the staging timeout.
   */
  stage(roomId: string, attachment: StagedAttachment): StagedAttachment | null {
    const superseded = this.#attachment;
    this.#roomId = roomId;
    this.#attachment = attachment;
    return superseded;
  }

  /**
   * The attachment staged for `roomId`, or `null` — **never another room's**.
   * This is the rule the whole class exists for; see the class doc comment.
   */
  stagedFor(roomId: string): StagedAttachment | null {
    return this.#roomId === roomId ? this.#attachment : null;
  }

  /**
   * Removes and returns whatever is held, whichever room it belongs to.
   *
   * Used on the paths where the attachment is finished with regardless of
   * room: a successful send, a failed send (the token is spent or unreachable
   * either way — see `attachmentSend`'s doc comment), the reader pressing
   * Remove, and the composer's own teardown.
   */
  take(): StagedAttachment | null {
    const held = this.#attachment;
    this.#roomId = null;
    this.#attachment = null;
    return held;
  }

  /**
   * Removes and returns what is held **only if its token matches** `token`;
   * otherwise leaves everything alone and returns `null`.
   *
   * The send path's version of `take`. A send is asynchronous, and by the
   * time it resolves the reader may already have attached a different file —
   * clearing the strip then would delete a file they are still expecting to
   * send. Matching on the token means a completed send only ever clears the
   * attachment it was actually for, which is the same rule `Composer`'s text
   * send applies to the draft with its `roomId === sentRoomId` check.
   */
  takeToken(token: string): StagedAttachment | null {
    if (this.#attachment?.token !== token) return null;
    return this.take();
  }

  /**
   * Switches focus to `roomId`, returning the attachment abandoned by the
   * switch — the caller discards its token — or `null` when there was
   * nothing, or when what is held already belongs to `roomId`.
   *
   * Called unconditionally from the composer's room-switch effect, the same
   * one `DraftTracker.switchTo` is called from, so a same-room re-run cannot
   * throw away a file the reader just attached.
   */
  switchTo(roomId: string): StagedAttachment | null {
    if (this.#roomId === roomId) return null;
    return this.take();
  }
}
