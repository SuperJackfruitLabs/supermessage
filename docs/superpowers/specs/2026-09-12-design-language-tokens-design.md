# The design language, and one source it is generated from

**Status:** design approved 2026-09-12, not yet planned or implemented.
**Scope:** P1 of five. P2–P5 are named in §3 and specced separately.

## 1. The problem

Three themes in this repository each cite
`docs/superpowers/specs/2026-08-13-console-design.md` as their source. None of
them agree.

| | Light ground | Accent (light) | Accent (dark) | Amber |
|---|---|---|---|---|
| `src/app.css` | `#ffffff` cool slate | `#4249c4` indigo | `#8b9bf7` indigo | `#8a4e00` |
| `Theme.swift` / `Theme.kt` | `#f6f4ef` warm paper | `#3f4bb0` indigo | `#7fb4d8` **blue** | `#a8660a` |
| `landing` / `docs-site` | `#151129` near-black | `#9d8ff0` violet | — | `#e8a33d` |

Someone arrives at supermessage.dev (violet on near-black, Bricolage
Grotesque), installs the desktop app (white on cool slate, IBM Plex), then
opens it on their phone (warm paper, New York serif). Three products. Even
amber — the one colour the spec is most emphatic about reserving — is three
different ambers, and the native dark accent is not the same *hue* as its own
light accent.

The vocabularies differ too, not only the values:

- `app.css` has a three-rank text ramp (`content` / `content-muted` /
  `content-faint`) whose spacing was derived against measured contrast.
  **`Theme.swift` and `Theme.kt` have no text-colour roles at all** and fall
  through to system labels, so the hierarchy the typography section is built on
  does not exist on mobile.
- Native has `ok`. Web does not.
- Web has `scrim`, `accent-soft`, `signal-soft`. Native does not.

And the language stops at colour and type. There are **no tokens for spacing,
radius, elevation or motion on any platform**. Radius is ad-hoc Tailwind —
`rounded-md` ×30, `rounded-full` ×19, `rounded-lg` ×5 — with no rule saying
which means what.

The root cause is not carelessness. It is that the spec is a *document three
codebases read and re-derived by eye*. Nothing mechanical could tell them they
had drifted.

## 2. Decisions

1. **One palette — "D, violet measured" (§4)** — in three appearances: Light,
   Dark, Paper.
2. **One role vocabulary of sixteen colour roles**, every one defined in every
   appearance. A missing role is a generator failure, not a fallback.
3. **The palette is generated, not transcribed.** `design/tokens.toml` plus
   `scripts/generate-tokens.py` emit all five targets; the generated files are
   committed and CI fails on drift.
4. **Every colour carries the contrast contract it must satisfy**, asserted by
   the generator before it writes anything.
5. **Paper is what "light" means on a phone.** No appearance picker.
6. **Dynamic Type is not flattened.** Type roles carry both a web size and a
   native text style.

## 3. Decomposition

This spec is P1. The rest are separately specced, in this order.

| | Project | Why here |
|---|---|---|
| **P1** | **Token source and generator** | *This document.* Everything else consumes it. |
| P2 | Extraction, harness, inventory | Split `Timeline.svelte` (2,609 lines) and `+page.svelte` (1,047); Storybook; `#Preview` / `@Preview`; the parity table. |
| P3 | Icon system | Needs P1's sizing and colour, P2 to review the result. |
| P4 | Native accessibility | A per-component pass; P2 is what makes components exist. |
| P5 | i18n groundwork | Largest; wants the component set stable first. |

P3, P4 and P5 all touch the same component files. P1 and P2 first means each
component is opened once, with tokens, a harness and an inventory already in
place.

**P2's Storybook half is exposed to `docs/tech-stack.md` decision 5** (the
desktop UI framework, reopened 2026-08-30). If the Svelte layer is replaced,
Storybook goes with it; the extraction, the story states and the native
previews survive. P1 is unaffected either way — a token source outlives its
renderers, which is part of why it comes first.

### What P1 changes for a user

D replaces both existing product palettes. Desktop moves from white/indigo to
violet. iOS and Android keep a warm light ground but it shifts, and their dark
accent stops being blue. This is a visible product change, not a refactor.

