# TEMPORARY — measurement for the LCD-text change; removed before merge.
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from png_decode import decode

root = pathlib.Path(sys.argv[1])
def rows(p):
    w, h, ch, r = decode(str(p)); return w, h, ch, r

for mode in ("lcd", "gray"):
    runs = sorted(root.glob(f"{mode}-*"))
    base = runs[0]
    frames_differ, worst, over = set(), 0, {}
    for other in runs[1:]:
        for f in sorted(base.glob("*.png")):
            g = other / f.name
            wa, ha, ca, ra = rows(f); wb, hb, cb, rb = rows(g)
            if (wa, ha) != (wb, hb):
                frames_differ.add(f.name); over[f.name] = 999; continue
            for y in range(ha):
                A, B = ra[y], rb[y]
                if A == B: continue
                for x in range(wa):
                    pa = A[x*ca:x*ca+3]; pb = B[x*cb:x*cb+3]
                    if pa != pb:
                        d = max(abs(u - v) for u, v in zip(pa, pb))
                        frames_differ.add(f.name); worst = max(worst, d)
                        if d > 2: over[f.name] = max(over.get(f.name, 0), d)
    print(f"PROBE {mode}: {len(runs)} renders, frames differing between any two: {len(frames_differ)}, "
          f"largest channel delta: {worst}, frames over tolerance 2: {len(over)}")
    for n, d in sorted(over.items(), key=lambda kv: -kv[1])[:10]:
        print(f"PROBE   {mode} over: {n} (delta {d})")
