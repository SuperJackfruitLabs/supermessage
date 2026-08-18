// Covers the custom-event registry's dispatch/fallback chain, the
// version-tolerance rule, payload/field bounding, and that a hostile payload
// (wrong-typed fields, a throwing renderer, huge/deeply nested content)
// always resolves to a safe, inert view rather than breaking. See
// `customEvents.ts`'s doc comment for the versioning decision and the
// fallback chain this file exercises.

import { describe, expect, it } from "vitest";
import {
  DEMO_NOTE_EVENT_TYPE,
  PERMISSION_REQUEST_EVENT_TYPE,
  TURN_ACTIVITY_EVENT_TYPE,
  createCustomEventRegistry,
  customEventRegistry,
  demoNoteRenderer,
  permissionRequestRenderer,
  registerCustomEventRenderer,
  resolveCustomEvent,
  safeStringField,
  turnActivityRenderer,
  type CustomEventRegistry,
  type CustomEventRenderer,
} from "./customEvents";

/** A renderer that always shows one field named after its own event type —
 * enough to prove dispatch picked the *right* entry when a registry holds
 * more than one. */
function namedRenderer(eventType: string, maxKnownSchemaVersion = 1): CustomEventRenderer {
  return {
    eventType,
    maxKnownSchemaVersion,
    render: () => ({ fields: [{ label: "Type", value: eventType }] }),
  };
}

describe("resolveCustomEvent — dispatch", () => {
  it("renders through the registered renderer for a known type", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: "Deployed" }, null);
    expect(view).toEqual({
      status: "rendered",
      fields: [{ label: "Note", value: "Deployed" }],
      newerVersion: false,
      decision: null,
    });
  });

  it("picks the renderer matching the exact event type among several registered", () => {
    const registry = createCustomEventRegistry([
      namedRenderer("dev.supermessage.demo.a.v1"),
      namedRenderer("dev.supermessage.demo.b.v1"),
    ]);
    const view = resolveCustomEvent(registry, "dev.supermessage.demo.b.v1", {}, null);
    expect(view).toEqual({
      status: "rendered",
      fields: [{ label: "Type", value: "dev.supermessage.demo.b.v1" }],
      newerVersion: false,
      decision: null,
    });
  });

  it("falls back to the plain-text body for an unknown type", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, "org.kaambaan.card.v1", { title: "x" }, "fallback text");
    expect(view).toEqual({ status: "fallbackBody", text: "fallback text" });
  });

  it("falls back to the generic placeholder for an unknown type with no body", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, "org.kaambaan.card.v1", null, null);
    expect(view).toEqual({ status: "placeholder", text: "Custom event (org.kaambaan.card.v1)" });
  });

  it("names 'unknown' in the placeholder when there is no event type at all", () => {
    const registry = createCustomEventRegistry();
    const view = resolveCustomEvent(registry, null, null, null);
    expect(view).toEqual({ status: "placeholder", text: "Custom event (unknown)" });
  });

  it("falls back to the body when a known type's renderer produces no fields", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    // No `title` field at all — the demo renderer has nothing to show.
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { unrelated: 1 }, "fallback");
    expect(view).toEqual({ status: "fallbackBody", text: "fallback" });
  });

  it("falls back to the placeholder when a known type's renderer produces no fields and there is no body", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, {}, null);
    expect(view).toEqual({
      status: "placeholder",
      text: `Custom event (${DEMO_NOTE_EVENT_TYPE})`,
    });
  });

  it("treats a whitespace-only body the same as no body", () => {
    const registry = createCustomEventRegistry();
    const view = resolveCustomEvent(registry, "org.unknown.v1", null, "   ");
    expect(view.status).toBe("placeholder");
  });
});