## 4. The palette

Three literals are inherited from the marketing site so the product and the
site are recognisably one thing: `#151129` (ink), `#9d8ff0` (violet),
`#e8a33d` (amber). **Every other value is solved to a contrast target rather
than chosen.** Hue is 252 throughout, except amber.

### 4.1 Light — desktop default

| Role | Value | Measured |
|---|---|---|
| `surface` | `#fdfcff` | — |
| `surface-sunken` | `#f3f2f9` | 1.088 vs surface |
| `surface-raised` | `#fafafd` | 1.019 vs surface |
| `border` | `#e0deea` | 1.300 vs surface |
| `border-strong` | `#c3bfd4` | 1.752 vs surface |
| `content` | `#221c38` | 15.91 on surface |
| `content-muted` | `#50496d` | 8.17 on surface |
| `content-faint` | `#70688f` | 5.05 surface · 4.64 sunken · 4.96 raised |
| `accent` | `#5b43d4` | 6.42 on surface |
| `accent-content` | `#ffffff` | 6.57 on accent |
| `accent-soft` | `#ebe7fb` | content 13.44 on it |
| `signal` | `#814904` | 7.11 on surface |
| `signal-soft` | `#fcf3e4` | signal 6.61 on it |
| `danger` | `#b4222a` | 6.41 on surface |
| `ok` | `#1d7c59` | 5.04 on surface |
| `scrim` | `rgb(34 28 56 / 0.45)` | luminance drop, see §5.3 |

### 4.2 Dark — built on the site's ink

| Role | Value | Measured |
|---|---|---|
| `surface` | `#1f1838` | — |
| `surface-sunken` | `#151129` | 1.089 vs surface · *the landing page's ink* |
| `surface-raised` | `#251f41` | 1.085 vs surface |
| `border` | `#383254` | 1.406 vs surface |
| `border-strong` | `#534a75` | 2.090 vs surface |
| `content` | `#f4f2fb` | 15.20 on surface · *the landing page's text* |
| `content-muted` | `#c6c2d8` | 9.72 on surface |
| `content-faint` | `#8e86ab` | 4.93 surface · 5.37 sunken · 4.55 raised |
| `accent` | `#9d8ff0` | 6.12 on surface · *the landing page's violet* |
| `accent-content` | `#1a1433` | 6.39 on accent |
| `accent-soft` | `#2b224f` | content 13.11 on it |
| `signal` | `#e8a33d` | *the landing page's amber* |
| `signal-soft` | `#332919` | signal 6.62 on it |
| `danger` | `#ed6e74` | 5.68 on surface |
| `ok` | `#70cdab` | 8.84 on surface |
| `scrim` | `rgb(21 17 41 / 0.70)` | luminance drop, see §5.3 |

### 4.3 Paper — mobile default

| Role | Value | Measured |
|---|---|---|
| `surface` | `#faf8f3` | — |
| `surface-sunken` | `#f2eee6` | 1.090 vs surface |
| `surface-raised` | `#f7f6f1` | 1.020 vs surface |
| `border` | `#e1dbcd` | 1.300 vs surface |
| `border-strong` | `#c5bea8` | 1.750 vs surface |
| `content` | `#22192e` | 15.87 on surface |
| `content-muted` | `#51475e` | 8.20 surface · 7.52 sunken |
| `content-faint` | `#726681` | 5.03 surface · 4.61 sunken · 4.93 raised |
| `accent` | `#5b43d4` | 6.19 on surface |
| `accent-content` | `#ffffff` | 6.57 on accent |
| `accent-soft` | `#ebe7fb` | content 13.92 on it |
| `signal` | `#814904` | 6.85 on surface |
| `signal-soft` | `#f7edda` | signal 6.26 on it |
| `danger` | `#b4222a` | 6.18 on surface |
| `ok` | `#1d7c59` | 4.85 on surface |
| `scrim` | `rgb(34 25 46 / 0.45)` | luminance drop, see §5.3 |

### 4.4 What the ramps hold to

- Three text ranks at roughly **16 / 8 / 5**, so the progression is a
  hierarchy rather than three values that happen to pass.
