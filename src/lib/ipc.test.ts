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

const { listen } = await import("@tauri-apps/api/event");
const { turn, turnStatusWord, firstRunAdvance, firstRunAnswer, firstRunScript, acknowledgement, hasSent, setHiddenEvents, roomCast, typingView, attachmentSend, rosterNeedsYouInbox, roomStatus, rosterFilterSections, agentAvatarStyle, agentAvatarVariants, deleteMessage, editMessage, ensureRecovery, makeSessionCommands, spaceDisplayName, spaceSelect, spacesList, ignoreUser, unignoreUser, blockedUsers, reportEvent, reportRoom, reportUser, reportReasonRemaining, setRoomNotificationMode, setRoomPinned, knownPeople, directRoomWith, roomInviter, roomMentionCandidates, account, peopleLabel, sendPermissionAnswer, notificationPermission, notificationClearAll, sendGateDecisionTo, onIgnoredUsers } = await import("./ipc");

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

// The spaces commands are ordinary bare exports — unlike `login`/
// `restoreSession` there is nothing to re-arm, and re-arming is precisely
// what `space_select` must *not* provoke (see its doc comment). What is
// worth pinning down is the wire format, which fails at runtime with
// "invalid args" rather than at compile time if the argument name drifts
// from the Rust side's `space_id`.
describe("the spaces commands", () => {
  it("passes the space id as camelCase spaceId", async () => {
    invokeMock.mockResolvedValueOnce(undefined);

    await spaceSelect("!space:x.org");

    expect(invokeMock).toHaveBeenCalledWith("space_select", { spaceId: "!space:x.org" });
  });

  it("sends an explicit null for All rooms rather than omitting the argument", async () => {
    // The core's parameter is `Option<String>`, so an omitted key would also
    // deserialize as `None` — but only by luck of Tauri's argument handling.
    // Saying `null` out loud is what makes "restore every room" a value this
    // function passes rather than an absence it hopes will be read that way.
    invokeMock.mockResolvedValueOnce(undefined);

    await spaceSelect(null);

    expect(invokeMock).toHaveBeenCalledWith("space_select", { spaceId: null });
  });

  it("lists spaces with no arguments and returns them unchanged", async () => {
    const summaries = [{ id: "!a:x.org", name: "Alpha", avatarUrl: null, childCount: 2 }];
    invokeMock.mockResolvedValueOnce(summaries);

    await expect(spacesList()).resolves.toEqual(summaries);
    expect(invokeMock).toHaveBeenCalledWith("spaces_list");
  });
});

describe("ensureRecovery", () => {
  it("invokes ensure_recovery and passes the key through", async () => {
    invokeMock.mockResolvedValueOnce("EsTk abcd");

    await expect(ensureRecovery()).resolves.toBe("EsTk abcd");
    expect(invokeMock).toHaveBeenCalledWith("ensure_recovery");
  });

  it("passes null through when recovery was already on", async () => {
    invokeMock.mockResolvedValueOnce(null);

    await expect(ensureRecovery()).resolves.toBeNull();
    expect(invokeMock).toHaveBeenCalledWith("ensure_recovery");
  });
});

describe("message edit and delete", () => {
  it("deleteMessage invokes delete_message with the room and event", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(undefined);
    await deleteMessage("!r:x", "$e");
    expect(invokeMock).toHaveBeenCalledWith("delete_message", { roomId: "!r:x", eventId: "$e" });
  });
  it("editMessage invokes edit_message with the new body", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(undefined);
    await editMessage("!r:x", "$e", "fixed");
    expect(invokeMock).toHaveBeenCalledWith("edit_message", { roomId: "!r:x", eventId: "$e", body: "fixed" });
  });
});

describe("agent avatar", () => {
  it("agentAvatarStyle invokes agent_avatar_style with roomId", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue({ ground: "accent", ink: "accentContent", variant: 0 });
    await expect(agentAvatarStyle("!a:x")).resolves.toEqual({
      ground: "accent",
      ink: "accentContent",
      variant: 0,
    });
    expect(invokeMock).toHaveBeenCalledWith("agent_avatar_style", { roomId: "!a:x" });
  });
  it("agentAvatarVariants invokes agent_avatar_variants", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue([]);
    await agentAvatarVariants();
    expect(invokeMock).toHaveBeenCalledWith("agent_avatar_variants");
  });
});

