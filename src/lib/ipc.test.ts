// Verifies `makeSessionCommands`'s core contract: the `login`/
// `restoreSession` functions it returns always call `onArm` before issuing
// the underlying Tauri command. This is what makes it structurally
// impossible to invoke the `login`/`restore_session` commands without also
// re-arming the room-list tracker — see that function's doc comment in
// `ipc.ts` and `rooms.svelte.ts`'s module doc comment for the hazard this
// guards against.
//
// `@tauri-apps/api/core`'s `invoke` throws outside a real Tauri webview, so
// it's mocked here rather than exercised for real — this test is about the
// call ordering `makeSessionCommands` guarantees, not the IPC round trip
// itself (that's `commands.rs`'s and `dto.rs`'s job to get right on the
// Rust side).

import { describe, expect, it, vi } from "vitest";

const invokeMock = vi.fn();

vi.mock("@tauri-apps/api/core", () => ({
  invoke: (...args: unknown[]) => invokeMock(...args),
}));
vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(),
}));

const { makeSessionCommands } = await import("./ipc");

describe("makeSessionCommands", () => {
  it("calls onArm before invoking login", async () => {
    invokeMock.mockResolvedValue(undefined);
    const order: string[] = [];
    const onArm = () => order.push("arm");
    invokeMock.mockImplementationOnce(async () => {
      order.push("invoke");
      return undefined;
    });

    const { login } = makeSessionCommands(onArm);
    await login("https://example.org", "alice", "hunter2");

    expect(order).toEqual(["arm", "invoke"]);
    expect(invokeMock).toHaveBeenCalledWith("login", {
      homeserver: "https://example.org",
      username: "alice",
      password: "hunter2",
    });
  });

  it("calls onArm before invoking restore_session, and returns its result", async () => {
    const order: string[] = [];
    const onArm = () => order.push("arm");
    invokeMock.mockImplementationOnce(async () => {
      order.push("invoke");
      return true;
    });

    const { restoreSession } = makeSessionCommands(onArm);
    const result = await restoreSession();

    expect(order).toEqual(["arm", "invoke"]);
    expect(result).toBe(true);
    expect(invokeMock).toHaveBeenCalledWith("restore_session");
  });

  it("calls onArm synchronously, before the command promise even settles", () => {
    // Regression guard for the specific race `rooms.svelte.ts` relies on:
    // the core's streaming task can emit its first envelope before the
    // `login` command's own promise resolves, so the re-arm must happen
    // before that async gap opens, not after `await`.
    let armed = false;
    invokeMock.mockReturnValueOnce(new Promise(() => {})); // never resolves
    const { login } = makeSessionCommands(() => {
      armed = true;
    });

    void login("https://example.org", "alice", "hunter2");
    expect(armed).toBe(true);
  });
});