- **`content-faint` never below 4.5 on any of the three grounds** — not just on
  the reading surface. The earlier cool-slate attempt passed on `surface` and
  measured 2.92:1 on `surface-sunken`.
- **`signal` at ≥6:1 on `signal-soft`.** It carries the 10px
  `AWAITING YOUR DECISION` label, the smallest text in the app on the one
  element the operator must not miss. 4.5 is not enough margin for that job.
- **The `surface : surface-sunken` depth step at ~1.09 in all three
  appearances.** This is the sheet-on-field relationship the whole three-level
  depth story rests on, and it is what silently broke in the current dark theme
  at 1.035.

## 5. The token source

### 5.1 Format

TOML, read with Python's stdlib `tomllib`. No dependency, and it matches the
repo's existing Python generators (`assets/build-icon.py`,
`scripts/e2e-drive.py`).

The deciding factor over JSON is **comments**. The most valuable content in
`app.css` today is not its hex values but the forty lines explaining why
`--color-surface-sunken` is `#090b11` and what broke at `#101218`. That
rationale must survive the move, or a documented palette is traded for an
undocumented one.

### 5.2 The sixteen roles

```
surface · surface-sunken · surface-raised
border · border-strong
content · content-muted · content-faint
accent · accent-content · accent-soft
signal · signal-soft
danger · ok · scrim
```

The union of what the three themes have today. Web contributes the text ramp
native lacks; native contributes `ok`; web contributes `scrim` and the two
`-soft` tints.

**A role absent from any appearance fails the generator.** That one rule is
what would have prevented mobile shipping without a text hierarchy.

### 5.3 Contracts

Every colour carries the contrast contract it must satisfy, and the generator
asserts all of them before writing a byte.

```toml
[appearance.light.color.content-faint]
value = "#70688f"
# The bottom rung is the whole ramp's constraint. It must clear the floor on
# all three grounds, not just the reading surface — the earlier attempt passed
# on `surface` and measured 2.92:1 on `surface-sunken`.
contrast = [
  { against = "surface",        min = 4.5 },
  { against = "surface-sunken", min = 4.5 },
  { against = "surface-raised", min = 4.5 },
]
```

This makes the accessibility floor a build failure rather than a comment. As
things stand, a well-meaning nudge to any of these values breaks a target
silently.

Two contracts are **not** WCAG ratios, because the ratio is the wrong
instrument:

- **Depth step.** `surface : surface-sunken` must land in a band around 1.09.
  It is a perceptual check, not a legibility one.
- **Scrim.** Checked as a **mean-luminance drop**, not a ratio. A WCAG ratio's
  `+0.05` flare term swamps luminances this small and reports 1.10:1 for a
  region that has visibly lost more than half its light.

## 6. The generator and its five targets

`scripts/generate-tokens.py` emits **token files only**. Behaviour files stay
hand-written and consume them.

| Target | Generated | Hand-written consumer |
|---|---|---|
| Desktop | `src/lib/tokens.css` | `src/app.css` — safe areas, `user-select` discipline, the iOS 16px rule |
| iOS | `apple/Supermessage/Generated/ThemeTokens.swift` | `Theme.swift` — `ThemedFace`, `metaFace()`, `nameFace()` |
| Android | `android/app/src/main/kotlin/dev/supermessage/GeneratedThemeTokens.kt` | `Theme.kt` — the composable, the CompositionLocals, the Material bridge |
| Landing | `landing/src/styles/tokens.css` | `index.astro` — its inline `:root` block moves out |
| Docs | `docs-site/src/styles/tokens.css` | `theme.css` — the Starlight variable mapping |

Whole-file generation would delete real work. `Theme.swift`'s `ThemedFace`
modifier exists because the faces were static properties reading
`UITraitCollection.current`, which SwiftUI does not observe — switching
appearance left stale typography on screen until relaunch. That fix is not
regenerable. A tokens-only file is also far more reviewable in the CI diff,
which matters when the diff *is* the gate.

**Rationale travels.** The generator emits TOML comments as comments in each
target. The reason `content-faint` is `#70688f` should be readable in
`Theme.kt`, not only in the source — otherwise the next person re-derives it,
which is how this situation arose.

