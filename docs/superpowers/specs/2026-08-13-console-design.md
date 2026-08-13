# supermessage visual design — "Operator console, editorial reading"

**Status:** Decided (13 Aug 2026). Binding authority for the design pass.
**Companions:** [positioning.md](../../positioning.md), [tech-stack.md](../../tech-stack.md), [matrix-events.md](../../matrix-events.md).

---

## 1. The brief

supermessage is the human-facing client of a **synthetic organization** — an
org where AI agents and people are both first-class members
(`positioning.md`). Its rooms are not group chats. On the live homeserver
today they are agents: `🧠 Buddhimaan — Squad Lead`, `🛡️ Threat Hunter Theo —
Security`, `💻 Coder Kai — Code & Build`.

**Audience:** one operator, supervising eight to twenty agents.

**The page's single job:** let the operator tell at a glance which agent is
which and what it is saying — and act on the ones that need a decision.

Two facts about the content drive everything below:

1. **Agents write at length.** Their messages are plans, findings, and
   reports, not chat banter. The timeline is a reading surface.
2. **Agents ask for permission.** Wedge #3 in `positioning.md` is
   approvals-from-chat: "the missing HITL channel — nobody else has this."
   A decision arriving in the timeline is the most important thing this app
   will ever render.

So: **chrome is instrumentation, content is editorial, and one colour is
reserved for decisions.**

---

## 2. What the design is *not*

Recorded so a reviewer can check it. Three looks that turn up in generated
design regardless of subject, all rejected here:

- Warm cream ground + high-contrast display serif + terracotta accent.
- Near-black ground + a single acid-green or vermilion accent. **This is the
  obvious answer for "operator console" and is specifically refused.** Our
  neutral is a blue-cast slate, not black, and our signal is amber.
- Broadsheet hairlines + zero radius + dense newspaper columns.

Also refused: `01 / 02 / 03` numbered markers. Nothing in this app is a
sequence.

---

## 3. Palette

One neutral ramp with a blue cast (indigo-slate), one chrome hue (indigo),
one signal (amber), one danger (red). **No fifth hue.** Liveness is
conveyed by a filled-vs-hollow dot plus a word, never by a colour of its
own.

### Amber is reserved

`--color-signal` may be used **only** for a pending decision — a permission
or approval request awaiting the operator. It appears nowhere else: not on
unread badges, not on hover, not on warnings, not on the connection banner.
If amber is on screen, the operator owes someone an answer. Any other use is
a review defect.

### Tokens

Light (`:root`):

| Token | Value | Use |
|---|---|---|
| `--color-surface` | `#ffffff` | Reading surface, composer |
| `--color-surface-sunken` | `#f4f5f8` | Roster, info panel, insets |
| `--color-surface-raised` | `#fbfbfd` | Cards, chips |
| `--color-border` | `#dfe1e8` | Hairlines |
| `--color-border-strong` | `#c3c7d2` | Focused inputs, card edges |
| `--color-content` | `#161821` | Body text |
| `--color-content-muted` | `#4a4f5e` | Labels, metadata |
| `--color-content-faint` | `#6b7082` | System lines, disabled |
| `--color-accent` | `#4249c4` | Selection rail, focus ring, Send |
| `--color-accent-content` | `#ffffff` | Text on accent |
| `--color-accent-soft` | `#eceefb` | Own-message ground |
| `--color-signal` | `#8a4e00` | **Pending decision only** |
| `--color-signal-soft` | `#fdf3e2` | Pending-decision card ground |
| `--color-danger` | `#b4232a` | Errors, failed sends |

Dark (`prefers-color-scheme: dark`):

| Token | Value |
|---|---|
| `--color-surface` | `#14161c` |
| `--color-surface-sunken` | `#090b11` |
| `--color-surface-raised` | `#1b1e26` |
| `--color-border` | `#282c37` |
| `--color-border-strong` | `#3a3f4e` |
| `--color-content` | `#e8eaf0` |
| `--color-content-muted` | `#b0b6c6` |
| `--color-content-faint` | `#787f91` |
| `--color-accent` | `#8b9bf7` |
| `--color-accent-content` | `#12141b` |
| `--color-accent-soft` | `#242a44` |
| `--color-signal` | `#f0a63c` |
| `--color-signal-soft` | `#2e2317` |
| `--color-danger` | `#f4756e` |