describe("resolveCustomEvent — version tolerance", () => {
  it("is not marked newerVersion for a payload at or below the renderer's known schema version", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]); // maxKnownSchemaVersion: 1
    const view = resolveCustomEvent(
      registry,
      DEMO_NOTE_EVENT_TYPE,
      { schema_version: 1, title: "x" },
      null,
    );
    expect(view).toMatchObject({ status: "rendered", newerVersion: false });
  });

  it("still renders (best-effort) and marks newerVersion for a schema_version above the renderer's known version", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(
      registry,
      DEMO_NOTE_EVENT_TYPE,
      // An additive field a v2 schema might add — the renderer, only ever
      // reading `title`, tolerates it without any special-casing.
      { schema_version: 2, title: "x", futureField: { anything: "here" } },
      null,
    );
    expect(view).toEqual({
      status: "rendered",
      fields: [{ label: "Note", value: "x" }],
      newerVersion: true,
      decision: null,
    });
  });

  it("treats a missing schema_version as the baseline version, not as newer", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: "x" }, null);
    expect(view).toMatchObject({ newerVersion: false });
  });

  it("ignores a non-numeric schema_version rather than treating it as newer", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(
      registry,
      DEMO_NOTE_EVENT_TYPE,
      { schema_version: "not-a-number", title: "x" },
      null,
    );
    expect(view).toMatchObject({ newerVersion: false });
  });
});

describe("resolveCustomEvent — hostile payloads render inert", () => {
  it("recovers when the renderer throws, falling back to the body", () => {
    const throwing: CustomEventRenderer = {
      eventType: "dev.supermessage.demo.throws.v1",
      maxKnownSchemaVersion: 1,
      render: () => {
        throw new Error("boom");
      },
    };
    const registry = createCustomEventRegistry([throwing]);
    const view = resolveCustomEvent(
      registry,
      "dev.supermessage.demo.throws.v1",
      { anything: "here" },
      "fallback text",
    );
    expect(view).toEqual({ status: "fallbackBody", text: "fallback text" });
  });

  it("recovers when the renderer throws and there is no body either", () => {
    const throwing: CustomEventRenderer = {
      eventType: "dev.supermessage.demo.throws.v1",
      maxKnownSchemaVersion: 1,
      render: () => {
        throw new Error("boom");
      },
    };
    const registry = createCustomEventRegistry([throwing]);
    const view = resolveCustomEvent(registry, "dev.supermessage.demo.throws.v1", {}, null);
    expect(view.status).toBe("placeholder");
  });

  it("recovers from a renderer that stack-overflows walking a pathologically deep payload unbounded", () => {
    // Simulates the one hazard this module's own accessors are structurally
    // immune to (see customEvents.ts's "Why renderers never recurse"): a
    // hand-written, unbounded recursive tree-walk. Built small in *code* but
    // deep in *structure* — thousands of levels of nesting from a tiny
    // literal, exactly the shape a byte-size cap alone does not rule out.
    let deep: unknown = { value: "leaf" };
    for (let i = 0; i < 20_000; i += 1) {
      deep = { child: deep };
    }

    function unboundedWalk(node: unknown): string {
      if (node !== null && typeof node === "object" && "child" in node) {
        return unboundedWalk((node as { child: unknown }).child);
      }
      return "reached the bottom";
    }

    const badlyWritten: CustomEventRenderer = {
      eventType: "dev.supermessage.demo.bad.v1",
      maxKnownSchemaVersion: 1,
      render: (content) => ({ fields: [{ label: "Deep", value: unboundedWalk(content) }] }),
    };
    const registry = createCustomEventRegistry([badlyWritten]);

    const view = resolveCustomEvent(
      registry,
      "dev.supermessage.demo.bad.v1",
      deep,
      "fallback text",
    );
    // The renderer's own bug (RangeError: call stack size exceeded) must not
    // propagate out of resolveCustomEvent — it degrades to the same
    // body/placeholder chain a normal renderer failure gets.
    expect(view).toEqual({ status: "fallbackBody", text: "fallback text" });
  });

  it("resolves quickly against a huge/deeply nested payload when the renderer only reads a shallow named field", () => {
    let deep: Record<string, unknown> = { title: "the real value" };
    for (let i = 0; i < 50_000; i += 1) {
      deep = { child: deep, title: deep.title };
    }
    const registry = createCustomEventRegistry([demoNoteRenderer]);

    const started = performance.now();
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, deep, null);
    const elapsedMs = performance.now() - started;

    expect(view).toEqual({
      status: "rendered",
      fields: [{ label: "Note", value: "the real value" }],
      newerVersion: false,
      decision: null,
    });
    // Generous bound — this is about proving "doesn't hang", not a strict
    // perf assertion.
    expect(elapsedMs).toBeLessThan(1000);
  });

  it("degrades gracefully when a field the renderer expects to be a string is actually an object or number", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    expect(
      resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: { nested: "object" } }, "fallback"),
    ).toEqual({ status: "fallbackBody", text: "fallback" });
    expect(resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: 42 }, "fallback")).toEqual({
      status: "fallbackBody",
      text: "fallback",
    });
  });

  it("degrades gracefully when content itself is not an object (array, string, number, null)", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    for (const content of [["title", "x"], "just a string", 42, null]) {
      const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, content, "fallback");
      expect(view).toEqual({ status: "fallbackBody", text: "fallback" });
    }
  });
});

