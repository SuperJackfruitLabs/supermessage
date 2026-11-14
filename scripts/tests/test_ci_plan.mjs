// node --test scripts/tests/test_ci_plan.mjs
//
// The docs-only CI plan (scripts/ci-plan.mjs, .github/workflows/ci.yml): the classifier fails
// closed, and the `check` aggregate passes only for the graph its plan selected.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { JOBS, PRODUCT_INPUTS, aggregate, classify, gitIn, isDocsPath, planFor } from '../ci-plan.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const SCRIPT = join(ROOT, 'scripts/ci-plan.mjs');
const SHA_A = 'a'.repeat(40);
const SHA_B = 'b'.repeat(40);

/** A git that answers merge-base with SHA_A and the diff with `paths`. */
const fakeGit = (paths) => (args) =>
  args[0] === 'merge-base' ? { ok: true, stdout: `${SHA_A}\n` } : { ok: true, stdout: paths.map((p) => `${p}\0`).join('') };
const noMergeBase = (args) => (args[0] === 'merge-base' ? { ok: false, error: 'exit 1' } : { ok: true, stdout: 'README.md\0' });
const blankMergeBase = (args) => (args[0] === 'merge-base' ? { ok: true, stdout: '\n' } : { ok: true, stdout: 'README.md\0' });
const failedDiff = (args) => (args[0] === 'merge-base' ? { ok: true, stdout: SHA_A } : { ok: false, error: 'spawnSync git ENOBUFS' });
const throwingGit = () => {
  throw new Error('boom');
};
const pr = (git, base = SHA_A, head = SHA_B) => planFor({ event: 'pull_request', base, head, git });
const cli = (args, env) =>
  spawnSync(process.execPath, [SCRIPT, ...args], { cwd: ROOT, encoding: 'utf8', env: { PATH: process.env.PATH ?? '', ...env } });

