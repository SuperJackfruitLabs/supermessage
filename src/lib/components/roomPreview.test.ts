import { describe, expect, it } from "vitest";
import { composeRoomPreview, type RoomPreviewFacts } from "./roomPreview";
import { DECISION_BEARING_EVENT_TYPES } from "./customEvents";

/** A room with an ordinary peer message, overridden per case. Every field is
 * named explicitly in the overrides that matter, so a case reads as the row
 * of the truth table it is. */
function facts(over: Partial<RoomPreviewFacts> = {}): RoomPreviewFacts {
  return {
    lastMessage: "ship it",
    lastMessageIsOwn: false,
    lastMessageNamesSender: false,
    lastEventType: null,
    ...over,
  };
}

/** A fixture decision type. Deliberately in this app's own demo namespace
 * (`dev.supermessage.demo.*`, the same one `customEvents.ts` reserves for
 * proving the extension path) so it can never be mistaken for — or collide
 * with — a real suite event type, and never tempts anyone to add it to the
 * production set. */
const FIXTURE_GATE_TYPE = "dev.supermessage.demo.gate.v1";
const fixtureDecisionTypes: ReadonlySet<string> = new Set([FIXTURE_GATE_TYPE]);

describe("composeRoomPreview — the You: rule", () => {
  // All four combinations of the two booleans. The pair is the whole reason
  // this module exists: either flag alone gives the wrong answer for one of
  // these rows.

  it("peer message, not naming its sender: the text, unprefixed", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessageIsOwn: false, lastMessageNamesSender: false }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toEqual({ text: "ship it", pending: false });
  });

  it("own message, not naming its sender: prefixed with You:", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessageIsOwn: true, lastMessageNamesSender: false }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toEqual({ text: "You: ship it", pending: false });
  });

  it("peer emote, which already names its sender: left alone", () => {
    expect(
      composeRoomPreview(
        facts({
          lastMessage: "Kai waves",
          lastMessageIsOwn: false,
          lastMessageNamesSender: true,
        }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toEqual({ text: "Kai waves", pending: false });
  });

  it("own emote: no You: prefix, because the text already names me", () => {
    // The regression this flag was added for: `You: Rakesh waves` names the
    // sender twice. See `RoomSummary.lastMessageNamesSender`.
    const preview = composeRoomPreview(
      facts({
        lastMessage: "Rakesh waves",
        lastMessageIsOwn: true,
        lastMessageNamesSender: true,
      }),
      DECISION_BEARING_EVENT_TYPES,
    );
    expect(preview).toEqual({ text: "Rakesh waves", pending: false });
    expect(preview!.text).not.toContain("You:");
  });

  it("prefixes only its own prefix, leaving a body that looks like one alone", () => {
    // A peer whose message literally begins "You: " must not be mistaken
    // for an own message, and must not be rewritten.
    expect(
      composeRoomPreview(
        facts({ lastMessage: "You: nice try", lastMessageIsOwn: false }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toEqual({ text: "You: nice try", pending: false });
  });
});

describe("composeRoomPreview — nothing to show", () => {
  it("returns null when there is no preview, rather than a placeholder", () => {
    // Spec §6.1.1: the line is omitted entirely, exactly as the role/time
    // line already is. A caller must be able to tell "no line" from "an
    // empty line" without inspecting the string.
    expect(composeRoomPreview(facts({ lastMessage: null }), DECISION_BEARING_EVENT_TYPES)).toBeNull();
  });

  it("returns null for a blank preview rather than a bare You:", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessage: "   ", lastMessageIsOwn: true }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toBeNull();
  });

  it("ignores the ownership flags entirely when there is no text", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessage: null, lastMessageIsOwn: true, lastMessageNamesSender: true }),
        DECISION_BEARING_EVENT_TYPES,
      ),
    ).toBeNull();
  });
});

describe("composeRoomPreview — the pending-decision path", () => {
  it("reads Approval needed for a decision-bearing event type", () => {
    expect(
      composeRoomPreview(facts({ lastEventType: FIXTURE_GATE_TYPE }), fixtureDecisionTypes),
    ).toEqual({ text: "Approval needed", pending: true });
  });

  it("replaces the event's own text rather than competing with it", () => {
    // A gate must never be able to put its own prose on the roster.
    const preview = composeRoomPreview(
      facts({ lastMessage: "Custom event", lastEventType: FIXTURE_GATE_TYPE }),
      fixtureDecisionTypes,
    );
    expect(preview).toEqual({ text: "Approval needed", pending: true });
  });

  it("still fires when the event produced no preview text at all", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessage: null, lastEventType: FIXTURE_GATE_TYPE }),
        fixtureDecisionTypes,
      ),
    ).toEqual({ text: "Approval needed", pending: true });
  });

  it("ignores the You: rule — a gate of ours is still Approval needed", () => {
    expect(
      composeRoomPreview(
        facts({
          lastMessage: "Custom event",
          lastMessageIsOwn: true,
          lastEventType: FIXTURE_GATE_TYPE,
        }),
        fixtureDecisionTypes,
      ),
    ).toEqual({ text: "Approval needed", pending: true });
  });

  it("leaves an ordinary custom event as an ordinary preview", () => {
    expect(
      composeRoomPreview(
        facts({ lastMessage: "Custom event", lastEventType: "dev.supermessage.demo.note.v1" }),
        fixtureDecisionTypes,
      ),
    ).toEqual({ text: "Custom event", pending: false });
  });

  it("never fires for a message with no event type", () => {
    expect(
      composeRoomPreview(facts({ lastEventType: null }), fixtureDecisionTypes)!.pending,
    ).toBe(false);
  });
});

describe("DECISION_BEARING_EVENT_TYPES", () => {
  it("is empty, so no production event can reach the amber path", () => {
    // The load-bearing assertion of the whole dormant path (spec §6.1.1,
    // §7.1: build the mechanism, ship it unreachable). If this ever fails,
    // read that constant's doc comment before changing this test — the
    // second reason it must stay empty survives the gate schema landing.
    expect(DECISION_BEARING_EVENT_TYPES.size).toBe(0);
  });

  it("does not contain the fixture type the tests above prove the path with", () => {
    expect(DECISION_BEARING_EVENT_TYPES.has(FIXTURE_GATE_TYPE)).toBe(false);
  });
});