describe("roster views", () => {
  it("rosterNeedsYouInbox invokes roster_needs_you_inbox with rows and nowMs", async () => {
    invokeMock.mockReset();
    const inbox = { decisions: [], invitations: [], count: 0, badgeCount: 0 };
    invokeMock.mockResolvedValue(inbox);
    await expect(rosterNeedsYouInbox([], 42)).resolves.toEqual(inbox);
    expect(invokeMock).toHaveBeenCalledWith("roster_needs_you_inbox", { rows: [], nowMs: 42 });
  });
  it("roomStatus passes describesAgent and turnInProgress camelCase", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue("working");
    await expect(roomStatus("active", true, true)).resolves.toBe("working");
    expect(invokeMock).toHaveBeenCalledWith("room_status", {
      state: "active",
      describesAgent: true,
      turnInProgress: true,
    });
  });
  it("rosterFilterSections passes sections and filter", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue([]);
    await rosterFilterSections([], "unread");
    expect(invokeMock).toHaveBeenCalledWith("roster_filter_sections", {
      sections: [],
      filter: "unread",
    });
  });
});

describe("attachmentSend", () => {
  it("returns the core's outcome", async () => {
    const outcome = {
      outcome: "failed",
      error: { kind: "network", message: "x" },
      restaged: null,
    };
    invokeMock.mockResolvedValueOnce(outcome);

    const result = await attachmentSend("!r:x.org", "tok", null);

    expect(result).toEqual(outcome);
    expect(invokeMock).toHaveBeenCalledWith("attachment_send", {
      roomId: "!r:x.org",
      token: "tok",
      caption: null,
    });
  });
});

describe("cast, typing line and acknowledgement", () => {
  it("roomCast invokes room_cast with roomId", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue({ roomId: "!r:x" });
    await roomCast("!r:x");
    expect(invokeMock).toHaveBeenCalledWith("room_cast", { roomId: "!r:x" });
  });

  it("typingView passes typers, agentIds and counterpart", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue({ line: null, agents: [] });
    const typers = [{ userId: "@a:x", displayName: "A", label: "A" }];
    const counterpart = { userId: "@a:x", name: "Atlas" };
    await typingView(typers, ["@a:x"], counterpart);
    expect(invokeMock).toHaveBeenCalledWith("typing_view", {
      typers,
      agentIds: ["@a:x"],
      counterpart,
    });
  });

  it("acknowledgement passes the five facts camelCase", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(null);
    await acknowledgement("!r:x", "Atlas", ["@a:x"], ["Atlas"], true);
    expect(invokeMock).toHaveBeenCalledWith("acknowledgement", {
      roomId: "!r:x",
      addressee: "Atlas",
      agentIds: ["@a:x"],
      typingAgents: ["Atlas"],
      turnInProgress: true,
    });
  });

  it("turn asks for one room's record by camelCase roomId", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(null);
    expect(await turn("!r:x")).toBeNull();
    expect(invokeMock).toHaveBeenCalledWith("turn", { roomId: "!r:x" });
  });

  it("turnStatusWord passes the ending camelCase and returns the core's word", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue("Stopped");
    expect(await turnStatusWord("timedOut")).toBe("Stopped");
    expect(invokeMock).toHaveBeenCalledWith("turn_status_word", { endedBy: "timedOut" });
  });

  it("hasSent invokes has_sent with roomId", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(true);
    expect(await hasSent("!r:x")).toBe(true);
    expect(invokeMock).toHaveBeenCalledWith("has_sent", { roomId: "!r:x" });
  });

  it("setHiddenEvents invokes set_hidden_events with the whole set", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(undefined);
    await setHiddenEvents(["$a", "$b"]);
    expect(invokeMock).toHaveBeenCalledWith("set_hidden_events", { eventIds: ["$a", "$b"] });
  });

  it("spaceDisplayName invokes space_display_name with name", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue("Runtime 9247e5");
    expect(await spaceDisplayName("9247e5c1a0b34d2e")).toBe("Runtime 9247e5");
    expect(invokeMock).toHaveBeenCalledWith("space_display_name", { name: "9247e5c1a0b34d2e" });
  });

  it("firstRunAnswer passes the step", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue("approved");
    expect(await firstRunAnswer("waiting")).toBe("approved");
    expect(invokeMock).toHaveBeenCalledWith("first_run_answer", { step: "waiting" });
  });

  it("firstRunAdvance passes the step and firstRunScript takes none", async () => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue("asking");
    expect(await firstRunAdvance("greeting")).toBe("asking");
    expect(invokeMock).toHaveBeenCalledWith("first_run_advance", { step: "greeting" });
    invokeMock.mockReset();
    invokeMock.mockResolvedValue({ agentName: "Atlas" });
    expect((await firstRunScript()).agentName).toBe("Atlas");
    expect(invokeMock).toHaveBeenCalledWith("first_run_script");
  });
});