void describe('classifier', () => {
  for (const [name, paths] of [
    ['README.md only', ['README.md']],
    ['bare README', ['README']],
    ['README + docs/** + docs-site/**', ['README', 'docs/matrix-events.md', 'docs-site/src/content/docs/index.mdx', 'docs-site/astro.config.mjs']],
    ['docs-site styles other than the generated tokens', ['docs-site/src/styles/custom.css']],
  ]) {
    void test(`docs-only: ${String(name)}`, () => assert.equal(pr(fakeGit(paths)).docsOnly, true));
  }

  for (const [name, paths] of [
    ['docs + Rust core', ['docs/matrix-events.md', 'crates/supermessage-core/src/lib.rs']],
    ['docs-site + frontend', ['docs-site/src/content/docs/index.mdx', 'src/routes/+page.svelte']],
    ['the generated docs-site tokens (checked by the tokens job)', ['docs-site/src/styles/tokens.css']],
    ['the generated docs-site favicon (checked by the tokens job)', ['docs-site/public/favicon.svg']],
    ['generated tokens with other docs', ['docs-site/public/favicon.svg', 'README.md']],
    ['SwiftUI', ['apple/Supermessage/App.swift']],
    ['Android', ['android/app/build.gradle.kts']],
    ['workflow', ['.github/workflows/ci.yml']],
    ['release config', ['.github/release-config.json']],
    ['the planner itself', ['scripts/ci-plan.mjs']],
    ['root package', ['package.json']],
    ['Cargo lock', ['Cargo.lock']],
    ['pnpm lock', ['pnpm-lock.yaml']],
    ['design tokens source', ['design/tokens.toml']],
    ['generated product artifact (Swift tokens)', ['apple/Supermessage/Generated/ThemeTokens.swift']],
    ['vendored treaty corpus', ['fixtures/ecosystem-identity/identity.json']],
    ['landing', ['landing/src/pages/index.astro']],
    ['unknown path', ['CHANGELOG.md']],
    ['agent notes', ['CLAUDE.md']],
    ['README below another directory', ['android/README.md']],
    ['README lookalike', ['READMEx.md']],
    ['case differs', ['readme.md']],
    ['Docs/ is not docs/', ['Docs/OPERATING.md']],
    ['a file named docs', ['docs']],
    ['the docs/ prefix alone', ['docs/']],
    ['docs-siteX/ is not docs-site/', ['docs-siteX/a.md']],
    ['a README.* that is a directory', ['README.d/x.md']],
  ]) {
    void test(`not docs-only: ${String(name)}`, () => assert.equal(pr(fakeGit(paths)).docsOnly, false));
  }

  void test('an empty diff is not docs-only', () => {
    assert.deepEqual(pr(fakeGit([])), { docsOnly: false, reason: 'the diff is empty' });
    assert.equal(classify([]).docsOnly, false);
  });

  for (const [name, base, head] of [
    ['empty base', '', SHA_B],
    ['zero base', '0'.repeat(40), SHA_B],
    ['empty head', SHA_A, ''],
    ['short sha', 'abc123', SHA_B],
  ]) {
    void test(`an unavailable sha is not docs-only: ${name}`, () => {
      const plan = pr(fakeGit(['README.md']), base, head);
      assert.equal(plan.docsOnly, false);
      assert.match(plan.reason, /unavailable/);
    });
  }

  void test('a missing merge base is not docs-only', () => {
    assert.deepEqual(pr(noMergeBase), { docsOnly: false, reason: 'no merge base: exit 1' });
    assert.equal(pr(blankMergeBase).docsOnly, false);
  });

  void test('a failed or overflowing diff is not docs-only', () => {
    assert.deepEqual(pr(failedDiff), { docsOnly: false, reason: 'the diff failed: spawnSync git ENOBUFS' });
  });

  void test('a classifier error is not docs-only', () => {
    assert.deepEqual(pr(throwingGit), { docsOnly: false, reason: 'the classifier failed: boom' });
  });

  for (const event of ['push', 'schedule', 'workflow_dispatch', 'workflow_call', 'pull_request_target', 'merge_group', '']) {
    void test(`the ${event || 'empty'} event is never docs-only, even for a docs-only diff`, () => {
      assert.equal(planFor({ event, base: SHA_A, head: SHA_B, git: fakeGit(['README.md']) }).docsOnly, false);
    });
  }

  void test('isDocsPath matches only the three families', () => {
    assert.ok(['README', 'README.de.md', 'README.txt', 'docs/a', 'docs-site/a/b'].every(isDocsPath));
    assert.ok(!['README/', 'android/README.md', 'docs', 'docs-site', 'xdocs/a', '.docs/a', ...PRODUCT_INPUTS].some(isDocsPath));
  });
});