Both themes are first-class. Every value above must be checked against its
own ground for contrast; `--color-content-faint` on `--color-surface` is the
tightest pair and must still clear 4.5:1 for the 10.5px system lines it
carries. Adjust the token, never the usage, if it does not.

**These values are the shipped ones, revised three times during
implementation and synced back here — the table is not the original draft.**
All three revisions are worth knowing about, because all three were caught by
rendering or review rather than by design:

- The first draft's `faint` (`#8b90a0` / `#6b7183`) failed this section's own
  floor, reading as low as 2.92:1. Raising it cleared the floor but left it
  ~1.2:1 against `muted`, which is a wobble, not a rank — so `muted` was
  re-spaced too. The ramp is now roughly 15.9 : 8.2 : 4.9 in light, a real
  progression with the floor as its last step rather than its only
  constraint.
- `--color-signal` was `#a35c00`, which read 4.63:1 on `--color-signal-soft`
  — clearing the floor by almost nothing, while carrying the 10px `AWAITING
  YOUR DECISION` label: the smallest text in the app, on the one element the
  operator must never miss. Darkened to 6.0:1. Dark mode was already 7.5:1
  and is unchanged.
- Dark `--color-surface-sunken` was `#101218`. Once §6.3 put the reading
  column on `--color-surface` over a `--color-surface-sunken` field, that
  pair became the load-bearing step in the whole design — and it measured
  **1.035:1 in dark against 1.090:1 in light**, less than half the step and
  effectively subliminal across 1050px of field at 1905px. `#090b11` brings
  dark to **1.088:1**. Only the sunken end could move: lightening
  `--color-surface` far enough (~`#191b23`) collapses surface-vs-raised from
  1.085:1 to 1.031:1, deleting the third level of the stack to fix the
  second, and drops `--color-content-faint` on the reading surface to
  4.29:1 — under §9's floor. The value keeps the ramp's `(R, R+2, R+8)`
  channel spacing, so the blue cast survives. Light is unchanged.

The lesson for anyone extending this palette: a ratio that merely clears
4.5:1 is not automatically finished. Check it against the *neighbouring rank*
as well as against its ground, and give the smallest text the widest margin.

**Surface steps must be checked in both themes independently.** The two
ramps are not mirror images of each other — sRGB luminance is far from
linear near black, so a tone step that reads in light can be half as strong
in dark at the same hex distance. Every step in the sunken → surface →
raised stack is now: light 1.090 / 1.034, dark 1.088 / 1.085.

**Which ground a control lifts *to* depends on the ground it sits on.** On
`--color-surface` a control drops to `--color-surface-sunken` for hover; on
`--color-surface-sunken` — the roster, and since §6.2's header moved, the
room header too — it lifts to `--color-surface`, with `bg-surface/60` for
hover where a sustained `bg-surface` state also exists. Moving an element
between the two grounds means moving its controls' states with it, or they
land at 1.0:1 and disappear.

**No hardcoded colours anywhere.** The existing rule holds: components
consume tokens, and `Timeline.svelte`'s `.message-html` block keeps reading
through `currentColor`.

---

## 4. Typography

Three faces, three jobs. Bundled locally via `@fontsource*` npm packages —
the app's CSP is `default-src 'self'`, so no font may be fetched from a CDN,
and a system stack would make the app look like a different product on each
of the four platforms it ships to.

| Role | Face | Package | Why |
|---|---|---|---|
| Chrome, UI, own messages | **IBM Plex Sans** | `@fontsource-variable/ibm-plex-sans` | A face with literal instrument-panel lineage; its slightly narrow, squared forms hold a dense roster at 13px |
| Message bodies (peers) | **Source Serif 4** | `@fontsource-variable/source-serif-4` | A screen-native reading serif. Agent output is long-form; setting it in a serif is the strongest available way to say "this is the thing to read" |
| Data, labels, sigils, timestamps | **IBM Plex Mono** | `@fontsource/ibm-plex-mono` (400, 500) | Event types, room ids, mxids and timestamps are data; a mono grid is how data reads as data |

`@fontsource-variable/ibm-plex-mono` does not exist — use the static
`@fontsource/ibm-plex-mono` at weights 400 and 500 only. `font-synthesis:
none` is already set in `app.css`, so every weight in use must be a real
weight.