describe("session commands", () => {
  const calls: [string, () => Promise<unknown>, string, Record<string, unknown> | undefined][] = [
    ["ignoreUser invokes ignore_user with userId", () => ignoreUser("@a:x"), "ignore_user", { userId: "@a:x" }],
    ["unignoreUser invokes unignore_user with userId", () => unignoreUser("@a:x"), "unignore_user", { userId: "@a:x" }],
    ["blockedUsers invokes blocked_users", () => blockedUsers(), "blocked_users", undefined],
    ["reportEvent invokes report_event with roomId, eventId and reason", () => reportEvent("!r:x", "$e", "spam"), "report_event", { roomId: "!r:x", eventId: "$e", reason: "spam" }],
    ["reportRoom invokes report_room with roomId and reason", () => reportRoom("!r:x", "spam"), "report_room", { roomId: "!r:x", reason: "spam" }],
    ["reportUser invokes report_user with userId and reason", () => reportUser("@a:x", "spam"), "report_user", { userId: "@a:x", reason: "spam" }],
    ["reportReasonRemaining invokes report_reason_remaining with reason", () => reportReasonRemaining("hi"), "report_reason_remaining", { reason: "hi" }],
    ["setRoomNotificationMode passes roomId and mode", () => setRoomNotificationMode("!r:x", "muted"), "set_room_notification_mode", { roomId: "!r:x", mode: "muted" }],
    ["setRoomPinned passes roomId and pinned", () => setRoomPinned("!r:x", true), "set_room_pinned", { roomId: "!r:x", pinned: true }],
    ["knownPeople passes an explicit null query", () => knownPeople(), "known_people", { query: null }],
    ["knownPeople passes the query", () => knownPeople("ate"), "known_people", { query: "ate" }],
    ["roomMentionCandidates passes roomId and query", () => roomMentionCandidates("!r:x", "at"), "room_mention_candidates", { roomId: "!r:x", query: "at" }],
    ["directRoomWith passes userId", () => directRoomWith("@a:x"), "direct_room_with", { userId: "@a:x" }],
    ["roomInviter passes roomId", () => roomInviter("!r:x"), "room_inviter", { roomId: "!r:x" }],
    ["account invokes account", () => account(), "account", undefined],
    ["peopleLabel passes userIds", () => peopleLabel(["@a:x", "@b:x"]), "people_label", { userIds: ["@a:x", "@b:x"] }],
    ["sendPermissionAnswer passes roomId and optionId", () => sendPermissionAnswer("!r:x", "Allow once"), "send_permission_answer", { roomId: "!r:x", optionId: "Allow once" }],
    ["notificationPermission passes prompt", () => notificationPermission(true), "notification_permission", { prompt: true }],
    ["notificationPermission without a prompt", () => notificationPermission(false), "notification_permission", { prompt: false }],
    ["notificationClearAll takes no arguments", () => notificationClearAll(), "notification_clear_all", undefined],
    ["sendGateDecisionTo passes camelCase args", () => sendGateDecisionTo("!r:x", "g1", "approve", null, "$e", "Ship it?"), "send_gate_decision_to", { roomId: "!r:x", gateId: "g1", optionId: "approve", comment: null, inReplyTo: "$e", prompt: "Ship it?" }],
  ];

  it.each(calls)("%s", async (_name, call, command, args) => {
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(null);
    await call();
    expect(invokeMock.mock.calls).toEqual([args === undefined ? [command] : [command, args]]);
  });

  it("onIgnoredUsers listens on sm://ignored-users and hands over the ids", async () => {
    const listenMock = vi.mocked(listen);
    listenMock.mockReset();
    listenMock.mockResolvedValue(() => {});
    const handler = vi.fn();
    await onIgnoredUsers(handler);
    expect(listenMock.mock.calls[0][0]).toBe("sm://ignored-users");
    const callback = listenMock.mock.calls[0][1] as (e: { payload: string[] }) => void;
    callback({ payload: ["@a:x"] });
    expect(handler).toHaveBeenCalledWith(["@a:x"]);
  });
});