void describe('classifier against a real repository', () => {
  let dir = '';
  let base = '';
  const sh = (...args) => {
    const run = spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
    if (run.status !== 0) throw new Error(run.stderr);
    return run.stdout.trim();
  };
  const write = (path, text) => {
    mkdirSync(dirname(join(dir, path)), { recursive: true });
    writeFileSync(join(dir, path), text);
  };
  const commit = (message) => {
    sh('add', '-A');
    sh('commit', '-q', '-m', message);
    return sh('rev-parse', 'HEAD');
  };
  const branch = (name, change) => {
    sh('checkout', '-q', '-B', name, base);
    change();
    return commit(name);
  };
  const plan = (head, from = base) => planFor({ event: 'pull_request', base: from, head, git: gitIn(dir) });

  before(() => {
    dir = mkdtempSync(join(tmpdir(), 'ci-plan-'));
    sh('init', '-q', '-b', 'main');
    sh('config', 'user.email', 'ci@example.invalid');
    sh('config', 'user.name', 'ci');
    sh('config', 'commit.gpgsign', 'false');
    write('README.md', 'readme\n');
    write('docs/guide.md', 'guide\n');
    write('src/lib/app.ts', 'export {};\n');
    base = commit('base');
  });
  after(() => rmSync(dir, { recursive: true, force: true }));

  void test('a docs-only branch is docs-only', () => {
    const head = branch('docs', () => {
      write('README.md', 'readme, edited\n');
      write('docs/new page.md', 'a path with a space\n');
      write('docs-site/index.md', 'site\n');
    });
    assert.deepEqual(plan(head), { docsOnly: true, reason: 'all 3 changed paths are docs' });
  });

  void test('a rename from docs/ to source is not docs-only', () => {
    const head = branch('rename', () => renameSync(join(dir, 'docs/guide.md'), join(dir, 'src/lib/guide.ts')));
    assert.equal(plan(head).docsOnly, false);
  });

  void test('a move from source into docs/ is not docs-only (the old path counts)', () => {
    const head = branch('rename-in', () => renameSync(join(dir, 'src/lib/app.ts'), join(dir, 'docs/app.ts')));
    assert.deepEqual(plan(head), { docsOnly: false, reason: 'src/lib/app.ts is not a docs path' });
  });

  void test('a rename within docs/ stays docs-only', () => {
    const head = branch('rename-docs', () => renameSync(join(dir, 'docs/guide.md'), join(dir, 'docs/moved.md')));
    assert.equal(plan(head).docsOnly, true);
  });

  void test('a large docs-only diff is read whole', () => {
    const head = branch('large', () => {
      for (let i = 0; i < 4000; i++) write(`docs/bulk/page-${i}.md`, `${i}\n`);
    });
    assert.deepEqual(plan(head), { docsOnly: true, reason: 'all 4000 changed paths are docs' });
    const mixed = branch('large-mixed', () => {
      for (let i = 0; i < 4000; i++) write(`docs/bulk/page-${i}.md`, `${i}\n`);
      write('zz-last.ts', 'export {};\n');
    });
    assert.equal(plan(mixed).docsOnly, false);
  });

  void test('a commit with no changes is not docs-only', () => {
    sh('checkout', '-q', '-B', 'empty', base);
    sh('commit', '-q', '--allow-empty', '-m', 'empty');
    assert.deepEqual(plan(sh('rev-parse', 'HEAD')), { docsOnly: false, reason: 'the diff is empty' });
  });

  void test('a base that is not in the clone is not docs-only', () => {
    const head = branch('docs-2', () => write('README.md', 'again\n'));
    assert.match(plan(head, 'c'.repeat(40)).reason, /^no merge base/);
  });

  void test('unrelated histories (no merge base) are not docs-only', () => {
    sh('checkout', '-q', '--orphan', 'orphan');
    sh('rm', '-rqf', '.');
    write('README.md', 'orphan\n');
    assert.match(plan(commit('orphan')).reason, /^no merge base/);
  });
});

/** Every result GitHub gives a job in `needs`, and a job missing from it. */
const RESULTS = ['success', 'failure', 'skipped', 'cancelled', 'missing'];

/** A needs context: `plan` is the changes job's outputs, `results` each judged job's result. */
function needsFor(changes, plan, results) {
  const needs = {};
  if (changes !== 'missing') needs.changes = { result: changes, outputs: plan };
  for (const [name, result] of Object.entries(results)) if (result !== 'missing') needs[name] = { result, outputs: {} };
  return needs;
}

const ALL_FALSE = { frontend: 'false', rust: 'false', ios: 'false', android: 'false', licences: 'false', tokens: 'false', docs: 'false', docs_only: 'false' };
const PLANS = {
  docsOnly: { ...ALL_FALSE, docs: 'true', docs_only: 'true' },
  nothing: ALL_FALSE,
  docsSite: { ...ALL_FALSE, docs: 'true' },
  frontend: { ...ALL_FALSE, frontend: 'true' },
  rustAndMobile: { ...ALL_FALSE, rust: 'true', ios: 'true', android: 'true', licences: 'true' },
  everything: { frontend: 'true', rust: 'true', ios: 'true', android: 'true', licences: 'true', tokens: 'true', docs: 'true', docs_only: 'false' },
};

const bool = (plan) => Object.fromEntries(Object.entries(plan).map(([key, value]) => [key, value === 'true']));