**Subsets: all of them, not latin-only.** This draft originally said latin
only; that turned out to be unachievable. The `@fontsource-variable`
packages expose only axis-level entry points (`wght.css`, `opsz.css`), each
carrying every subset — there is no per-subset CSS for a variable family.
Stripping subsets would mean hand-writing `@font-face` blocks with hardcoded
asset paths and hand-maintained `unicode-range`s. Against that, the extra
~600 KB sits in a *bundle*, not a page load — nothing is ever fetched — and
keeping cyrillic and greek means a Matrix display name in those scripts
renders in the designed face instead of falling back.

**Sans italic is bundled too** (`wght-italic.css`), and this is not
optional: with `font-synthesis: none`, an `<em>` inside an *own* message —
which is set in sans, unlike a peer message — renders upright and loses its
emphasis silently. Mono deliberately has no italic; see §6.3.

**The risk this design takes, stated plainly:** serif message bodies in a
chat client. Nobody does it. It is justified here because these are not chat
messages — they are agent reports read at length. If it fails, it fails
visibly and reverts to `--font-system` in one token.

### Scale

| Token | Size / line-height | Face | Use |
|---|---|---|---|
| `--text-label` | 10px / 1.2, `0.08em` tracking, uppercase | Mono 500 | Role chips, field labels, `REPLYING TO`, card header |
| `--text-meta` | 10.5px / 1.3 | Mono 400 | Timestamps, ids, `seen`, system lines |
| `--text-ui` | 13px / 1.4 | Sans 400/500 | Buttons, roster rows, panel text |
| `--text-ui-lg` | 15px / 1.4 | Sans 600 | Room header name |
| `--text-body` | 15px / 1.62 | Serif 400 | Peer message bodies, card values |
| `--text-body-own` | 14px / 1.5 | Sans 400 | Own message bodies |

Reading measure for peer bodies: **`68ch`**, not a percentage.

---

## 5. Structural devices

Each encodes something true about the content. No decoration.

### 5.1 The em-dash role parse

Room names on this homeserver carry structure: `<glyph> <Name> — <Role>`.
Split it and set the parts at different ranks instead of truncating one
string.

```
"🧠 Buddhimaan — Squad Lead"  →  glyph "🧠", name "Buddhimaan", role "SQUAD LEAD"
"aether-dispatches"            →  glyph null, name "aether-dispatches", role null
```

Rules, in a pure, unit-tested module (`src/lib/components/roomIdentity.ts`):

- Split on the **first** em dash (`—`, U+2014) with surrounding whitespace.
  Only an em dash — a hyphen in `aether-dispatches` must not split.
- The glyph is the leading grapheme **only if** it is outside the ASCII
  range and is followed by whitespace. Iterate code points
  (`[...name]`), never code units — the `initials()` astral-surrogate bug
  is already on record.
- Both halves trim; an empty half yields `null`.
- Degrade to `{glyph: null, name: <the whole trimmed name>, role: null}`.
  A room with no role is not a broken room and must not show a placeholder.
- Bound `role` at 40 chars and `name` at 120 for layout safety, consistent
  with every other sender-controlled surface in this codebase.

### 5.2 Matrix sigils

`@user:server`, `#alias:server`, `!roomid:server` — Matrix's own vernacular,
and unmistakably this protocol rather than a generic chat app. In
`RoomInfoPanel`, set every identifier in mono with the sigil rendered in
`--color-content-faint` and the rest in `--color-content-muted`. No box, no
chip: the sigil *is* the label, so the existing "Address" / "Room ID"
headings above the mono lines can go.

### 5.3 Mono means machine, serif means prose

The face itself carries the distinction. System lines (membership, room
creation, encryption enabled), placeholders, event types and ids are mono.
Everything a human or agent wrote to be read is serif. Chrome is sans. A
reader learns this in about four seconds and never has to be told.

---

## 6. Layout

### 6.1 Roster (left, `w-[300px]`)

Two lines per row, replacing today's one-line name + pill.

```
┌────────────────────────────────────┐
│ ▎ 🧠   Buddhimaan               2  │
│       SQUAD LEAD · 4m              │
├────────────────────────────────────┤
│   🛡️   Threat Hunter Theo          │
│       SECURITY · 2h                │
└────────────────────────────────────┘
```

- Line 1: name, Sans 500 `--text-ui`, truncated. Unread count right-aligned,
  mono `--text-meta`, on `--color-accent` — a count, not a dot.
- Line 2: role (`--text-label`) `·` relative last activity (`--text-meta`).
  No role → the time alone. No activity → the line is omitted entirely
  rather than printed empty.
