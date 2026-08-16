// Driving the app itself, so its UI can be tested without a human at the
// keyboard.
//
// **Why this exists.** The bug that prompted it has no symptom anywhere but
// the screen: messages that are in the model and render as empty bubbles, and
// a room that blanks and refills. Every layer underneath tested innocent,
// repeatedly, because every layer underneath *was* innocent. Reading the
// rendered rows is the only way to see it.
//
// **Why WebdriverIO and not `tauri-driver`.** macOS has no WKWebView driver, so
// `tauri-driver` does not work here at all. `@wdio/tauri-service` carries an
// embedded WebDriver server instead (`tauri-plugin-wdio-webdriver`, registered
// in `lib.rs` behind `debug_assertions`), which is the one path Tauri documents
// for macOS.
//
// **Debug builds only**, and deliberately: the embedded server is a
// remote-control surface on a running app. A release build never compiles it in.

import type { Options } from "@wdio/types";

export const config: Options.Testrunner = {
  runner: "local",
  framework: "mocha",
  reporters: ["spec"],
  specs: ["./e2e/**/*.spec.ts"],
  maxInstances: 1,

  // One at a time and unhurried: this launches a real application that has to
  // restore a session from the OS keyring and sync a homeserver before any
  // assertion can mean anything.
  mochaOpts: {
    ui: "bdd",
    timeout: 120_000,
  },

  capabilities: [
    {
      browserName: "tauri",
      "tauri:options": {
        // The debug binary `cargo build` produces — the same one `tauri dev`
        // runs, so what is driven here is what a developer sees.
        application: "./src-tauri/target/debug/supermessage",
      },
    } as WebdriverIO.Capabilities,
  ],

  services: [
    [
      "tauri",
      {
        appBinaryPath: "./src-tauri/target/debug/supermessage",
        // On macOS the service auto-detects the embedded provider; naming it
        // means a Linux or Windows run fails loudly rather than quietly taking
        // a different path and reporting different results.
        driverProvider: "embedded",
        // A cold debug build opens a keyring, a SQLite store and a homeserver
        // sync before its embedded WebDriver answers — measured at several
        // seconds, and longer on the first run after a rebuild. The default
        // readiness window is shorter than that, so the service concluded the
        // server was "unreachable" and fell back to `tauri-driver`, which does
        // not exist on macOS. The failure then reads as a missing binary
        // rather than a slow start.
        statusPollTimeout: 60_000,
        timeout: 60_000,
      },
    ],
  ],

  logLevel: "warn",

  /**
   * Two things this run cannot proceed without, checked before an app is
   * launched — both learned by watching a run fail confusingly.
   *
   * The debug build loads its UI from vite's dev server (`devUrl`), so without
   * one running the window comes up empty and the failure reads as
   * "core.invoke not available", which sounds like a Tauri problem and is not.
   *
   * And a second instance cannot open the matrix store the first one holds, so
   * a leftover app from an earlier run makes this one die with ECONNREFUSED
   * halfway through — again saying nothing about the actual cause.
   */
  onPrepare: async () => {
    const { execSync } = await import("node:child_process");

    try {
      execSync("pkill -f 'target/debug/supermessage'", { stdio: "ignore" });
    } catch {
      // No stray instance: `pkill` exits non-zero when it matches nothing.
    }

    const viteUp = await fetch("http://localhost:1420/")
      .then((r) => r.ok)
      .catch(() => false);

    if (!viteUp) {
      throw new Error(
        "No dev server on :1420 — a debug build loads its UI from vite. Run `pnpm dev` first.",
      );
    }
  },
};
