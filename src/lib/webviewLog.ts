// Sending the webview's console into the Rust log.
//
// Tauri on macOS runs a WKWebView. Its inspector speaks Safari's Web Inspector
// protocol, not CDP, so nothing can attach to the console programmatically —
// which means a bug spanning the IPC boundary (the timeline's diff stream on
// one side, the list rendering it on the other) gets diagnosed from half the
// evidence. This forwards the half that was invisible.
//
// Only `warn` and `error`, plus the two events that carry a crash: an app that
// mirrored every `console.log` would drown the stream it is meant to make
// readable.
//
// Installed only in development. In a shipped build this is a channel from the
// page into the log file with nothing gained — the reader has no log to read.

import { invoke } from "@tauri-apps/api/core";

/** Stringifies a console argument without throwing on a cycle or a DOM node. */
function render(value: unknown): string {
  if (typeof value === "string") return value;
  if (value instanceof Error) return `${value.name}: ${value.message}`;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function forward(level: "warn" | "error", args: unknown[]): void {
  // Fire-and-forget, and swallowing its own failure: a logging call that can
  // throw would be a new failure mode inside the paths it exists to observe.
  void invoke("log_from_webview", {
    level,
    message: args.map(render).join(" "),
  }).catch(() => {});
}

export function installWebviewLogBridge(): void {
  const original = { warn: console.warn, error: console.error };

  console.warn = (...args: unknown[]) => {
    forward("warn", args);
    original.warn(...args);
  };
  console.error = (...args: unknown[]) => {
    forward("error", args);
    original.error(...args);
  };

  window.addEventListener("error", (event) => {
    forward("error", [`uncaught: ${event.message}`, `${event.filename}:${event.lineno}`]);
  });
  window.addEventListener("unhandledrejection", (event) => {
    forward("error", ["unhandled rejection:", event.reason]);
  });
}