- Recency is the only honest liveness signal available per row (per-room
  typing is not streamed; `typingStore` scopes to the focused room). Rooms
  active within the last 5 minutes render their time in
  `--color-content-muted`; older ones in `--color-content-faint`. No dot, no
  animation, no invented "online" state.
- Avatar 32px. Fallback is the parsed **glyph** when there is one, the first
  letter of the parsed `name` otherwise — never the raw first character of
  the full room name.
- Selection: a 2px `--color-accent` left rail plus `--color-surface`
  ground. Not a full accent fill.
- Row separator: hairline, inset to clear the avatar column.

Relative time: `now`, `4m`, `2h`, `3d`, then a date. Pure and unit-tested
alongside the identity parse; takes an explicit `now` argument so the tests
do not depend on the clock.

### 6.2 Room header

```
 🧠  Buddhimaan   SQUAD LEAD                        ● live    Info
─────────────────────────────────────────────────────────────────
```

- 24px avatar, name in `--text-ui-lg`, role as a bordered chip in
  `--text-label` (`--color-border`, `--color-content-muted`). Chip omitted
  when there is no role.
- Right: the connection dot — **filled** for `live`, hollow for everything
  else — plus the state word in `--text-meta` lowercase mono. Colour:
  `--color-content-muted`, or `--color-danger` for `error`. Never amber.
- The header's own ground is `--color-surface-sunken`, matching the roster,
  the timeline field and the composer. It is chrome, not part of the reading
  sheet §6.3 lays over that field — on `--color-surface` it was a full-width
  lit bar capping the sheet, and the pane read as two lit regions rather
  than one column. Its `--color-border` bottom hairline is what separates it
  from the field, and is now load-bearing rather than reinforcing a tone
  step.
- `Info` stays a text button; `--text-ui`, hover `--color-surface` at 60%,
  pressed state uses `--color-surface`. (Both were
  `--color-surface-sunken` while the header was `--color-surface`; on a
  sunken header they measure 1.0:1 and vanish. See §3 on which way a control
  lifts.)
- Do not show a member count here. It comes from `roomInfo`, which is only
  fetched when the panel opens; showing a stale or absent number would be
  worse than showing none.

### 6.3 Timeline — the reading surface

#### 6.3.0 One reading column

**Everything in the timeline lays out inside a single centred column of
`72ch`** — peer blocks, own bubbles, date dividers, system lines,
placeholders, emotes and dispatch cards alike. Peers align to its left edge,
own messages to its right edge, and centred rows to its centre.

This was added after the first implementation was rendered and reviewed. The
original text said only "right-aligned" for own messages, which was read —
reasonably — as right-aligned against the *viewport*. At 1905px that put a
reply **599px** from the message it was answering, and in the very case where
the relationship is most explicit, the one where the reply quotes its parent
by name. The pane read as two unrelated columns with a void between them,
and it got worse as the window got wider.

The asymmetry this design wants between own and peer messages is one of
**register, not geometry**: sans against serif, tight against airy, a ground
against no ground. Every bit of that survives inside a shared column. The
horizontal distance was never carrying meaning — it was just the default a
chat bubble inherits, which is exactly the thing this surface is trying to
stop being.

**Peer messages lose the bubble.** A 70%-wide rounded bubble is the wrong
container for a 400-word plan.

```
BUDDHIMAAN   14:22

I've traced the double-response to a duplicate gateway holding the
same Matrix profile. Stopping the stray process clears it; the code
fix still matters, because the next console start recreates it.

  👍 2    Reply  👍 🎉 …
```

- Sender line: name in `--text-label`, timestamp in `--text-meta`, same
  baseline, `--color-content-muted`.
- Body: `--text-body` (serif), `--color-content`, `max-width: 68ch`,
  left-aligned, no background, no border.
- **Sender runs collapse.** Consecutive messages from the same sender within
  5 minutes drop the sender line and tighten to a 2px gap. Extend
  `timelineGrouping.ts` (which already collapses membership runs) rather
  than adding a second grouping mechanism. The timestamp of a collapsed
  continuation is not rendered.
- Hover reveals the existing actions row, unchanged in behaviour.

**Own messages keep a bubble** — right-aligned, `--color-accent-soft`
ground, `--color-content` text, 6px radius, `--text-body-own` (**sans**),
`max-w-[52ch]`. The asymmetry is the point: *you type, they write.* Own
messages are commands; they are not set for reading at length.