describe("field/value bounding", () => {
  it("caps an overlong field value and appends an ellipsis", () => {
    const longTitle = "x".repeat(5000);
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: longTitle }, null);
    expect(view.status).toBe("rendered");
    if (view.status === "rendered") {
      expect(view.fields[0]!.value.length).toBeLessThan(longTitle.length);
      expect(view.fields[0]!.value.endsWith("…")).toBe(true);
    }
  });

  it("caps the number of fields a renderer can contribute", () => {
    const manyFields: CustomEventRenderer = {
      eventType: "dev.supermessage.demo.many.v1",
      maxKnownSchemaVersion: 1,
      render: () => ({
        fields: Array.from({ length: 100 }, (_, i) => ({ label: `L${i}`, value: `V${i}` })),
      }),
    };
    const registry = createCustomEventRegistry([manyFields]);
    const view = resolveCustomEvent(registry, "dev.supermessage.demo.many.v1", {}, null);
    expect(view.status).toBe("rendered");
    if (view.status === "rendered") {
      expect(view.fields.length).toBeLessThanOrEqual(12);
    }
  });

  it("caps an overlong label too, not just the value", () => {
    const longLabel: CustomEventRenderer = {
      eventType: "dev.supermessage.demo.longlabel.v1",
      maxKnownSchemaVersion: 1,
      render: () => ({ fields: [{ label: "L".repeat(500), value: "short" }] }),
    };
    const registry = createCustomEventRegistry([longLabel]);
    const view = resolveCustomEvent(registry, "dev.supermessage.demo.longlabel.v1", {}, null);
    expect(view.status).toBe("rendered");
    if (view.status === "rendered") {
      expect(view.fields[0]!.label.length).toBeLessThan(500);
    }
  });
});

