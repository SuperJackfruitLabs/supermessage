// Composes a roster row's preview line (spec §6.1.1) from the four facts
// the core ships on `RoomSummary`: the preview text, whether it is ours,
// whether it already names its own sender, and — for a custom event only —
// its Matrix event type.
//
// The core deliberately returns facts rather than a display string, so
// exactly one place in the webview turns them into one. That place is this
// module, and it is pure (no DOM, no store, no Svelte, no clock) for the
// same reason `roomIdentity.ts` is: the interesting part is a truth table,
// and a truth table is worth testing without mounting a component.
//
// ## The one real rule
//
// Prefix `You: ` when the message is ours **and** does not already name its
// sender. Both halves are load-bearing:
//
// | isOwn | namesSender | line             |
// |-------|-------------|------------------|
// | false | false       | `ship it`        |
// | true  | false       | `You: ship it`   |
// | false | true        | `Kai waves`      |
// | true  | true        | `Rakesh waves`   |
//
// That last row is why `lastMessageNamesSender` exists at all. An emote
// previews as `"<Name> waves"` — the way `Timeline.svelte` renders the same
// event — so prefixing an own emote would produce `"You: <MyName> waves"`,
// naming the sender twice. Neither `isOwn` nor the emote rendering is wrong
// alone; only the composition is, which is exactly the kind of defect a
// composition module should own. See `ipc.ts`'s `RoomSummary` field docs
// and the core's `MessagePreview::names_sender`.
//
// No sender prefix is added for *anyone else*, and that is a known,
// recorded limitation rather than an oversight (spec §6.1.1): most rooms
// here are one-to-one with an agent, so prefixing with the agent's name
// just repeats the room name two lines above. In a room with several
// people this loses attribution.
//
// ## The pending-decision path
//
// A gate awaiting this operator makes the line read `Approval needed` and
// the row take `--color-signal` — the same amber, meaning the same thing,
// one surface earlier than the dispatch card (spec §3, §6.1.1, §7.1). It
// is keyed off `lastEventType` against `customEvents.ts`'s
// `DECISION_BEARING_EVENT_TYPES`, **which is empty and must stay empty**;
// see that constant for the two independent reasons this cannot fire in
// production and why the second one survives the gate schema landing.
// {@link composeRoomPreview} takes the set as a parameter — the same shape
// `resolveCustomEvent` takes its registry, and for the same reason: the
// mechanism is provable with a fixture set without touching the production
// one.

/**
 * The subset of `RoomSummary` this module reads. Structural rather than an
 * import of `RoomSummary` itself, so the tests can state a case as the four
 * fields it is actually about instead of a whole room object with six
 * irrelevant ones — and so nothing here can quietly start depending on the
 * room's name, unread count or activity time, none of which belong in a
 * decision about *what was said*.
 */
export interface RoomPreviewFacts {
  lastMessage: string | null;
  lastMessageIsOwn: boolean;
  lastMessageNamesSender: boolean;
  lastEventType: string | null;
}

/** A composed preview line, or `null` from {@link composeRoomPreview} when
 * the row has nothing to show. */
export interface RoomPreview {
  /** The display string, ready to render as text. Never empty. */
  text: string;
  /**
   * Whether this is the pending-decision line rather than a message
   * preview — the row's amber switch. `true` only ever means "the operator
   * owes someone an answer"; it is not a severity, a warning or an error
   * (spec §3). Always `false` in production today, by construction: see
   * this module's doc comment.
   */
  pending: boolean;
}

/** The one string the pending path renders. Fixed, not derived from the
 * event — a gate's own prose is untrusted and unbounded, and the roster
 * line has room for neither. */
const APPROVAL_NEEDED = "Approval needed";

/** The prefix for a message this account sent, per spec §6.1.1's "no sender
 * prefix, except your own". */
const OWN_PREFIX = "You: ";

/**
 * Builds the preview line for one room, or returns `null` when the row must
 * omit the line entirely — spec §6.1.1 is explicit that there is no
 * placeholder string, exactly as for the role/time line above it.
 *
 * `decisionTypes` is the set of event types that mean a decision is
 * pending; production callers pass `customEvents.ts`'s
 * `DECISION_BEARING_EVENT_TYPES`. It is a required parameter rather than a
 * default import so the caller names the set it is trusting, and so tests
 * can prove the mechanism against a fixture type without the production set
 * ever gaining an entry.
 *
 * The pending check comes **first**, and deliberately does not consult
 * `lastMessage`: `Approval needed` replaces whatever the event's text would
 * have been rather than competing with it, so a gate can never leak its own
 * body onto the roster, and the amber cannot be suppressed by a preview
 * that happened to come back empty.
 */
export function composeRoomPreview(
  facts: RoomPreviewFacts,
  decisionTypes: ReadonlySet<string>,
): RoomPreview | null {
  if (facts.lastEventType !== null && decisionTypes.has(facts.lastEventType)) {
    return { text: APPROVAL_NEEDED, pending: true };
  }

  // The core already collapses whitespace and returns `None` rather than an
  // empty string, so this trim is defence in depth, not a second opinion:
  // it is what stops a blank preview from rendering as a bare `You: ` — a
  // line that would look like a bug and say nothing.
  if (facts.lastMessage === null || facts.lastMessage.trim() === "") return null;

  const prefix = facts.lastMessageIsOwn && !facts.lastMessageNamesSender ? OWN_PREFIX : "";
  return { text: `${prefix}${facts.lastMessage}`, pending: false };
}