- Date divider: a full-width hairline with the date centred on it in
  `--text-label`, sitting on the surface colour. Not a pill.
- System lines: centred, `--text-meta`, `--color-content-faint`. Reads as a
  quiet log.
- Placeholders (undecryptable, redacted, unrenderable): same as system
  lines. **No italic** — and the same goes for the `edited` marker and every
  other mono rank. `font-synthesis: none` means an italic needs a real
  bundled italic file, and IBM Plex Mono's would be ~30 KB bought to slant
  three short strings that the mono face already marks as secondary. Italic
  survives only where it does real work and the face is already bundled for
  it: serif emotes, and `<em>` inside a message body.
- Emotes: centred, serif italic, `--color-content-muted`.
- Images and media files keep their current structure but adopt the peer
  layout — no bubble for peers, 6px radius on the image, filename in sans,
  size/kind line in mono.

### 6.4 Composer

```
─────────────────────────────────────────────────────────
 ›  Message…                                  Send  ⏎
```

- A `›` prompt sigil in the left gutter, mono, `--color-content-faint` —
  one character of console vernacular, not a decoration.
- Textarea: transparent, borderless, Sans `--text-ui`. The **container**
  carries the focus ring (2px `--color-accent`, offset), so the whole strip
  responds as one instrument.
- `Send` compact, `--color-accent`, with a `⏎` hint in mono at 70% opacity.
- Reply strip above: 2px `--color-accent` left rail, `REPLYING TO <sender>`
  in `--text-label`, excerpt in serif `--text-meta`-sized, `✕` to cancel.
- Send error: `--color-danger`, `SEND FAILED` in `--text-label`, the message
  in `--text-ui`. Keeps `role="alert"`.

### 6.5 Connection banner

A 24px strip, `--color-surface-raised`, hairline below. State word in
`--text-label`, message in `--text-ui` `--color-content-muted`. Danger token
for `error`. Behaviour unchanged: hidden entirely when `live`. It carries
the *message*; the header dot carries the at-a-glance state.

### 6.6 Room info panel

Sections keep their order. Changes: drop the "Address" / "Room ID" headings
in favour of sigil-led mono lines (§5.2); set the topic in serif
`--text-body` (it is prose); member rows show the display name in sans and
the mxid in sigil-led mono; section rules become hairlines with
`--text-label` headings.

---

## 7. The signature element — the dispatch card

**This is the one thing the app is remembered by, and the one place amber
appears.**

Every `kind: "customMessage"` item — Kaambaan cards, runs, station status,
and above all **permission requests** — renders as the timeline's only
bordered object. Nothing else in the timeline has a border.

```
╭─ 2px edge
│ ┌──────────────────────────────────────────────┐
│ │ …SUPERMESSAGE.DEMO.NOTE.V1            14:22  │
│ ├──────────────────────────────────────────────┤
│ │ NOTE      Restart hermes-gateway             │
│ │ SCOPE     station-04                         │
│ └──────────────────────────────────────────────┘
╰─
```

- Full width of the reading measure, **left-aligned regardless of sender**.
  A dispatch is not a remark; it does not take a side.
- 6px radius, `--color-surface-raised` ground, a **1px `--color-border`
  hairline** on three sides, and a **2px `--color-border-strong` left edge**.

  The two border ranks are the point, and the first implementation got this
  wrong in a way only rendering revealed: it used `--color-border-strong` on
  all four sides, so the left edge was the same colour as its neighbours and
  merely one pixel wider — invisible. The card's signature device therefore
  existed *only* on the pending variant, which no shipped renderer can
  currently produce. The edge must be a visible rank in the ordinary state,
  so that going amber changes an edge's **meaning** rather than conjuring an
  edge from nothing.
- Header row: the event type in `--text-label`, hairline beneath,
  timestamp right. The type is a reverse-DNS string whose **tail** is the
  informative part, so truncate from the **left** with a leading ellipsis —
  `…supermessage.demo.note.v1`, never `dev.supermessage.dem…`.
- Field rows: label column `--text-label` at a fixed `9ch`, value in
  `--text-body` (serif). Values stay plain-text interpolation — the
  no-`{@html}` rule for custom payloads is unchanged and non-negotiable.
- `fallbackBody` status: the body in serif, no field grid.
- `placeholder` status: keep it a quiet centred system line, not a card. A
  type we cannot render is not worth a bordered object.