describe("decision", () => {
  // `decision` is deliberately `unknown` here: these tests feed shapes a
  // renderer must never be trusted to get right, so the cast to the
  // renderer interface is the point, not an oversight. Cast through
  // `unknown` to `CustomEventRenderer` — never `as never`.
  function resolve(decision: unknown) {
    const renderer = {
      eventType: "test.decision.v1",
      maxKnownSchemaVersion: 1,
      render: () => ({ fields: [{ label: "Action", value: "Restart gateway" }], decision }),
    } as unknown as CustomEventRenderer;
    const registry = createCustomEventRegistry([renderer]);
    return resolveCustomEvent(registry, "test.decision.v1", {}, "fallback");
  }

  it("passes a well-formed decision through", () => {
    const view = resolve({
      prompt: "Approve restarting hermes-gateway?",
      options: [
        { id: "approve", label: "Approve" },
        { id: "decline", label: "Decline" },
      ],
    });
    expect(view.status).toBe("rendered");
    expect(view.status === "rendered" && view.decision?.options).toHaveLength(2);
  });

  it("bounds the prompt and each label", () => {
    const view = resolve({
      prompt: "p".repeat(1000),
      options: [{ id: "a", label: "l".repeat(500) }],
    });
    if (view.status !== "rendered" || !view.decision) throw new Error("expected a decision");
    expect(view.decision.prompt.length).toBeLessThanOrEqual(301);
    expect(view.decision.options[0]!.label.length).toBeLessThanOrEqual(61);
  });

  it("caps the option count at four", () => {
    const view = resolve({
      prompt: "pick",
      options: Array.from({ length: 20 }, (_, i) => ({ id: `o${i}`, label: `Option ${i}` })),
    });
    expect(view.status === "rendered" && view.decision?.options).toHaveLength(4);
  });

  it("drops a decision with no valid options rather than rendering a dead card", () => {
    expect(resolve({ prompt: "pick", options: [] })).toMatchObject({ decision: null });
    expect(resolve({ prompt: "pick", options: "nope" })).toMatchObject({ decision: null });
    expect(resolve({ prompt: "pick" })).toMatchObject({ decision: null });
  });

  it("drops options with a non-string id or label", () => {
    const view = resolve({
      prompt: "pick",
      options: [{ id: 1, label: "Approve" }, { id: "decline", label: null }, { id: "ok", label: "OK" }],
    });
    expect(view.status === "rendered" && view.decision?.options).toEqual([
      { id: "ok", label: "OK" },
    ]);
  });

  it("is null when a renderer sets nothing", () => {
    const registry = createCustomEventRegistry([
      { eventType: "t.v1", maxKnownSchemaVersion: 1, render: () => ({ fields: [{ label: "a", value: "b" }] }) },
    ]);
    expect(resolveCustomEvent(registry, "t.v1", {}, null)).toMatchObject({ decision: null });
  });

  it("never sets a decision on a fallbackBody or placeholder view", () => {
    const registry = createCustomEventRegistry([]);
    expect(resolveCustomEvent(registry, "unknown.v1", {}, "body")).toEqual({
      status: "fallbackBody",
      text: "body",
    });
  });

  it("keeps the shipped demo renderer decision-free", () => {
    const view = resolveCustomEvent(customEventRegistry, DEMO_NOTE_EVENT_TYPE, { title: "x" }, null);
    expect(view.status === "rendered" && view.decision).toBeNull();
  });

  // Beyond the brief's cases: the two remaining shapes a hostile renderer
  // could plausibly echo straight off the payload, both of which must reach
  // `boundDecision`'s "not an object" arm rather than its property reads.
  it("drops a decision that is not an object at all", () => {
    for (const hostile of [null, undefined, "approve", 42, true]) {
      expect(resolve(hostile)).toMatchObject({ decision: null });
    }
  });

  it("drops a function-valued decision, even when it carries a valid prompt and options", () => {
    // The case that isolates `boundDecision`'s non-object guard: a function
    // is `typeof "function"`, not `"object"`, yet it can carry ordinary
    // properties — so every *other* check in that function passes here.
    // Delete the guard and this shape renders a live amber card. (The
    // primitives above do not isolate it: none of them has an `options`
    // array, so the `Array.isArray` check catches them anyway.)
    const hostile = () => "not a decision";
    Object.assign(hostile, {
      prompt: "Approve restarting hermes-gateway?",
      options: [{ id: "approve", label: "Approve" }],
    });
    expect(resolve(hostile)).toMatchObject({ decision: null });
  });

  it("drops a decision whose prompt is not a string, even with valid options", () => {
    expect(
      resolve({ prompt: { toString: () => "gotcha" }, options: [{ id: "a", label: "A" }] }),
    ).toMatchObject({ decision: null });
    expect(resolve({ options: [{ id: "a", label: "A" }] })).toMatchObject({ decision: null });
  });

  it("drops non-object options (a bare string, null) without dropping their valid siblings", () => {
    const view = resolve({
      prompt: "pick",
      options: ["approve", null, 7, ["nested"], { id: "ok", label: "OK" }],
    });
    expect(view.status === "rendered" && view.decision?.options).toEqual([
      { id: "ok", label: "OK" },
    ]);
  });

  it("counts the four-option cap in valid options, not in raw array entries", () => {
    // Six junk entries first: a naive `slice(0, 4)` before validation would
    // yield an empty (and therefore dropped) decision here.
    const view = resolve({
      prompt: "pick",
      options: [
        null,
        null,
        null,
        null,
        null,
        null,
        { id: "a", label: "A" },
        { id: "b", label: "B" },
      ],
    });
    expect(view.status === "rendered" && view.decision?.options).toEqual([
      { id: "a", label: "A" },
      { id: "b", label: "B" },
    ]);
  });

  it("leaves the option id untruncated — it is an identifier, not display text", () => {
    // The label is bounded because it is rendered; the id is not, because a
    // truncated id would be silently wrong when it is handed to the
    // gate-resolution call. See `boundDecision`'s doc comment.
    const longId = "i".repeat(500);
    const view = resolve({ prompt: "pick", options: [{ id: longId, label: "Approve" }] });
    expect(view.status === "rendered" && view.decision?.options[0]!.id).toBe(longId);
  });
});

