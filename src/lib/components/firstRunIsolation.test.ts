// RF5: the first-run demo reaches the core's first-run commands and nothing else. It is a sample
// conversation; a command that sends, answers a decision or marks anything read would put a demo
// into a real room or a real account.
//
// This project's vitest runs in plain node, with no DOM to mount a component in, so the guard walks
// the code instead: every module reachable from `FirstRunDemo.svelte` through any import (static,
// namespace, re-export or dynamic, in any path spelling, in child components too) is resolved to a
// file, and none of them may reach the IPC layer except through the four first-run wrappers. The
// IPC layer is where `invoke` lives, so a module that cannot name another wrapper cannot call one.

import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";

const SRC = resolve("src");
const DEMO = join(SRC, "lib/components/FirstRunDemo.svelte");
const IPC = join(SRC, "lib/ipc.ts");
const ALLOWED = ["agentAvatarStyle", "firstRunAdvance", "firstRunAnswer", "firstRunScript"];

interface Edge {
  /** The module that has the import. */
  from: string;
  /** The specifier as written. */
  specifier: string;
  /** The resolved file, or null for a package. */
  file: string | null;
  /** The runtime names taken (type-only names removed); "*" for a namespace, "?" for a dynamic import. */
  names: string[];
  /** The import carries no runtime value at all. */
  typeOnly: boolean;
}

/** The TypeScript an `.svelte` file holds (its script blocks), or a `.ts` file whole. */
function scriptOf(file: string): string {
  const text = readFileSync(file, "utf8");
  if (!file.endsWith(".svelte")) return text;
  return [...text.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join("\n");
}

function resolveSpecifier(from: string, specifier: string): string | null | undefined {
  let base: string;
  if (specifier.startsWith("$lib/")) base = join(SRC, "lib", specifier.slice(5));
  else if (specifier === "$lib") base = join(SRC, "lib");
  else if (specifier.startsWith(".")) base = resolve(dirname(from), specifier);
  else return null; // a package, `$app/…`, `node:…`
  const tries = [base, `${base}.ts`, `${base}.svelte.ts`, `${base}.svelte`, `${base}.js`, join(base, "index.ts")];
  return tries.find((p) => existsSync(p) && statSync(p).isFile());
}

function edgesOf(file: string): Edge[] {
  const sf = ts.createSourceFile(file, scriptOf(file), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const found: Edge[] = [];
  const add = (specifier: string, names: string[], typeOnly: boolean) => {
    const file2 = resolveSpecifier(file, specifier);
    if (file2 === undefined) throw new Error(`${rel(file)}: cannot resolve "${specifier}"`);
    found.push({ from: file, specifier, file: file2, names, typeOnly });
  };
  const visit = (node: ts.Node): void => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      const clause = node.importClause;
      const names: string[] = [];
      let typeOnly = clause?.isTypeOnly ?? false;
      if (!clause) typeOnly = false; // `import "x"` still runs the module
      else if (!clause.isTypeOnly) {
        if (clause.name) names.push("default");
        const bindings = clause.namedBindings;
        if (bindings && ts.isNamespaceImport(bindings)) names.push("*");
        else if (bindings) for (const el of bindings.elements) if (!el.isTypeOnly) names.push((el.propertyName ?? el.name).text);
        typeOnly = names.length === 0 && clause !== undefined;
      }
      add(node.moduleSpecifier.text, names, typeOnly);
    } else if (ts.isExportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const names = node.isTypeOnly
        ? []
        : node.exportClause && ts.isNamedExports(node.exportClause)
          ? node.exportClause.elements.filter((e) => !e.isTypeOnly).map((e) => (e.propertyName ?? e.name).text)
          : ["*"];
      add(node.moduleSpecifier.text, names, names.length === 0);
    } else if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const arg = node.arguments[0];
      if (!arg || !ts.isStringLiteralLike(arg)) throw new Error(`${rel(file)}: a dynamic import() the guard cannot resolve`);
      add(arg.text, ["?"], false);
    } else if (ts.isImportEqualsDeclaration(node) || (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "require")) {
      throw new Error(`${rel(file)}: import= / require() is not walked`);
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
}

const rel = (f: string) => relative(SRC, f);

/** Every module reachable from `entry` through imports that carry a runtime value, with the edges walked. */
function reach(entry: string): { modules: Set<string>; edges: Edge[] } {
  const modules = new Set<string>([entry]);
  const edges: Edge[] = [];
  const queue = [entry];
  while (queue.length > 0) {
    const file = queue.pop()!;
    for (const edge of edgesOf(file)) {
      edges.push(edge);
      if (edge.typeOnly || edge.file === null || modules.has(edge.file)) continue;
      modules.add(edge.file);
      // The IPC layer is the boundary being guarded; its own imports are the other side of it.
      if (edge.file !== IPC) queue.push(edge.file);
    }
  }
  return { modules, edges };
}

/** The violations of RF5 in the graph below `entry`; empty when it is clean. */
export function ipcViolations(entry: string): string[] {
  const { edges } = reach(entry);
  const bad: string[] = [];
  for (const e of edges) {
    if (e.file === null && /^@tauri-apps\//.test(e.specifier) && !e.typeOnly) {
      bad.push(`${rel(e.from)} imports ${e.specifier} directly`);
    }
    if (e.file !== IPC || e.typeOnly) continue;
    for (const name of e.names) {
      if (!ALLOWED.includes(name)) bad.push(`${rel(e.from)} takes "${name}" from the IPC layer`);
    }
  }
  return bad;
}

describe("FirstRunDemo.svelte reaches the IPC layer only through the first-run wrappers", () => {
  it("walks the demo's children and helpers, not just the demo", () => {
    const { modules } = reach(DEMO);
    const names = [...modules].map(rel);
    expect(names).toContain("lib/components/FirstRunDemo.svelte");
    expect(names).toContain("lib/components/firstRun.ts");
    expect(names.some((n) => n.endsWith("DispatchCard.svelte"))).toBe(true);
    expect(names.some((n) => n.endsWith("RoomAvatar.svelte"))).toBe(true);
    expect(names.length).toBeGreaterThan(5);
  });

  it("takes nothing from $lib/ipc but the four wrappers, in any module it reaches", () => {
    expect(ipcViolations(DEMO)).toEqual([]);
  });

  it("uses the four wrappers (so the allowlist is what the demo really calls)", () => {
    const { edges } = reach(DEMO);
    const used = new Set(edges.filter((e) => e.file === IPC && !e.typeOnly).flatMap((e) => e.names));
    expect([...used].sort()).toEqual(ALLOWED);
  });

  it("answers the card with the local step machine, with no answeredHere or onAnswered", () => {
    const source = readFileSync(DEMO, "utf8");
    expect(source).toMatch(/demo\.answer\(optionId\)/);
    expect(source).not.toMatch(/answeredHere|onAnswered/);
  });
});
