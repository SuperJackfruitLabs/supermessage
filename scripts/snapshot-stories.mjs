// Render every Storybook story to a PNG.
//
// The catalogue is read from Storybook's own `index.json` rather than by
// globbing `*.stories.svelte`, so a story that fails to compile is absent
// here too — it cannot quietly stop being covered while its file sits in
// the tree looking fine.
//
// **Determinism is the whole job.** A screenshot gate that flakes gets
// ignored within a week, so everything below is here to remove a source of
// variation, and each one cost a run to find:
//
//   - `reducedMotion` and a `*, *::before { animation: none }` rule, because
//     a third of this catalogue is streaming states with a pulsing caret.
//   - `document.fonts.ready`, because the first frames came out in Times.
//   - A fixed viewport and `deviceScaleFactor: 1`.
//   - UTC and `en-US`: the timeline renders relative timestamps, and the
//     native gates both learned this the expensive way — six Android frames
//     to a timezone, two iOS frames to a locale.
//
// What it deliberately does not do is compare anything. Rendering and
// judging are separate programs so that the judging half is shared with the
// iOS gate instead of reimplemented with a second set of opinions.
import { chromium } from 'playwright'
import { createServer } from 'node:http'
import { readFile, mkdir, rm, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'

const STATIC = 'storybook-static'
const out = process.argv[2]
if (!out) throw new Error('usage: snapshot-stories.mjs <output-dir>')
if (!existsSync(STATIC)) throw new Error(`no ${STATIC} — run \`pnpm storybook:build\` first`)

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.svg': 'image/svg+xml', '.png': 'image/png', '.map': 'application/json' }

// Served from disk rather than pointed at a dev server: a build is a fixed
// artifact, `storybook dev` recompiles on the fly and its first paint races.
const server = createServer(async (req, res) => {
  const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html'
  const file = path.join(STATIC, rel)
  if (!file.startsWith(STATIC)) return res.writeHead(403).end()
  try {
    const body = await readFile(file)
    res.writeHead(200, { 'content-type': TYPES[path.extname(file)] ?? 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404).end()
  }
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const origin = `http://127.0.0.1:${server.address().port}`

const index = JSON.parse(await readFile(path.join(STATIC, 'index.json'), 'utf8'))
const stories = Object.values(index.entries).filter((e) => e.type === 'story')
stories.sort((a, b) => a.id.localeCompare(b.id))

await rm(out, { recursive: true, force: true })
await mkdir(out, { recursive: true })

const browser = await chromium.launch()
const context = await browser.newContext({
  viewport: { width: 900, height: 720 },
  deviceScaleFactor: 1,
  reducedMotion: 'reduce',
  colorScheme: 'light',
  locale: 'en-US',
  timezoneId: 'UTC',
})
// Installed before any Storybook code runs, not after navigation.
//
// `storyFinished` is the only signal that a play function has finished, and
// subscribing after `goto` is a race the shutter wins about half the time —
// the event has already fired and the listener waits forever. An init script
// is in place before the preview bundle evaluates, so the event cannot be
// missed; the poll is for the channel itself, which appears slightly later.
await context.addInitScript(() => {
  window.__finished = []
  window.__threw = []
  const install = () => {
    const channel = window.__STORYBOOK_ADDONS_CHANNEL__
    if (!channel) return false
    channel.on('storyFinished', (event) => window.__finished.push(event ?? {}))
    // A separate event, and not optional. `storyFinished` reports
    // `status: "success"` for a story whose play function threw — the status
    // is about rendering, and rendering did succeed. A play function that
    // blew up on an ambiguous selector was therefore reported as a pass,
    // with the story still showing its pre-interaction state and the gate
    // ready to adopt that as the baseline.
    channel.on('playFunctionThrewException', (error) =>
      window.__threw.push(error?.message ?? error?.name ?? JSON.stringify(error)))
    return true
  }
  if (!install()) {
    const timer = setInterval(() => { if (install()) clearInterval(timer) }, 10)
  }
})
const page = await context.newPage()

let failed = 0
for (const story of stories) {
  await page.goto(`${origin}/iframe.html?id=${encodeURIComponent(story.id)}&viewMode=story`,
    { waitUntil: 'load' })
  // Storybook signals a finished render on the document body. Waiting for
  // this rather than a sleep is the difference between a gate and a coin
  // toss — `#storybook-root` exists long before the story is in it.
  await page.waitForSelector('body.sb-show-main:not(.sb-show-preparing)', { timeout: 20_000 })
    .catch(() => {})
  // A story with a `play` is not finished when it is shown — the interaction
  // that puts it into the state it is named for runs afterwards. Four
  // SearchPanel stories and four MentionMenu stories came out byte-identical
  // because of this, each one a different named state photographed before
  // anything had happened to it.
  await page.waitForFunction(() => window.__finished.length > 0, null, { timeout: 20_000 })
    .catch(() => console.error(`  SLOW  ${story.id} never reported storyFinished`))
  const threw = await page.evaluate(() => window.__threw)
  if (threw.length) {
    console.error(`  PLAY FAILED  ${story.id}\n    ${threw[0].split('\n')[0]}`)
    failed += 1
  }
  await page.waitForFunction(() => document.fonts.status === 'loaded', null, { timeout: 20_000 })
    .catch(() => {})
  await page.addStyleTag({ content: `*, *::before, *::after {
    animation: none !important; transition: none !important;
    caret-color: transparent !important; }` })
  // `.count()` is the wrong question: Storybook ships `#error-message` in
  // every preview document and keeps it hidden, so counting it reported all
  // 83 stories as broken while every frame on disk was correct. Visibility
  // is the only thing that distinguishes a real failure here.
  const errored = await page.locator('#error-message').isVisible()
  if (errored) {
    console.error(`  ERROR ${story.id} rendered Storybook's error screen`)
    failed += 1
  }
  // The viewport, not `#storybook-root`.
  //
  // Shooting the root element seemed obviously right — tight crops, small
  // files — and it silently rendered 13 stories as nothing. `SearchPanel`
  // opens with `fixed inset-0` and `MentionMenu` with `absolute`; both are
  // out of flow, so the root collapses to zero height and an element
  // screenshot of it captures an empty box. They were only caught because
  // the contact sheet flags byte-identical frames, and all thirteen came out
  // identical to the three stories that legitimately render nothing.
  //
  // A component that positions itself against the viewport has to be
  // photographed against the viewport.
  await page.screenshot({ path: path.join(out, `${story.id}.png`) })
}

await writeFile(path.join(out, 'PLATFORM'), `${process.platform}\n`)
await browser.close()
server.close()
console.log(`${stories.length} stories rendered to ${out} (${process.platform})`)
process.exit(failed ? 1 : 0)