/** What each judged job must report under a plan, written out from the jobs' `if`s. */
const expected = (plan, event) => {
  const p = bool(plan);
  const want = (on) => (on ? 'success' : 'skipped');
  return {
    tokens: want(p.tokens),
    docs: want(p.docs),
    frontend: want(p.frontend),
    rust: want(p.rust),
    ios: want(p.ios),
    'android-libs': want(p.android),
    'android-unit': want(p.android),
    'android-apks': want(p.android && event !== 'pull_request'),
    'android-avd': want(p.android),
    'android-instrumented': want(p.android),
    android: want(p.android),
    licences: want(p.licences),
    'treaty-corpus': want(!p.docs_only),
    'docs-claims': want(p.docs_only),
  };
};

void describe('aggregate truth table', () => {
  void test('it judges every job the plan selects', () => {
    assert.deepEqual(Object.keys(JOBS).sort(), Object.keys(expected(PLANS.everything, 'push')).sort());
  });
  for (const event of ['pull_request', 'push']) {
    for (const [planName, plan] of Object.entries(PLANS)) {
      const valid = !(planName === 'docsOnly' && event !== 'pull_request');
      const want = expected(plan, event);
      void test(`${event} ${planName}: the planned graph ${valid ? 'passes' : 'fails (push cannot be docs-only)'}`, () => {
        assert.equal(aggregate(needsFor('success', plan, want), event).ok, valid);
      });
      void test(`${event} ${planName}: a changes job that did not succeed fails`, () => {
        for (const changes of RESULTS.filter((r) => r !== 'success')) assert.equal(aggregate(needsFor(changes, plan, want), event).ok, false, changes);
      });
      void test(`${event} ${planName}: every other result for every job fails`, () => {
        for (const job of Object.keys(JOBS)) {
          for (const result of RESULTS.filter((r) => r !== want[job])) {
            assert.equal(aggregate(needsFor('success', plan, { ...want, [job]: result }), event).ok, false, `${job}=${result}`);
          }
        }
      });
    }
  }

  void test('a known failing docs fixture fails the docs-only run', () => {
    for (const job of ['docs', 'docs-claims']) {
      const verdict = aggregate(needsFor('success', PLANS.docsOnly, { ...expected(PLANS.docsOnly, 'pull_request'), [job]: 'failure' }), 'pull_request');
      assert.deepEqual(verdict, { ok: false, reason: `${job} is failure, the plan expects success` });
    }
  });

  void test('an unreadable plan output fails', () => {
    for (const name of Object.keys(ALL_FALSE)) {
      for (const value of ['', 'yes', undefined]) {
        const plan = { ...PLANS.frontend, [name]: value };
        if (value === undefined) delete plan[name];
        assert.equal(aggregate(needsFor('success', plan, expected(PLANS.frontend, 'pull_request')), 'pull_request').ok, false, `${name}=${value}`);
      }
    }
  });

  void test('a docs-only plan that also selects other work fails', () => {
    for (const extra of ['frontend', 'rust', 'ios', 'android', 'licences', 'tokens'].map((k) => ({ [k]: 'true' })).concat([{ docs: 'false' }])) {
      const plan = { ...PLANS.docsOnly, ...extra };
      assert.equal(aggregate(needsFor('success', plan, expected(plan, 'pull_request')), 'pull_request').ok, false, JSON.stringify(extra));
    }
  });

  void test('an unreadable needs context fails', () => {
    for (const needs of [undefined, null, '{}']) assert.equal(aggregate(needs, 'pull_request').ok, false);
  });
});

void describe('the CLI', () => {
  void test('aggregate exits 0 only for a passing graph, and 1 for unparseable needs', () => {
    const want = expected(PLANS.docsOnly, 'pull_request');
    const ok = cli(['aggregate'], { EVENT: 'pull_request', NEEDS: JSON.stringify(needsFor('success', PLANS.docsOnly, want)) });
    assert.equal(ok.status, 0, ok.stdout);
    const bad = cli(['aggregate'], { EVENT: 'pull_request', NEEDS: JSON.stringify(needsFor('success', PLANS.docsOnly, { ...want, rust: 'success' })) });
    assert.equal(bad.status, 1);
    assert.equal(cli(['aggregate'], { EVENT: 'push', NEEDS: 'not json' }).status, 1);
  });

  void test('plan writes docs_only=false for a push, to GITHUB_OUTPUT', () => {
    const dir = mkdtempSync(join(tmpdir(), 'ci-plan-out-'));
    const out = join(dir, 'out');
    writeFileSync(out, '');
    assert.equal(cli(['plan'], { EVENT: 'push', GITHUB_OUTPUT: out }).status, 0);
    assert.equal(readFileSync(out, 'utf8'), 'docs_only=false\nreason=push keeps its plan\n');
    rmSync(dir, { recursive: true, force: true });
  });

  void test('an unknown command exits 2', () => assert.equal(cli(['nope'], {}).status, 2));
});