describe("registerCustomEventRenderer / createCustomEventRegistry", () => {
  it("starts empty when built with no renderers", () => {
    const registry: CustomEventRegistry = createCustomEventRegistry();
    expect(registry.size).toBe(0);
  });

  it("registers a renderer that resolveCustomEvent can then find", () => {
    const registry = createCustomEventRegistry();
    registerCustomEventRenderer(registry, demoNoteRenderer);
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, { title: "x" }, null);
    expect(view.status).toBe("rendered");
  });

  it("replaces an existing renderer registered under the same event type", () => {
    const registry = createCustomEventRegistry([demoNoteRenderer]);
    registerCustomEventRenderer(registry, namedRenderer(DEMO_NOTE_EVENT_TYPE, 2));
    const view = resolveCustomEvent(registry, DEMO_NOTE_EVENT_TYPE, {}, null);
    expect(view).toEqual({
      status: "rendered",
      fields: [{ label: "Type", value: DEMO_NOTE_EVENT_TYPE }],
      newerVersion: false,
      decision: null,
    });
  });
});

describe("safeStringField", () => {
  it("reads a top-level string field", () => {
    expect(safeStringField({ title: "hello" }, "title", 100)).toBe("hello");
  });

  it("is null when the field is absent", () => {
    expect(safeStringField({}, "title", 100)).toBeNull();
  });

  it("is null when the field is not a string", () => {
    expect(safeStringField({ title: 42 }, "title", 100)).toBeNull();
    expect(safeStringField({ title: { nested: true } }, "title", 100)).toBeNull();
    expect(safeStringField({ title: null }, "title", 100)).toBeNull();
  });

  it("is null when content itself is not an object", () => {
    expect(safeStringField(null, "title", 100)).toBeNull();
    expect(safeStringField("a string", "title", 100)).toBeNull();
    expect(safeStringField(42, "title", 100)).toBeNull();
  });

  it("truncates to maxChars with an ellipsis", () => {
    const value = safeStringField({ title: "x".repeat(50) }, "title", 10);
    expect(value).toBe(`${"x".repeat(10)}…`);
  });
});