### 7.1 The pending-decision variant

The left edge turns **`--color-signal`**, the ground becomes
`--color-signal-soft`, and a decision row appears beneath the fields:

```
│ │ AWAITING YOUR DECISION                       │
│ │ [ Approve ]  [ Decline ]                     │
```

This is the only amber in the application.

**No wire schema is invented here.** Kaambaan owns the event schema and it
has not landed (`customEvents.ts` is explicit that this module must never
invent one). What this pass adds is the *UI contract on our own side*:

```ts
export interface CustomEventRenderResult {
  fields: CustomEventField[];
  /** Set by a renderer when the payload carries a decision the operator
   * still owes an answer to. No shipped renderer sets this yet — the
   * Kaambaan permission schema is the first that will. */
  decision?: {
    prompt: string;
    options: { id: string; label: string }[];
  };
}
```

- `resolveCustomEvent` bounds `decision` exactly as it bounds fields: prompt
  to `FIELD_VALUE_MAX_CHARS`, at most 4 options, each label to
  `FIELD_LABEL_MAX_CHARS`, and drops `decision` entirely if there are no
  valid options. A malformed decision degrades to an ordinary card, never to
  a card with a broken button.
- The card takes an `onDecide(itemId, optionId)` callback prop. **No shipped
  renderer sets `decision`, so no button reaches production in this pass.**
  `Timeline.svelte` wires `onDecide` to a function that logs and does
  nothing else, with a comment naming the Kaambaan gate-resolution call that
  replaces it.
- Cover the pending variant with unit tests against a fixture renderer, the
  same way `customEvents.test.ts` already tests the registry with fixtures.
  The slot ships proven, not speculative.

**Do not ship a visible button that does nothing.** If a future reviewer
finds a rendered Approve button with no backend, that is a defect against
this spec.

---

## 8. Motion

Almost none, deliberately. Scattered animation is the loudest tell of
undesigned work.

- 100ms `background-color` / `border-color` transitions on hover, selection
  and focus. Nothing else.
- No entrance animation on timeline items — they arrive inside a
  virtualiser, and a fade on every scroll recycle is worse than none.
- The existing `animate-pulse` image placeholder stays; it communicates
  loading, which is real.
- All of it inside `@media (prefers-reduced-motion: reduce) { … }` opt-outs.

---

## 9. Quality floor

Not announced in the UI, just true:

- **Focus:** every interactive element shows a 2px `--color-accent` ring
  with a 2px offset. Never `outline: none` without a replacement.
- **Contrast:** 4.5:1 for body and label text in both themes; verify the
  faint-on-surface pairs specifically.
- **Colour is never the only channel.** The connection dot pairs with a
  word; the pending card pairs amber with the `AWAITING YOUR DECISION`
  label; failed sends pair danger with text.
- **Responsive:** below `840px` the room-info panel overlays instead of
  taking a column; below `640px` the roster collapses to a rail the room
  view can return to. This subsumes the carried "responsive layout polish"
  follow-up.
- **Safe areas:** the existing `--inset-*` handling is preserved exactly.
- **user-select discipline** is preserved exactly: chrome is not selectable,
  content is `.selectable`.

---

## 10. Copy

The design expresses the agent-fleet reading through structure (role chips,
glyphs, roster density) because that structure comes from the data and
degrades on its own. **The words stay protocol-truthful** — this is a Matrix
client and a room may well hold people.

| Now | Becomes | Why |
|---|---|---|
| "Select a room to start chatting" | "Choose a room from the roster." | An empty screen is an invitation to act |
| "No messages yet" | "Nothing here yet." | Shorter, same meaning |
| "No rooms yet." | "No rooms yet." | Already right |
| "Room info" (button) | "Info" | The header already names the room |
| "Custom event" (card eyebrow) | *removed* | The event type is right there; the label was doing nothing |
| "Failed to send" | "Not sent" | States the fact without the drama |
| "Sending…" | "Sending…" | Already right |

Sentence case throughout. Labels are uppercase only as a *typographic* rank
(`--text-label`), never as shouting in a sentence.

---

## 11. Non-goals for this pass

- No new IPC commands, no core (Rust) changes. This is a frontend pass.
- No Kaambaan wire schemas.
- No theme switcher — `prefers-color-scheme` only.
- No icon set. The two glyphs in use (`✕`, `›`) stay as characters.
