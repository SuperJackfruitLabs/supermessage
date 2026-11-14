// node --test scripts/tests/test_release_assets.mjs
//
// What a `v*` tag releases. The shared release workflow ships every artifact of main CI's run
// that `artifact` in .github/release-config.json matches, and .github/release/check.sh refuses a
// tag whose run lacks one of them. These pin the three to each other: the artifacts
// .github/workflows/ci.yml uploads for a release, the pattern that fetches them, and the list the
// check demands.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const CI = readFileSync(join(ROOT, '.github/workflows/ci.yml'), 'utf8');
const CONFIG = JSON.parse(readFileSync(join(ROOT, '.github/release-config.json'), 'utf8'));
const CHECK = join(ROOT, '.github/release/check.sh');
const SHA = 'c'.repeat(40);

/** One job of ci.yml, as text. */
const job = (name) => {
  const start = CI.indexOf(`\n  ${name}:\n`);
  assert.ok(start > -1, `job ${name} is missing`);
  const rest = CI.slice(start + 1);
  const end = rest.slice(1).search(/\n {2}[a-z-]+:\n/);
  return end === -1 ? rest : rest.slice(0, end + 1);
};

/** The desktop matrix's targets. */
const desktopTargets = () => [...job('desktop').matchAll(/^ {12}target: (\S+)$/gm)].map((m) => m[1]);

/** Every artifact name ci.yml uploads, with `${{ matrix.target }}` expanded and the sha filled in. */
const uploadedNames = () =>
  [...CI.matchAll(/uses: actions\/upload-artifact@\S+\n(?: {8}.*\n)*? {10}name: (.+)\n/g)].flatMap(([, name]) => {
    const names = name.includes('${{ matrix.target }}') ? desktopTargets().map((t) => name.replace('${{ matrix.target }}', t)) : [name];
    return names.map((n) => n.replace('${{ github.sha }}', SHA));
  });

/** The kit's pattern as download-artifact reads it: `*` is any run of characters. */
const matches = (pattern, name) =>
  new RegExp(`^${pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*')}$`).test(name);

const RELEASED = () => [...desktopTargets().map((t) => `desktop-${t}-${SHA}`), `android-apks-${SHA}`].sort();

void describe('the release ships the desktop installers and the Android APKs', () => {
  void test('the desktop matrix still has its three targets', () => {
    assert.deepEqual(desktopTargets(), ['universal-apple-darwin', 'x86_64-unknown-linux-gnu', 'x86_64-pc-windows-msvc']);
  });

  void test("each desktop bundle is under the root workspace's target/, where tauri writes it", () => {
    assert.match(readFileSync(join(ROOT, 'Cargo.toml'), 'utf8'), /^\[workspace\]\nmembers = \[[^\]]*"src-tauri"/m);
    const bundles = [...job('desktop').matchAll(/^ {12}bundle: (\S+)$/gm)].map((m) => m[1]);
    assert.deepEqual(bundles, ['target/universal-apple-darwin/release/bundle', 'target/release/bundle', 'target/release/bundle']);
  });

  void test("the config's pattern fetches exactly the release artifacts, and nothing else ci.yml uploads", () => {
    const pattern = CONFIG.artifact.replaceAll('{sha}', SHA);
    assert.deepEqual(uploadedNames().filter((name) => matches(pattern, name)).sort(), RELEASED());
  });

  void test('android-apks uploads the four per-ABI APKs, named as the releases name them, for 30 days', () => {
    const body = job('android-apks');
    assert.ok(body.includes('          for abi in arm64-v8a armeabi-v7a x86 x86_64; do\n'));
    assert.ok(body.includes('"android/app/build/outputs/apk/debug/app-$abi-debug.apk"'));
    assert.ok(body.includes('"android-dist/supermessage_${version}_android-$abi-debug.apk"'));
    assert.ok(body.includes('./gradlew -q :app:printVersion'));
    assert.ok(body.includes('          name: android-apks-${{ github.sha }}\n          path: android-dist/\n          if-no-files-found: error\n          retention-days: 30\n'));
  });

  void test('the kit accepts the config and its check hook', () => {
    const run = spawnSync('sh', ['.github/release/kit.sh', 'config', '.github/release-config.json'], { cwd: ROOT, encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /hook: \.github\/release\/check\.sh/);
  });
});

void describe('check.sh refuses a run that lacks a release artifact', () => {
  const dir = mkdtempSync(join(tmpdir(), 'release-check-'));
  after(() => rmSync(dir, { recursive: true, force: true }));
  // A gh that answers the artifacts API from a fixture, through the same jq filter it is given.
  writeFileSync(
    join(dir, 'gh'),
    '#!/bin/sh\nwhile [ $# -gt 0 ]; do case "$1" in -q) shift; q=$1 ;; esac; shift; done\njq -r "$q" "$FIXTURE"\n',
  );
  chmodSync(join(dir, 'gh'), 0o755);

  const check = (artifacts) => {
    const fixture = join(dir, 'artifacts.json');
    writeFileSync(fixture, JSON.stringify({ total_count: artifacts.length, artifacts }));
    return spawnSync('sh', [CHECK], {
      encoding: 'utf8',
      env: { PATH: `${dir}:${process.env.PATH ?? ''}`, FIXTURE: fixture, CI_RUN_ID: '1', RELEASE_SHA: SHA, GITHUB_REPOSITORY: 'o/r' },
    });
  };
  const live = (name) => ({ name, expired: false });
  const all = () => [...RELEASED().map(live), live('android-jnilibs-all'), live('supermessage-debug-universal')];

  void test('every release artifact present: passes', () => {
    const run = check(all());
    assert.equal(run.status, 0, run.stdout + run.stderr);
  });

  for (const name of RELEASED()) {
    void test(`${name.replace(SHA, '<sha>')} missing: fails, naming it`, () => {
      const run = check(all().filter((a) => a.name !== name));
      assert.equal(run.status, 1);
      assert.ok(run.stdout.includes(name), run.stdout);
    });
    void test(`${name.replace(SHA, '<sha>')} expired: fails`, () => {
      assert.equal(check(all().map((a) => (a.name === name ? { ...a, expired: true } : a))).status, 1);
    });
  }

  void test("another commit's artifacts do not count", () => {
    assert.equal(check(RELEASED().map((n) => live(n.replace(SHA, 'd'.repeat(40))))).status, 1);
  });
});
