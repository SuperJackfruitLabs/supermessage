# The web's visual gate

83 stories, rendered headlessly and compared to a committed baseline. The
third of three: iOS has `snapshot-previews.sh`, Android has Roborazzi, and
until now the web had 83 stories nobody was comparing to anything.

    ./scripts/snapshot-stories.sh            # verify
    ./scripts/snapshot-stories.sh --record   # replace the baseline (Linux only)

## 1. What it found on the first run

Twenty of the 83 frames were byte-identical to another frame. Six of those
were legitimate. The other fourteen were defects, and none of them is visible
by reading the story files — every one reads correctly:

| Stories | What was wrong |
|---|---|
| `MentionMenu` ×4 | `absolute bottom-full` with no positioned ancestor resolves against the initial containing block, so the menu rendered above the top of the viewport. Four distinct states, four blank frames. |
| `SearchPanel` ×4 | The stories differ only in what `onSearch` returns, and `onSearch` fires on submit. Nothing submitted, so all four were the same empty panel — including "With hits". |
| `NewRoomPanel` ×2 | Same shape: `onCreate` never ran, so "Create fails" was the untouched form. |
| `SpacesRail` ×2 | "Many spaces" and "All rooms selected" passed identical args. `railEntries` prepends the All-rooms entry with a null `spaceId` and the rail selects on `entry.spaceId === selectedId`, so `selectedId: null` **is** All-rooms selected. Two names for one picture. |

Three more came out of fixing those, each one a thing the catalogue had been
quietly getting wrong:

  - **`layout: "centered"` is global**, and it wraps every story in a
    shrink-to-fit box. Anything that sizes itself from its parent gets a
    parent of zero width. `MentionMenu` needed `layout: "fullscreen"`.
  - **Tailwind v4 does not scan `.stories.svelte`.** An arbitrary-value class
    used only in a story — `h-[22rem]` — is never generated, so the element
    silently has no height. Classes that also appear in a component are fine.
  - **`SearchPanel` labelled its dialog and its input both "Search
    messages"**, which `getByLabelText` refuses as ambiguous and a screen
    reader reads as two things with one name. The dialog is "Search" now.

## 2. Why the baseline is Linux

Chromium does not rasterize text identically on macOS and Linux. Same Skia,
but CoreText and FreeType disagree about glyph positions below the pixel, and
the typeface is not the variable — IBM Plex and Source Serif ship in the
bundle through `@fontsource`, so both platforms draw the same outlines.

A tolerance was the obvious fix and is the wrong one: the threshold that
absorbs a rasterizer also absorbs a one-pixel shift, which is the regression
class the gate exists for. Docker is the usual correct answer and was ruled
out on 3.4GB of free disk against a 2GB image. So one platform owns the
baseline, and it is the one the gate runs on unattended.

`scripts/snapshot-stories.sh --record` **refuses to run on macOS**, and the
comparison skips rather than fails there. Rendering still works, which is the
half a Mac needs: build the catalogue, open `.snapshots-web/index.html`, look.

Re-record through the `Record the story baseline` workflow, which runs
`--record` on ubuntu-24.04 and uploads the result to commit.

## 3. Determinism

Everything the renderer does beyond "take a screenshot" is there to remove a
source of variation:

  - `reducedMotion: 'reduce'` plus a blanket `animation/transition: none`,
    because a third of this catalogue is streaming states with a pulsing
    caret.
  - `document.fonts.ready` — the first frames came out in Times.
  - UTC and `en-US`. Both native gates learned this the expensive way: six
    Android frames went to a timezone and two iOS frames to a locale.
  - Waiting on Storybook's `storyFinished`, not a sleep.

The result is that the web needs **no unstable list at all**, where iOS needs
seven exclusions and Android eight. Playwright can be told to hold still;
SwiftUI and Compose cannot.

It does need one thing they do not. Two runs of all 83 came out
byte-identical, which looked like the end of the story; four runs did not.
Three frames disagreed, by 7 to 25 pixels, every one of them on a rounded
corner, and none by more than 2 of 255 on any channel — Chromium does not
rasterize a curve bit-reproducibly. **Two runs agreeing is not evidence of
determinism**, which is exactly what the iOS unstable list learned by
rendering five times.

So the comparison allows a per-channel difference of 2, and that number is
measured rather than tuned: it was set to 1 first, on the strength of a few
printed deltas, and changed nothing — the three frames still disagreed.
Excluding them instead would have been the smaller change and the wrong one,
because the cause is curves rather than those three stories, and the list
would have grown each run until the gate covered nothing.

The bound is narrow enough to be worth stating in both directions. A padding
change of 4px — `py-1` to `py-2` on the mention menu — moves 9,301 pixels in
the smallest affected frame, and left the other 79 stories green. What ±2
does hide is a colour nudged by one or two parts in 255, and the contrast
contracts in `scripts/tests/` are the better place to catch that anyway.

One trap worth naming: `storyFinished` reports `status: "success"` for a
story whose **play function threw** — the status is about rendering, and
rendering did succeed. A play function that died on an ambiguous selector was
reported as a pass, with the story showing its pre-interaction state and the
gate ready to adopt that as the baseline. The renderer listens for
`playFunctionThrewException` separately, and that listener immediately found
two real failures the status check had called green.

## 4. The hole that is still open

**Nothing in this catalogue can render markdown.** `RichText` gets its blocks
from `richBlocksFromMarkdown`, which is `invoke("rich_blocks_from_markdown")`
— a call into the Rust core. Storybook has no core behind it, so the promise
never settles and every component that renders a message body shows an empty
space where the body goes.

It surfaced through `AgentReasoning`, whose two streaming stories are
identical: opening one shows an empty panel rather than the reasoning.

The fix is a preview-level stub returning `RichBlock[]` that the real core
produced — recorded output, not a parser. Writing a markdown parser for the
catalogue would put a second parser in a repository whose first rule is that
the app parses nothing, and it would show blocks the app cannot produce. For
the same reason `preview.ts` refuses to fake the appearance: a convincing
catalogue that is wrong is worse than one with a gap in it.
