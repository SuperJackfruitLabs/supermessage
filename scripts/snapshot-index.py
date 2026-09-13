#!/usr/bin/env python3
"""Write an index for a directory of rendered previews.

One page rather than 43 files, because the point of this project is looking
at them together: the amber rule is "exactly one row in the roster frame",
which is a claim about a page, not about a file.

**Identical images are flagged, and that is the most useful thing here.**
The first run produced three groups of byte-identical PNGs, and every one was
a real defect: two previews whose async seeding had not landed when the
shutter opened, and three that render a `UIViewRepresentable` and came out
blank. Nothing else in this repository could have caught either.
"""
import collections
import hashlib
import html
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from collections import defaultdict

# ── How much of a frame is one flat colour ────────────────────────────────
#
# **Duplicate detection is not enough, and the gap is not small.** It found
# three blank timeline frames only because there were three of them to compare;
# `RoomInfoPanel` has a single preview, snapshots as a bare spinner, and was
# reported as perfectly fine. Any view that loads in a `.task` has the same
# problem, and most of them have one preview each.
#
# So each image is also measured for the share of its pixels taken by the one
# most common colour. This is **a number to look at, not a verdict**: a small
# component on a full-device canvas is legitimately 93% background, so a
# threshold alone would cry wolf on half the catalogue. What it is good for is
# sorting — the frames at the top of that ranking are the ones worth opening.
#
# Decoded here rather than with Pillow because this repository installs no
# Python packages, and a contact sheet is not worth a dependency.


from png_decode import decode


def modal_share(path, step=7):
    w, h, ch, rows = decode(path)
    counts = collections.Counter()
    for y in range(0, h, step):
        row = rows[y]
        for x in range(0, w, step):
            counts[row[x * ch:x * ch + 3]] += 1
    total = sum(counts.values())
    (colour, n), = counts.most_common(1)
    return n / total, len(counts), colour.hex()


def modal_share(path, step=7):
    """The most common colour's share, and how many distinct ones there are."""
    w, h, ch, rows = decode(path)
    counts = collections.Counter()
    for y in range(0, h, step):
        row = rows[y]
        for x in range(0, w, step):
            counts[row[x * ch:x * ch + 3]] += 1
    total = sum(counts.values())
    (_, n), = counts.most_common(1)
    return n / total, len(counts)


out = pathlib.Path(sys.argv[1])
pngs = sorted(out.glob("*.png"))
if not pngs:
    sys.exit("no images")

by_hash = defaultdict(list)
for p in pngs:
    by_hash[hashlib.md5(p.read_bytes()).hexdigest()].append(p)
# Duplicates that are understood, and stay understood.
#
# Both are previews wrapped in `PreviewSeeded`, whose `.task` has not
# completed when the shutter opens — so the captured frame is the state
# *before* the seed lands, which is a real frame the app passes through and
# not the one the preview is named for. They render correctly in Xcode's
# canvas, which waits.
#
# Named here rather than excluded so they still appear on the page, and so a
# *new* duplicate stands out as something to look into rather than being lost
# in a warning nobody reads any more.
KNOWN_UNSEEDED = {
    "Supermessage_ComposerView.swift_Attachment_staged",
    "Supermessage_SpacePillStrip.swift_Three_spaces",
    # Both of NewRoomPanel's previews load their people in a `.task`, so the
    # two capture the same loading frame and differ in nothing.
    "Supermessage_NewRoomPanel.swift_Known_people",
    "Supermessage_NewRoomPanel.swift_Nobody_yet",
}

dupes = {h: ps for h, ps in by_hash.items() if len(ps) > 1}
duped = {p for ps in dupes.values() for p in ps}


def label(p: pathlib.Path) -> tuple[str, str]:
    stem = p.stem.removeprefix("Supermessage_")
    view, _, name = stem.partition(".swift_")
    return view or stem, name.replace("_", " ") or "(unnamed)"


groups = defaultdict(list)
for p in pngs:
    view, name = label(p)
    groups[view].append((name, p))

rows = []
for view in sorted(groups):
    cards = []
    for name, p in sorted(groups[view]):
        warn = ""
        if p in duped:
            others = [q.stem for q in by_hash[hashlib.md5(p.read_bytes()).hexdigest()] if q != p]
            names = ", ".join(label(pathlib.Path(o + ".png"))[1] for o in others)
            known = p.stem in KNOWN_UNSEEDED or any(o in KNOWN_UNSEEDED for o in others)
            note = (
                "the frame before its async seed landed — known, and correct "
                "in Xcode's canvas"
                if known
                else "look into this: a preview showing the same pixels as "
                "another is usually showing neither"
            )
            warn = (
                f'<p class="warn">identical to {html.escape(names)} — {note}</p>'
            )
        share, distinct = modal_share(p)
        # Both conditions, because either alone is wrong: a sparse-but-real
        # screen is 99% background with plenty of colours in its text, and a
        # busy screen can have few distinct colours.
        bare = share > 0.99 and distinct < 60
        if bare:
            warn += (
                '<p class="warn">almost entirely one colour — check this is '
                "the screen and not its loading state</p>"
            )
        cards.append(
            f'<figure{" class=dupe" if (p in duped or bare) else ""}>'
            f'<img loading="lazy" src="{html.escape(p.name)}" alt="{html.escape(name)}">'
            f"<figcaption>{html.escape(name)}"
            f' <span class=ink>{share:.0%} flat · {distinct} colours</span>'
            f"{warn}</figcaption></figure>"
        )
    rows.append(f"<section><h2>{html.escape(view)}</h2><div class=grid>{''.join(cards)}</div></section>")

flagged = sum(len(ps) for ps in dupes.values())
page = f"""<!doctype html>
<meta charset=utf-8>
<title>supermessage — iOS previews</title>
<style>
  :root {{ color-scheme: light dark; --ink: #221c38; --faint: #70688f; --warn: #814904; }}
  body {{ font: 14px/1.5 ui-sans-serif, system-ui, sans-serif; margin: 0; padding: 24px;
         background: #fbfaf7; color: var(--ink); }}
  @media (prefers-color-scheme: dark) {{ body {{ background: #151129; color: #efecf8; }} }}
  h1 {{ font-size: 20px; margin: 0 0 4px; }}
  .lede {{ color: var(--faint); max-width: 62ch; margin: 0 0 28px; }}
  h2 {{ font-size: 15px; margin: 32px 0 10px; font-weight: 600; }}
  .grid {{ display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); }}
  figure {{ margin: 0; }}
  img {{ width: 100%; border: 1px solid rgba(112,104,143,.35); border-radius: 6px;
         background: #fff; display: block; }}
  figcaption {{ font-size: 12px; color: var(--faint); margin-top: 6px; }}
  figure.dupe img {{ border-color: var(--warn); border-width: 2px; }}
  .ink {{ opacity: .65; }}
  .warn {{ color: var(--warn); margin: 4px 0 0; }}
</style>
<h1>iOS previews</h1>
<p class=lede>{len(pngs)} previews, rendered on a simulator from the app's own
<code>#Preview</code> blocks. {flagged} are flagged as byte-identical to another —
on the first run every one of those was a real defect rather than a
coincidence.</p>
{''.join(rows)}
"""
(out / "index.html").write_text(page)
print(f"{len(pngs)} images, {flagged} flagged as identical → {out / 'index.html'}")
