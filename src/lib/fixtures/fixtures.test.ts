/**
 * Named scenarios must mean what their names say.
 *
 * A fixture that has drifted from its name is worse than no fixture: a story
 * built on it demonstrates the wrong thing, convincingly. `longUnbrokenToken`
 * containing a space would still render — it would just quietly stop being a
 * test of the wrap guards, and the story would look like proof that they work.
 */
import { describe, expect, it } from "vitest";

import { SENDER_RUN_WINDOW_MS } from "$lib/components/timelineGrouping";
import {
  connectionError,
  connectionLive,
  connectionOffline,
} from "./connection";
import {
  dayDivider,
  encryptedPlaceholder,
  longUnbrokenToken,
  membershipLine,
  ownMessageFailed,
  ownMessageSending,
  reactionsMany,
  reactionsMine,
  senderRun,
} from "./timeline";

describe("timeline scenarios", () => {
  it("senderRun is one sender, twice, inside the run window", () => {
    expect(senderRun).toHaveLength(2);
    const [a, b] = senderRun;
    expect(a.item.sender).toBe(b.item.sender);

    // `timestampMs` is `number | null` on the wire, and a run fixture
    // without timestamps could not exercise the window at all — so this is
    // asserted rather than assumed away with a non-null assertion.
    const first = a.item.timestampMs;
    const second = b.item.timestampMs;
    expect(first).not.toBeNull();
    expect(second).not.toBeNull();

    expect(second! - first!).toBeLessThan(SENDER_RUN_WINDOW_MS);
    expect(second!).toBeGreaterThan(first!);
  });

  it("longUnbrokenToken is actually unbreakable", () => {
    const body = longUnbrokenToken.item.body ?? "";
    expect(body).not.toMatch(/\s/);
    expect(body.length).toBeGreaterThan(100);
  });

  it("ownMessage scenarios are own, and differ only in send state", () => {
    expect(ownMessageSending.item.isOwn).toBe(true);
    expect(ownMessageFailed.item.isOwn).toBe(true);
    expect(ownMessageSending.item.sendState).toBe("notSentYet");
    expect(ownMessageFailed.item.sendState).toBe("sendingFailed");
    expect(ownMessageSending.item.body).toBe(ownMessageFailed.item.body);
  });

  it("encryptedPlaceholder renders a placeholder, not a bubble", () => {
    // Spec §7: a type this build cannot render at all is not worth a
    // bordered object. It is a log line.
    expect(encryptedPlaceholder.view.render).toBe("placeholder");
  });

  it("dayDivider is a divider and membershipLine is a system line", () => {
    expect(dayDivider.view.render).toBe("dateDivider");
    expect(membershipLine.view.render).toBe("system");
  });

  it("reactionsMine has exactly one reaction by the reader", () => {
    const mine = reactionsMine.item.reactions.filter((r) => r.byMe);
    expect(mine).toHaveLength(1);
    expect(reactionsMine.item.reactions.length).toBeGreaterThan(1);
  });

  it("reactionsMany includes a key that is not a single emoji", () => {
    // `Reaction.key`'s own doc comment warns that the key is arbitrary
    // sender-controlled text. A fixture set of pure emoji would never
    // exercise that.
    expect(reactionsMany.item.reactions.length).toBeGreaterThanOrEqual(5);
    expect(reactionsMany.item.reactions.some((r) => !/^\p{Emoji}+$/u.test(r.displayKey))).toBe(true);
  });

  it("every timeline scenario carries a view the core would have decided", () => {
    // The app decides nothing (AGENTS.md rule 1), so a fixture without a
    // view is not a state the app can be in.
    for (const row of [
      ...senderRun,
      dayDivider,
      ownMessageSending,
      ownMessageFailed,
      longUnbrokenToken,
      encryptedPlaceholder,
      reactionsMine,
      reactionsMany,
      membershipLine,
    ]) {
      expect(row.view.render).toBeTruthy();
      expect(row.view.render).not.toBe("none");
    }
  });
});

describe("connection scenarios", () => {
  it("only connectionLive is live, because only it renders nothing", () => {
    expect(connectionLive.state).toBe("live");
    expect(connectionOffline.state).not.toBe("live");
    expect(connectionError.state).not.toBe("live");
  });

  it("connectionError carries a message, since that is the state that has one", () => {
    expect(connectionError.message).toBeTruthy();
    expect(connectionLive.message).toBeNull();
  });
});
