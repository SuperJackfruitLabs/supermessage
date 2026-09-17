# The link preview image

`public/og.png` is what a link to supermessage.dev shows when it is pasted into Slack, X or
iMessage, and `docs-site/public/og.png` is the same file for docs.supermessage.dev. It is a
1200×630 screenshot of `og.html`, which lives here rather than in `public/` so it is not
published as a page of its own.

Its colours and mark come from the generated `../src/styles/tokens.css` and
`../public/favicon.svg`, so after changing the headline, the palette or the mark, regenerate
those first (`python3 scripts/generate-tokens.py` from the repo root), then serve this
project's directory and screenshot the page at exactly 1200×630 with any headless Chromium:

```bash
# from landing/
python3 -m http.server 8000 &
npx playwright screenshot --color-scheme=dark --viewport-size=1200,630 \
  --wait-for-timeout=1500 "http://localhost:8000/og/og.html" public/og.png
cp public/og.png ../docs-site/public/og.png
```

`--color-scheme=dark` matters: the tokens follow the renderer's preference, and a link
preview should be the brand's ink ground, not whatever the machine that rendered it prefers.