### 6.1 Binding appearances to platforms

- Desktop and web: `light` ↔ `dark`, following the OS.
- iOS and Android: `paper` ↔ `dark`, following the OS.

Paper is *what light means on a phone*. No `@AppStorage`, no picker, no new
setting — `dynamic()` in `Theme.swift` only knows `userInterfaceStyle`, and
this binding means it does not need to know more. A user-facing chooser
remains available later.

On web, `paper` is emitted under `[data-appearance="paper"]` so the generator
stays uniform across targets, but nothing selects it by default.

Per the CSS rule the repo already follows: no colour gets its only definition
inside a media query. `:root` carries the complete light palette; the dark
block redefines only what changes.

### 6.2 Type, without flattening Dynamic Type

This is the one place a naive generator does real damage. iOS gets Dynamic
Type free today because `Theme.swift` uses `Font.system(.body, design:
.serif)` — a text *style*, not a size. A generator emitting `15.0` there costs
every native user their text scaling.

Each type role therefore carries both expressions:

```toml
[type.body]
family  = "serif"
web     = { size = "0.9375rem", line_height = 1.62, weight = 400 }
ios     = { style = "body" }        # Font.system(.body, design: .serif)
android = { style = "bodyLarge" }   # scales with sp
```

Web gets fixed rem; native gets platform text styles. Same role, same meaning,
each platform's accessibility intact.

The roles are the existing scale: `label`, `meta`, `ui`, `ui-lg`, `avatar`,
`body`, `body-own`.

### 6.3 CI

One job: run the generator, fail on `git diff --quiet` across all five
outputs, with an `::error::` naming the script to run and commit. This is
byte-for-byte the pattern the two UniFFI binding jobs in `ci.yml` already use,
chosen because the repo has twice been bitten by stale generated files that
surfaced as runtime mysteries rather than build failures.

## 7. The scales beyond colour

**Radius, named by role.** Four tokens, taking the values already in use so
this renames rather than redesigns:

| Token | Value | Used by | Replaces |
|---|---|---|---|
| `radius-control` | 6px | buttons, inputs, chips | `rounded-md` (×30) |
| `radius-card` | 8px | the dispatch card, panels | `rounded-lg` (×5) |
| `radius-pill` | 9999px | avatars, badges | `rounded-full` (×19) |
| `radius-sharp` | 0 | full-bleed rows, dividers | bare edges |

`rounded-md` cannot be wrong because it says nothing; `radius-control` can. It
maps directly onto `RoundedCornerShape` and `.cornerRadius`, which is where
those values would otherwise be guessed a fourth time.

**Elevation: the ramp, not the shadow.** Depth is the three-level surface ramp
plus a hairline. Web currently uses `shadow-lg` ×5 and `shadow-sm` ×1
(untokenized Tailwind defaults) and native uses no shadow or elevation at all.
One token for the single case that earns it — an element floating over the
scrim:

| Token | Web | iOS | Android |
|---|---|---|---|
| `elevation-overlay` | `0 8px 24px rgb(0 0 0 / 0.18)` | `.shadow(radius: 12, y: 4)` | `8.dp` |

Native gains it — a modal on iOS and Android currently sits on the page with
nothing lifting it off. Everything else keeps depth through surface level, and
the five other Tailwind shadows on web go away.

**Motion stays tiny, but becomes nameable.**

| Token | Value |
|---|---|
| `duration-quick` | 120ms |
| `duration-settle` | 200ms |
| `easing-standard` | `cubic-bezier(0.2, 0, 0, 1)` |

The reduced-motion block in `app.css` is currently the *only* motion policy,
so any transition anyone adds is unbudgeted. These three are the budget; spec
§8's "almost none, deliberately" still governs whether a transition is added
at all.

**Spacing: declare the 4px scale, do not rename it.** Tailwind's numeric scale
works; semantic spacing names would be over-engineering. The scale is the
contract.

**Layout constants, and the breakpoint derived from them.** `+page.svelte`
carries a comment deriving `1238 = 288 (roster) + 320 (panel) + 630 (sheet)` —
and then writes `1238` as a bare literal **ten times**, with `1293` alongside
it. Change the roster width and the breakpoint is silently wrong in ten places
while the comment still claims it is derived.