// AgentPod's two real event types — the first renderers here that are not a
// demonstration, and the first anywhere to set a `decision`.
describe("turnActivityRenderer", () => {
  function render(content: unknown) {
    const registry = createCustomEventRegistry([turnActivityRenderer]);
    return resolveCustomEvent(registry, TURN_ACTIVITY_EVENT_TYPE, content, "fallback");
  }

  const turn = (over: Record<string, unknown> = {}) => ({
    schema_version: 1,
    session_id: "sess_1",
    tools: [
      { id: "c1", title: "Read src/main.ts", kind: "read", status: "completed", locations: [] },
      { id: "c2", title: "Run tests", kind: "execute", status: "failed", locations: [] },
    ],
    counts: { total: 2, failed: 1, omitted: 0 },
    ...over,
  });

  it("leads with what happened, then lists it", () => {
    const view = render(turn());
    expect(view.status).toBe("rendered");
    if (view.status !== "rendered") return;
    expect(view.fields[0]).toEqual({ label: "Did", value: "2 things, 1 failed" });
    expect(view.fields[1]).toEqual({ label: "completed", value: "Read src/main.ts" });
    expect(view.fields[2]).toEqual({ label: "failed", value: "Run tests" });
    expect(view.decision).toBeNull();
  });

  it("says one thing in the singular, and stays quiet about no failures", () => {
    const view = render(
      turn({ tools: [{ id: "c1", title: "Read a file", status: "completed" }], counts: { total: 1, failed: 0, omitted: 0 } }),
    );
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.fields[0]).toEqual({ label: "Did", value: "1 thing" });
  });

  it("admits what it left out", () => {
    const view = render(turn({ counts: { total: 25, failed: 0, omitted: 5 } }));
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.fields.at(-1)).toEqual({ label: "and", value: "5 more not listed" });
  });

  it("renders a payload from a newer schema, and says it is newer", () => {
    // Additive minor versions must still render — see this module's doc comment.
    const view = render({ ...turn(), schema_version: 2, some_new_field: "ignored" });
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.newerVersion).toBe(true);
    expect(view.fields[0]!.value).toBe("2 things, 1 failed");
  });

  it("degrades rather than coercing a hostile payload", () => {
    // Objects where strings and numbers belong. Nothing may be stringified into
    // the DOM, and nothing may throw the timeline down with it.
    const view = render({
      counts: { total: { toString: "nope" }, failed: [], omitted: null },
      tools: [{ title: { evil: true }, status: 42 }, "not an object", null],
    });
    // Nothing readable survived, so the fallback body is what shows.
    expect(view.status).toBe("fallbackBody");
  });

  it("falls back when there is nothing to show", () => {
    expect(render({ tools: [], counts: {} }).status).toBe("fallbackBody");
    expect(render(null).status).toBe("fallbackBody");
  });
});

describe("permissionRequestRenderer", () => {
  function render(content: unknown) {
    const registry = createCustomEventRegistry([permissionRequestRenderer]);
    return resolveCustomEvent(registry, PERMISSION_REQUEST_EVENT_TYPE, content, "fallback");
  }

  const request = (over: Record<string, unknown> = {}) => ({
    schema_version: 1,
    session_id: "sess_1",
    request_seq: 41,
    title: "Write src/main.ts",
    options: [
      { option_id: "allow_once", name: "Allow once" },
      { option_id: "reject", name: "Reject" },
    ],
    ...over,
  });

  it("asks the question and offers the answers", () => {
    const view = render(request());
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.fields[0]).toEqual({ label: "Wants to", value: "Write src/main.ts" });
    expect(view.decision?.prompt).toBe("Allow Write src/main.ts?");
    expect(view.decision?.options).toEqual([
      { id: "Allow once", label: "Allow once" },
      { id: "Reject", label: "Reject" },
    ]);
  });

  it("carries the option's NAME as its id, because the id is what gets sent", () => {
    // `onDecide` receives `id` verbatim and sends it as an ordinary message.
    // The room is a shared human record: `Allow once` belongs in it,
    // `allow_once` does not. The hub's matcher accepts either.
    const view = render(request());
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.decision!.options.map((o) => o.id)).toEqual(["Allow once", "Reject"]);
  });

  it("describes a request it cannot offer answers to, rather than vanishing", () => {
    const view = render(request({ options: [] }));
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.fields[0]!.value).toBe("Write src/main.ts");
    expect(view.decision).toBeNull();
  });

  it("drops an option with no name, which nothing could label", () => {
    const view = render(request({ options: [{ option_id: "a" }, { option_id: "b", name: "Reject" }] }));
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.decision!.options).toEqual([{ id: "Reject", label: "Reject" }]);
  });

  it("renders no more than four buttons, whatever it is sent", () => {
    // `DECISION_MAX_OPTIONS`. The hub caps at four too, so this is the second
    // line of defence rather than the first.
    const many = Array.from({ length: 7 }, (_, i) => ({ option_id: `o${i}`, name: `Option ${i}` }));
    const view = render(request({ options: many }));
    if (view.status !== "rendered") throw new Error("expected rendered");
    expect(view.decision!.options).toHaveLength(4);
  });

  it("falls back when there is no question", () => {
    expect(render({ options: [] }).status).toBe("fallbackBody");
  });
});
