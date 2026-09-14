# The design language

The values live in [`design/tokens.toml`](../design/tokens.toml) and are
generated into every surface. **This file holds what values cannot** — what
the colours and ranks *mean*, and which uses are defects.

It supersedes `docs/superpowers/specs/2026-08-13-console-design.md` as the
authority. That document remains the record of how the language was arrived
at, and most of the reasoning below came from it. But it is desktop-web
shaped, and being a document three codebases read and re-derived by eye is
the failure this system exists to end: by 2026-09 the web app, the native
apps and the marketing site had three different palettes, three different
ambers, and a dark accent on mobile that was not even the same hue as its
own light accent.

## 1. The three faces

Structural, not decorative. This is the identity that travels between
platforms — not the typefaces, which differ by design.

| Face | Means |
|---|---|
| **Serif** | What an agent wrote. The timeline is a reading surface. |
| **Sans** | What the operator wrote. A command, not prose. |
| **Mono** | Data. Sigils, roles, timestamps, counts, code. |

So `body` is serif and `body-own` is sans, and that pairing is asserted by a
test in every emitter. Web bundles IBM Plex and Source Serif 4 because its
CSP is `default-src 'self'`; iOS and Android use the system's own generic
families, which is what makes Dynamic Type and font scaling free.

**Never emit a font size to a native platform.** The type roles carry a rem
size for web and a text-*style* name for iOS and Android. A number in the
`ios` or `android` field is rejected by the generator, because it silently
costs every user on that platform their text scaling and still looks correct
to whoever made the change.

## 2. Amber means one thing

**`signal` is a pending decision the reader owes an answer to. Nothing else.**

Not unread badges. Not hover. Not warnings. Not the connection banner. Not
"attention". Any other use is a review defect — reach for `danger` or
`accent` instead.

`signal` clears **6:1** against `signal-soft`, not the usual 4.5, because it
carries the 10px `AWAITING YOUR DECISION` label: the smallest text in the
app, on the one element the operator must not miss.

**`ok` is not amber.** A room that is working is good news, and amber is
reserved for what a reader owes.

## 3. Three text ranks, and the floor is the roster

`content` → `content-muted` → `content-faint`, at roughly 16:1, 8:1 and 5:1
on the reading surface.

**`content-faint` must clear 4.5:1 on all three grounds**, not just on
`surface`. The ground it fails on is never the one you are looking at: an
earlier ramp passed on the reading surface and measured 2.92:1 on
`surface-sunken`, which is the roster. In dark the worst ground flips to
`surface-raised`, because the ramp runs the other way — so all three are
listed in the contract rather than left to be noticed.

## 4. Depth is the surface ramp

`surface` (the sheet) sits on `surface-sunken` (the field: roster, header,
composer), with `surface-raised` for chips and avatars. The sheet-on-field
step is held at **~1.09** in every appearance. It went to 1.035 once, which
is less than half the step and effectively subliminal across 1050px of
field — it looked fine in isolation and wrong on screen.

**Shadow means floating above the page plane** — a modal over its scrim, a
popover over content. One token, `elevation-overlay`. Anything that is part
of the page, however raised, uses the ramp instead.

**`scrim` is the ramp's own end used as a wash**, never a fifth hue and
never `bg-black/40`. It is checked as a *region* luminance drop — ground
plus the text on it — because each theme's scrim is built from the ramp end
that contrasts with what is behind it, so a single-surface check reports
1.00× for a wash that works perfectly. A WCAG ratio is the wrong instrument
here too: its flare term reports 1.10:1 for a region that visibly lost half
its light.

## 5. Radius by role

| Token | For |
|---|---|
| `radius-control` | Things you press or type into: buttons, inputs, chips |
| `radius-card` | Panels and the dispatch card |
| `radius-pill` | Avatars, badges |
| `radius-sharp` | Full-bleed rows and dividers |

`rounded-md` cannot be wrong because it says nothing. `radius-control` can.

The exception: rendered message content (code spans, code blocks) uses
`em`-relative radii, because a code span's corner should scale with the
reader's text. **The radius scale is for chrome.**

## 6. Motion is almost none

Two durations — `duration-quick` (120ms) and `duration-settle` (200ms) — and
one easing. They exist so anything added is inside a budget, **not as an
invitation**. Whether a transition is added at all is still a design
question, and the answer is usually no.

## 7. An icon is a glyph that must not grow

Each platform draws from its own system set — **SF Symbols** on iOS,
**Material icons** on Android — and neither is wrapped in a shared
abstraction. A cross-platform icon layer would mean shipping one vendor's
glyphs on the other's hardware, which readers notice even when they cannot
say why.

What is shared is the rule for **when a mark is an icon at all**, and it came
out of a defect rather than a preference:

> A glyph beside text, describing that text, may be text — it should grow
> with the words it belongs to. A glyph alone inside a control of fixed size
> must be an icon, because it must not grow at all.

Android's jump-to-newest was `Text("↓")` in a 40dp floating button: a
body-font arrow, sized by the body text style, that outgrew its own button at
large `fontScale`. The one control on that screen whose entire job is to be a
fixed target in the corner was the one that would not hold its size. It is
`Icon(Icons.Filled.KeyboardArrowDown)` now, 24dp regardless.

The web's disclosure chevron, `⌄`, **is** text and is right to be. It sits
against a label, it means something about that label, and it scales with it.
The same character in the same repository is correct in one place and a
defect in the other, which is why the rule is about the position rather than
the character.

Two consequences worth stating:

- **`material-icons-core`, never `-extended`.** Core carries 49 icons;
  extended carries every Material icon ever drawn and is well known for what
  it adds to a release build. When core lacks a glyph, that is a reason to
  pick a different affordance, not to pull in the larger artifact.
- **The platforms may differ in the glyph, never in the affordance.**
  Jump-to-newest is an arrow on iOS and a chevron on Android, because core
  has no plain down-arrow. The alternative was giving iOS the weaker glyph to
  match a library limit on Android, which is a library deciding the design.
  Differences of this kind belong in `docs/platform-parity.md`.

## 8. Three appearances, two per platform

| | Light | Dark |
|---|---|---|
| Desktop, web, marketing | `light` | `dark` |
| iOS, Android | `paper` | `dark` |

**Paper is what "light" means on a phone.** It is not a user setting, there
is no picker, and nothing on desktop selects it. `dark` is anchored on the
marketing site's own ink (`#151129`) so arriving at the app from the site
does not feel like leaving it.

## 9. Changing a value

1. Edit `design/tokens.toml`.
2. Run `python3 scripts/generate-tokens.py`.
3. Commit the generated files along with the source.

**Never edit a generated file.** CI will catch you — but that is not the
reason. The reason is that the value would then be right on one surface and
wrong on four, which is the exact condition this system was built to end.

If the generator refuses, it is because a contract you did not intend to
break is written down. Read the message; it names the appearance, the role,
and the number it wanted.

## 10. Open

- **The three platforms disagree about when panes split** — web derives 1238
  from its pane widths, `RootView.swift` hardcodes 1000, and Android measures
  its own width. Recorded in the generated `Metrics` on both native
  platforms. Resolving it is a product question, not a token one.
- **Native surfaces do not use the ramp yet.** iOS paints with SwiftUI
  defaults and `Color.secondary`; Android reaches the palette through the
  Material bridge. Adoption is P6.
