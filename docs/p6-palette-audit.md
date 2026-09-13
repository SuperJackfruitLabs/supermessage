# P6: where native reaches past the palette

**Written 2026-09-13**, after the visual gate made the evidence viewable.
Counts are from the tree at that date and every command that produced one is
given, so a stale number can be caught rather than trusted.

This is an **audit**. Nothing is repainted here.

## 1. The finding

`design/tokens.toml` asserts contrast contracts and **fails the build** when
one is not met — `content-faint` must clear 4.5:1 against all three grounds,
not merely the reading surface. Ten roles carry such a contract:

```
accent-content · accent-soft · content · content-faint · content-muted
danger · ok · scrim · signal · surface-sunken
```

**iOS uses three of them.** `signal`, `danger` and `ok`. The other seven have
zero call sites — including **all three content roles**, which are the ones
whose contracts exist to guarantee that text is legible.

So the generator proves `content-faint` is legible on three grounds, the
build fails if it is not, and the app then draws that text in `.secondary` —
Apple's grey, which no contract in this repository has ever measured.

## 2. iOS, by the numbers

```bash
for role in surface content contentMuted contentFaint accent signal …; do
  grep -rho "Theme\.$role\b" apple/Supermessage --include='*.swift' | wc -l
done
```

| Token role | iOS call sites |
|---|---|
| `accent` | 18 |
| `surface` | 10 |
| `signal` | 10 |
| `danger` | 7 |
| `ok` | 1 |
| `surfaceSunken`, `surfaceRaised`, `border`, `borderStrong`, `content`, `contentMuted`, `contentFaint`, `accentContent`, `accentSoft`, `scrim` | **0** |

Against what it reaches for instead:

| System colour | Uses |
|---|---|
| `.secondary` | 50 |
| `.tertiary` | 17 |
| `.quaternary` | 9 |
| `.tint(` | 4 |
| `separator` | 4 |
| `.primary` | 3 |
| **total** | **87** |

**87 system-colour uses against 46 token uses** — and the split is not
arbitrary. Tokens are used for *accents and states*; the system is used for
**all body text and every depth surface**. The palette colours the exceptions
and Apple colours the page.

Where it concentrates, which is also where to start:

| File | System-colour uses |
|---|---|
| `Timeline/TimelineRowView.swift` | 17 |
| `Timeline/LiveTurnView.swift` | 8 |
| `Timeline/DecisionCard.swift` | 8 |
| `Composer/ComposerView.swift` | 7 |
| `Rooms/RoomRowView.swift` | 6 |
| `Rooms/RoomListView.swift` | 6 |

Visible in the rendered frames: `RoomInfoPanel`'s `Done` is **system blue**,
not `accent` `#5b43d4`.

## 3. Android, by the numbers

A different shape of problem, and a much smaller one.

| | Uses |
|---|---|
| `MaterialTheme.colorScheme.*` | 95 |
| `SupermessageTheme.colors.*` | 5 |

19:1 through the Material bridge — but the bridge is **not** a leak. `Theme.kt`
maps fourteen Material roles onto token values, so `colorScheme.outline` *is*
`colors.border`. Android's colours are largely right by construction, which is
why its rendered frames look correct.

Two real leaks, both narrow:

- **`secondaryContainer` and `onSecondaryContainer` are used but never
  mapped**, so those two call sites take Material's own defaults rather than
  anything from `design/tokens.toml`.
- **Anything Material draws for itself** — ripples, elevation tints, indicator
  colours — derives from its scheme rather than from the palette, and no
  contract covers those derivations.

## 4. What this means for the contracts

The contrast work in P1 was real: `scripts/tokens/contrast.py` measures
ratios, and `region_luminance_drop` was written because measuring the scrim
against a ground scored 1.00× and said nothing. Those measurements are
asserted before emission.

**They govern the web.** On iOS they govern five roles out of sixteen and no
text at all. Stated plainly so that nobody reads "contrast is asserted at
build time" as "the iOS app meets it".

## 5. Proposed order

Smallest blast radius first, and every step is now visible as an image diff on
Android — 41 reference frames, 24 seconds, failing on a changed pixel.

1. **Android's two unmapped roles.** Two call sites. The gate will show
   exactly which frames move.
2. **`RoomInfoPanel`'s system-blue `Done`**, and the other `.tint(` sites.
   Four uses, unambiguous, and one of them is already photographed.
3. **iOS depth**: `surfaceSunken` / `surfaceRaised` / `border`, which have no
   call sites at all today. This is what "the three-level depth ramp" means
   and it currently means nothing on iOS.
4. **iOS text**: the 79 `.primary`/`.secondary`/`.tertiary`/`.quaternary`
   uses become `content` / `contentMuted` / `contentFaint`. The largest step,
   the one the contracts were written for, and the one with **no visual gate
   behind it** — iOS has no verify mode.
5. **`PersonDto`'s glyph**, carried over from the fixture work: the name still
   holds its glyph while `TimelineRow`'s no longer does, and neither person
   row has a disc to move it into.

Step 4 is the one to be careful about. It is most of the value and it is the
only step where nothing can check the result but a person looking at it.

## 6. What an audit cannot tell you

- **Whether the result looks better.** These are counts. `.secondary` on
  paper may be a perfectly good grey; the argument for changing it is that it
  is unmeasured and unowned, not that it is ugly.
- **What Material derives internally.** Ripples and elevation overlays are
  computed from the scheme, and nothing here inspects them.
- **Whether iOS's frames are right at all.** 12 of its 52 previews do not
  render and `.task` panels capture loading states, so the iOS evidence in
  this document is weaker than the Android evidence throughout.
