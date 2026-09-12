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
import hashlib
import html
import json
import pathlib
import sys
from collections import defaultdict

out = pathlib.Path(sys.argv[1])
pngs = sorted(out.glob("*.png"))
if not pngs:
    sys.exit("no images")

by_hash = defaultdict(list)
for p in pngs:
    by_hash[hashlib.md5(p.read_bytes()).hexdigest()].append(p)
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
            warn = (
                '<p class="warn">identical to '
                + html.escape(", ".join(label(pathlib.Path(o + ".png"))[1] for o in others))
                + " — blank, or a frame captured before its state landed</p>"
            )
        cards.append(
            f'<figure{" class=dupe" if p in duped else ""}>'
            f'<img loading="lazy" src="{html.escape(p.name)}" alt="{html.escape(name)}">'
            f"<figcaption>{html.escape(name)}{warn}</figcaption></figure>"
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