const COMPONENTS = ['FRONTEND', 'RUST', 'IOS', 'ANDROID', 'LICENCES', 'TOKENS', 'DOCS'];

void describe('the workflow keeps its contract', () => {
  const ci = readFileSync(join(ROOT, '.github/workflows/ci.yml'), 'utf8');
  const job = (name) => {
    const start = ci.indexOf(`\n  ${name}:\n`);
    assert.ok(start > -1, `job ${name} is missing`);
    const rest = ci.slice(start + 1);
    const end = rest.slice(1).search(/\n {2}[a-z-]+:\n/);
    return end === -1 ? rest : rest.slice(0, end + 1);
  };

  /** The `plan` step in `changes`, run as the workflow would with the given filter outputs. */
  const shellPlan = (env) => {
    const block = /- id: plan\n(?: {8}.*\n)*? {8}run: \|\n((?: {10}.*\n)+)/.exec(job('changes'));
    assert.ok(block, 'the plan step is missing');
    const script = block[1].split('\n').map((line) => line.slice(10)).join('\n');
    const dir = mkdtempSync(join(tmpdir(), 'ci-plan-shell-'));
    const out = join(dir, 'out');
    writeFileSync(out, '');
    try {
      const run = spawnSync('sh', ['-eu', '-c', script], { encoding: 'utf8', env: { PATH: process.env.PATH ?? '', GITHUB_OUTPUT: out, ...env } });
      assert.equal(run.status, 0, run.stderr);
      return Object.fromEntries(readFileSync(out, 'utf8').trim().split('\n').map((line) => line.split('=')));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  };

  void test('the events are unchanged', () => {
    assert.ok(ci.startsWith('name: CI\n\non:\n  pull_request:\n  push:\n    branches: [main]\n\n'));
  });

  void test('a docs-only pull request runs only the docs lane', () => {
    const filters = Object.fromEntries(COMPONENTS.map((k) => [k, 'true']));
    assert.deepEqual(shellPlan({ ...filters, EVENT: 'pull_request', DOCS_ONLY: 'true' }), { ...ALL_FALSE, docs: 'true', docs_only: 'true' });
  });

  void test('without docs-only the outputs are the filter outputs, unchanged', () => {
    for (let mask = 0; mask < 1 << COMPONENTS.length; mask++) {
      const filters = Object.fromEntries(COMPONENTS.map((k, i) => [k, mask & (1 << i) ? 'true' : 'false']));
      const want = { ...Object.fromEntries(COMPONENTS.map((k) => [k.toLowerCase(), filters[k]])), docs_only: 'false' };
      for (const EVENT of ['pull_request', 'push']) {
        for (const DOCS_ONLY of ['false', '', 'TRUE', ...(EVENT === 'pull_request' ? [] : ['true'])]) {
          assert.deepEqual(shellPlan({ ...filters, EVENT, DOCS_ONLY }), want, JSON.stringify({ filters, EVENT, DOCS_ONLY }));
        }
      }
    }
  });

  void test('the outputs come from the plan step, which reads every filter output', () => {
    const body = job('changes');
    for (const name of [...COMPONENTS.map((k) => k.toLowerCase()), 'docs_only']) {
      assert.ok(body.includes(`      ${name}: \${{ steps.plan.outputs.${name} }}\n`), name);
    }
    for (const k of COMPONENTS) assert.ok(body.includes(`          ${k}: \${{ steps.filter.outputs.${k.toLowerCase()} }}\n`), k);
  });

  void test('the classifier reads the pull request shas from a full clone and cannot fail the plan', () => {
    const body = job('changes');
    assert.ok(body.includes('fetch-depth: 0'));
    assert.ok(body.includes('        id: docs\n        continue-on-error: true\n'));
    assert.ok(body.includes('BASE_SHA: ${{ github.event.pull_request.base.sha }}'));
    assert.ok(body.includes('HEAD_SHA: ${{ github.event.pull_request.head.sha }}'));
    assert.ok(body.includes('DOCS_ONLY: ${{ steps.docs.outputs.docs_only }}'));
  });

  void test('every judged job keeps its name and gate', () => {
    const gates = {
      tokens: "    needs: changes\n    if: needs.changes.outputs.tokens == 'true'\n",
      docs: "    needs: changes\n    if: needs.changes.outputs.docs == 'true'\n",
      frontend: "    needs: changes\n    if: needs.changes.outputs.frontend == 'true'\n",
      rust: "    needs: changes\n    if: needs.changes.outputs.rust == 'true'\n",
      ios: "    needs: changes\n    if: needs.changes.outputs.ios == 'true'\n",
      'android-libs': "    needs: changes\n    if: needs.changes.outputs.android == 'true'\n",
      'android-unit': "    needs: changes\n    if: needs.changes.outputs.android == 'true'\n",
      'android-apks': "    needs: [changes, android-libs]\n    if: needs.changes.outputs.android == 'true' && github.event_name != 'pull_request'\n",
      'android-avd': "    needs: changes\n    if: needs.changes.outputs.android == 'true'\n",
      'android-instrumented': "    needs: [changes, android-libs, android-avd]\n    if: needs.changes.outputs.android == 'true'\n",
      android: "    if: always() && needs.changes.outputs.android == 'true'\n",
      licences: "    needs: changes\n    if: needs.changes.outputs.licences == 'true'\n",
      'treaty-corpus': "    needs: changes\n    # Every change but a docs-only pull request, including when `changes`\n    # itself failed.\n    if: ${{ !cancelled() && needs.changes.outputs.docs_only != 'true' }}\n",
      'docs-claims': "    needs: changes\n    if: needs.changes.outputs.docs_only == 'true'\n",
    };
    assert.deepEqual(Object.keys(gates).sort(), Object.keys(JOBS).sort());
    for (const [name, gate] of Object.entries(gates)) assert.ok(job(name).includes(gate), name);
  });

  void test('check runs unless cancelled and judges every planned job', () => {
    const body = job('check');
    assert.ok(body.includes(`    needs: [changes, ${Object.keys(JOBS).join(', ')}]\n    if: \${{ !cancelled() }}\n`));
    assert.ok(body.includes('run: node scripts/ci-plan.mjs aggregate'));
  });

  void test('the docs lane runs the docs build and the docs claims test the rust job runs', () => {
    assert.ok(job('docs').includes('      - run: npm run build\n        working-directory: docs-site\n'));
    assert.ok(job('rust').includes('        run: cargo test --workspace --all-features\n'));
    assert.ok(job('docs-claims').includes('        run: cargo test -p supermessage-core --all-features --test docs_claims\n'));
    assert.ok(readFileSync(join(ROOT, 'crates/supermessage-core/tests/docs_claims.rs'), 'utf8').includes('docs-site/src/content/docs'));
  });

  void test('the planner tests run whenever the planner changes', () => {
    assert.match(job('changes'), /tokens:\n(?: {14}- .*\n)*? {14}- 'scripts\/tests\/\*\*'\n {14}- 'scripts\/ci-plan\.mjs'\n/);
    assert.ok(job('tokens').includes('        run: node --test scripts/tests/test_ci_plan.mjs\n'));
  });

  void test('the generated token files stay out of docs-only, and the tokens job still regenerates them', () => {
    for (const path of PRODUCT_INPUTS) assert.ok(job('changes').includes(`              - '${path}'\n`), path);
  });

  void test('the desktop build is untouched: push to main only', () => {
    assert.ok(job('desktop').includes("    needs: changes\n    if: github.event_name == 'push' && github.ref == 'refs/heads/main'\n"));
  });
});