So `roster-width`, `panel-width` and `sheet-width` become tokens and the
generator **computes** the breakpoints. The arithmetic in that comment becomes
the code that emits the value.

This also surfaces, without resolving, that the three platforms disagree on
when panes split: web says 1238, `RootView.swift` hardcodes
`threeColumnWidth = 1_000`, and Android measures its own width. Three
independent answers to one product question. P1 makes the disagreement visible
in one file; §10 records it as open.

## 8. The rules document

`docs/design-language.md` carries what values cannot: amber means a pending
decision and nothing else; serif is what an agent wrote, sans what the
operator wrote, mono is data; which radius means what; shadow means floating
over a scrim.

It supersedes `2026-08-13-console-design.md` as the authority. That document
stays in the repository as the record of how the language was arrived at — it
is the source of most of the reasoning above — but it is desktop-web-shaped,
and being a document three codebases read and re-derived is the failure this
project exists to fix.

## 9. Verification

This repository's most-repeated defect is a test that has never failed —
four separate times, each a plausible-looking assertion that passed against a
deliberately broken implementation. The plan is written around falsification.

**Every contract is mutation-proven.** Asserting that `content-faint` clears
4.5 on all three grounds is worth nothing until the value has been lightened
in `tokens.toml`, the generator watched to refuse, the value restored, and the
observation recorded. The same for role completeness (delete `ok` from
`paper`, confirm failure), the depth-step band, and the scrim check. Sixteen
roles × three appearances is too many to mutate exhaustively, so the
implementation plan names which mutations are actually run rather than
claiming all of them.

**The scrim check is the one to watch.** A WCAG ratio reports 1.10:1 for a
region that visibly lost half its light, so a test written the obvious way
passes against a broken scrim. It needs the mean-luminance instrument, and it
needs proving against a deliberately useless scrim value.

**Golden files per target.** Five committed expected outputs, so a generator
change that silently reshapes `Theme.kt` appears as a diff in review rather
than as a compile error three commits later.

**Visual verification, and its constraint.** `scripts/e2e-drive.py` — the
harness that caught the "opening a room shows one message" bug that
code-reading missed — is Linux and Windows only, because macOS has no
WKWebView driver. Development is currently on macOS.

The viable path is the MCP bridge the repo already carries:
`@hypothesi/tauri-plugin-mcp-bridge` and the `pnpm tauri:mcp` script, which
can screenshot the running webview. Composite-checking the palette in the real
app matters because the existing dark-theme depth bug measured correctly in
isolation and still failed on screen across 1050px of field.

For native: iOS builds locally; Android runs `scripts/android-ci-parity.sh`
before its suites, so a layout difference is not discovered in CI.

**Done, for P1:** `design/tokens.toml` and the generator exist; all five
targets are regenerated and committed; CI fails on drift; every named contract
has been mutated and seen to fail; and the three apps have been looked at, not
only built.

## 10. Open questions

Recorded, deliberately not resolved here.

- **Pane-split thresholds disagree across platforms** (§7): web 1238, iOS
  1000, Android measured. P1 makes this visible; deciding it is a product
  question, not a token one.
- **Typeface strategy is not unified and may not need to be.** Web bundles IBM
  Plex and Source Serif 4; native uses system faces so Dynamic Type comes
  free; the marketing site uses Bricolage Grotesque for display. The
  *structural* rule (serif/sans/mono by meaning) is shared, and §6.2 keeps
  each platform's accessibility. Whether the faces themselves should converge
  is out of scope for P1.
- **`docs/tech-stack.md` decision 5** (desktop UI framework) is unaffected by
  P1 and is not addressed by it.

## 11. Non-goals for P1

- Component extraction, Storybook, native previews — P2.
- Icons — P3. Web has zero SVGs, Android has zero icons, iOS has 25 SF
  Symbols; that is a real divergence and it is not this project.
- Native accessibility labels and identifiers — P4.
- i18n — P5.
- Redesigning any screen. P1 changes what colours and measurements the
  existing screens use, not what is on them.
