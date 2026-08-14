import { describe, expect, it } from "vitest";
import type { StagedAttachment } from "$lib/ipc";
import {
  attachmentFailure,
  formatAttachmentSize,
  sanitizeFilename,
  sendCaveat,
  stagedStripView,
  StagedAttachmentTracker,
} from "./stagedAttachment";

function staged(overrides: Partial<StagedAttachment> = {}): StagedAttachment {
  return {
    token: "tok",
    filename: "holiday.png",
    sizeBytes: 4096,
    mime: "image/png",
    ...overrides,
  };
}

describe("formatAttachmentSize", () => {
  // These five are `core::attachments::format_bytes`'s own test cases,
  // copied verbatim. The two formatters have to agree: an
  // `attachmentTooLarge` message names the limit in the core's units and is
  // rendered directly under a strip that named the file's size in these.
  it("matches the core's formatter, case for case", () => {
    expect(formatAttachmentSize(0)).toBe("0 B");
    expect(formatAttachmentSize(512)).toBe("512 B");
    expect(formatAttachmentSize(1024)).toBe("1.0 KiB");
    expect(formatAttachmentSize(52_428_800)).toBe("50.0 MiB");
    expect(formatAttachmentSize(3 * 1024 * 1024 * 1024)).toBe("3.0 GiB");
  });

  it("keeps sub-kibibyte sizes as whole bytes with no decimal", () => {
    expect(formatAttachmentSize(1)).toBe("1 B");
    expect(formatAttachmentSize(1023)).toBe("1023 B");
  });

  it("renders a 40MB file the way the strip has to show it", () => {
    // The screenshot case: 40 MiB must read `40.0 MiB`, not `41943040` and
    // not `41.9 MB`.
    expect(formatAttachmentSize(40 * 1024 * 1024)).toBe("40.0 MiB");
  });

  it("uses binary units, so a file of exactly the limit never prints as over it", () => {
    // Synapse's default max_upload_size. In decimal units this is "52.4 MB"
    // against a "50 MB" limit — a contradiction on screen.
    expect(formatAttachmentSize(52_428_800)).toBe("50.0 MiB");
  });

  it("stops at TiB rather than inventing a larger unit", () => {
    expect(formatAttachmentSize(5 * 1024 ** 4)).toBe("5.0 TiB");
    expect(formatAttachmentSize(2048 * 1024 ** 4)).toBe("2048.0 TiB");
  });

  it("says so rather than claiming 0 B when the number isn't one", () => {
    expect(formatAttachmentSize(Number.NaN)).toBe("unknown size");
    expect(formatAttachmentSize(Number.POSITIVE_INFINITY)).toBe("unknown size");
    expect(formatAttachmentSize(-1)).toBe("unknown size");
  });
});

describe("sanitizeFilename", () => {
  it("leaves an ordinary filename exactly as it is", () => {
    expect(sanitizeFilename("quarterly-report.pdf")).toBe("quarterly-report.pdf");
  });

  it("leaves non-Latin filenames alone", () => {
    // Only the explicit bidi *formatters* are stripped; RTL letters are the
    // normal case and lay out correctly on their own.
    expect(sanitizeFilename("تقرير.pdf")).toBe("تقرير.pdf");
    expect(sanitizeFilename("報告書.pdf")).toBe("報告書.pdf");
  });

  it("collapses a newline in a filename instead of letting it break the strip", () => {
    // Legal on POSIX: any byte but `/` and NUL.
    expect(sanitizeFilename("two\nlines.txt")).toBe("two lines.txt");
    expect(sanitizeFilename("tabbed\t\tname.txt")).toBe("tabbed name.txt");
  });

  it("strips control characters", () => {
    expect(sanitizeFilename("bell\u0007and\u007Fdelete.txt")).toBe("bellanddelete.txt");
  });

  it("strips the right-to-left override that makes an executable look like an image", () => {
    // `holiday<RLO>gnp.exe` renders as `holidayexe.png` in a terminal or a
    // browser. This strip is the confirm step; what it shows must be what
    // gets sent.
    const spoof = "holiday\u202Egnp.exe";
    const clean = sanitizeFilename(spoof);
    expect(clean).toBe("holidaygnp.exe");
    expect(clean).not.toContain("\u202E");
    expect(clean.endsWith(".exe")).toBe(true);
  });

  it("strips isolates and marks too, not just the override", () => {
    expect(sanitizeFilename("a\u2066b\u2069c\u200Ed\u200Fe.bin")).toBe("abcde.bin");
  });

  it("never returns an empty string", () => {
    expect(sanitizeFilename("")).toBe("Unnamed file");
    expect(sanitizeFilename("   ")).toBe("Unnamed file");
    expect(sanitizeFilename("\u202A\u202B\u202C")).toBe("Unnamed file");
  });

  it("bounds a hostile filename at 120 code points, with no ellipsis of its own", () => {
    const long = `${"a".repeat(500)}.png`;
    const bounded = sanitizeFilename(long);
    expect([...bounded]).toHaveLength(120);
    expect(bounded.endsWith("…")).toBe(false);
  });

  it("bounds by code point, so a surrogate pair is never cut in half", () => {
    // The leading "a" is load-bearing, and this project's own history is why
    // (AGENTS.md, "a test that has never failed"): with an *even* cap and a
    // two-code-unit character, a naive `slice(0, 120)` lands exactly on a
    // pair boundary and produces no lone surrogate at all — the assertion
    // below would then hold for the broken implementation too. One odd
    // character in front moves every pair off the boundary, so a code-unit
    // slice cuts the 60th rocket in half.
    const bounded = sanitizeFilename(`a${"🚀".repeat(200)}`);
    expect([...bounded]).toHaveLength(120);
    // With the `u` flag this range matches *lone* surrogates only — a
    // properly paired astral character is one code point above 0xFFFF and
    // does not match. So this is precisely the "cut a pair in half" check.
    expect(bounded).not.toMatch(/[\uD800-\uDFFF]/u);
    expect(bounded).toBe(`a${"🚀".repeat(119)}`);
  });
});

describe("stagedStripView", () => {
  it("shows the size, the dimensions when the core reported them, and the sniffed type", () => {
    expect(stagedStripView(staged({ sizeBytes: 4 * 1024 * 1024, width: 1920, height: 1080 }))).toEqual({
      filename: "holiday.png",
      summary: "4.0 MiB · 1920 × 1080 · image/png",
    });
  });

  it("shows size and type for a non-image, where the dimension keys are absent rather than null", () => {
    expect(stagedStripView(staged({ filename: "report.pdf", mime: "application/pdf", sizeBytes: 250_000 }))).toEqual({
      filename: "report.pdf",
      summary: "244.1 KiB · application/pdf",
    });
  });

  it("keeps the sniffed type visible when the filename lies about it", () => {
    // The confirm step's real job. The core sniffs from content, so a
    // `.png` that is really an unidentified blob says so on this line even
    // though the name and the truncation both hide it.
    expect(stagedStripView(staged({ filename: "holiday.png", mime: "application/octet-stream" })).summary).toBe(
      "4.0 KiB · application/octet-stream",
    );
  });

  it("declines to render half a dimension pair", () => {
    expect(stagedStripView(staged({ width: 1920 })).summary).toBe("4.0 KiB · image/png");
    expect(stagedStripView(staged({ height: 1080 })).summary).toBe("4.0 KiB · image/png");
    expect(stagedStripView(staged({ width: 0, height: 0 })).summary).toBe("4.0 KiB · image/png");
  });

  it("omits the type rather than trailing a separator when there isn't one", () => {
    expect(stagedStripView(staged({ mime: "" })).summary).toBe("4.0 KiB");
  });

  it("sanitizes the filename it renders", () => {
    expect(stagedStripView(staged({ filename: "spoof\u202Egnp.exe" })).filename).toBe("spoofgnp.exe");
  });
});

describe("sendCaveat", () => {
  it("says nothing when Send can only mean one thing", () => {
    expect(sendCaveat(false, false)).toBeNull();
  });

  it("names the draft text Send is not going to include", () => {
    const caveat = sendCaveat(true, false);
    expect(caveat).toMatch(/not your message text/i);
    expect(caveat).toMatch(/stays in the draft/i);
  });

  it("names the reply the attachment will not be", () => {
    // `attachment_send` takes no `in_reply_to`, so a staged file sent while
    // a reply is pending is an ordinary message — and the reply target
    // survives, which is the half a reader would otherwise have to guess at.
    const caveat = sendCaveat(false, true);
    expect(caveat).toMatch(/not as a reply/i);
    expect(caveat).toMatch(/still waiting/i);
  });

  it("covers both at once rather than picking one", () => {
    const caveat = sendCaveat(true, true) ?? "";
    expect(caveat).toMatch(/message text/i);
    expect(caveat).toMatch(/reply/i);
    expect(caveat).not.toBe(sendCaveat(true, false));
    expect(caveat).not.toBe(sendCaveat(false, true));
  });
});

describe("attachmentFailure", () => {
  it("passes the core's too-large message through, because it names both sizes", () => {
    const failure = attachmentFailure(
      {
        kind: "attachmentTooLarge",
        message: "that file is 200.0 MiB, but this homeserver accepts at most 50.0 MiB",
      },
      "attach",
    );
    expect(failure.label).toBe("File too large");
    expect(failure.message).toContain("200.0 MiB");
    expect(failure.message).toContain("50.0 MiB");
    // Sentence case (spec §10) — the core writes lowercase fragments.
    expect(failure.message.startsWith("That file is 200.0 MiB")).toBe(true);
  });

  it("tells the two attachment refusals apart, in the way the reader has to act on", () => {
    // These are the two kinds the design added, and collapsing them into one
    // message is the specific mistake worth a test: one means the file is
    // gone, the other means the file is fine and the room moved.
    const gone = attachmentFailure({ kind: "unknownAttachment", message: "that file is no longer staged; pick it again" }, "send");
    const moved = attachmentFailure({ kind: "roomChanged", message: "wrong room: requested !b, but !a is now focused" }, "send");

    expect(gone.label).not.toBe(moved.label);
    expect(gone.message).not.toBe(moved.message);
    expect(gone.message).toMatch(/no longer staged/i);
    expect(gone.message).toMatch(/attach it again/i);
    expect(moved.message).toMatch(/switched rooms/i);
  });

  it("words a room switch differently depending on which half of the flow it broke", () => {
    const whileAttaching = attachmentFailure({ kind: "roomChanged", message: "wrong room" }, "attach");
    const whileSending = attachmentFailure({ kind: "roomChanged", message: "wrong room" }, "send");
    expect(whileAttaching.message).toMatch(/switched rooms/i);
    expect(whileSending.message).toMatch(/^Not sent —/);
    expect(whileAttaching.message).not.toBe(whileSending.message);
  });

  it("keeps a store error's own text, which says what actually went wrong", () => {
    const failure = attachmentFailure(
      { kind: "store", message: "cannot read that file: permission denied (os error 13)" },
      "attach",
    );
    expect(failure.label).toBe("Couldn't attach");
    expect(failure.message).toBe("Cannot read that file: permission denied (os error 13)");
  });

  it("labels a generic failure by the half of the flow it came from", () => {
    expect(attachmentFailure({ kind: "network", message: "" }, "attach").label).toBe("Couldn't attach");
    expect(attachmentFailure({ kind: "network", message: "" }, "send").label).toBe("Send failed");
  });

  it("still says something useful for a rejection that isn't a CoreError at all", () => {
    const failure = attachmentFailure(new TypeError("undefined is not a function"), "send");
    expect(failure.label).toBe("Send failed");
    expect(failure.message).toBe("That file wasn't sent. Attach it again.");
    expect(attachmentFailure(undefined, "attach").message).toBe("That file couldn't be attached.");
  });

  it("bounds a message the core interpolated an OS string into", () => {
    const failure = attachmentFailure({ kind: "store", message: "x".repeat(5000) }, "attach");
    expect(failure.message.length).toBeLessThanOrEqual(301);
    expect(failure.message.endsWith("…")).toBe(true);
  });
});

describe("StagedAttachmentTracker", () => {
  it("holds nothing to begin with", () => {
    expect(new StagedAttachmentTracker().stagedFor("!a:x.org")).toBeNull();
  });

  it("shows a staged file to the room it was staged for", () => {
    const tracker = new StagedAttachmentTracker();
    const file = staged({ token: "tok-a" });
    tracker.stage("!a:x.org", file);
    expect(tracker.stagedFor("!a:x.org")).toBe(file);
  });

  // ---- the room-scoping rule ------------------------------------------
  //
  // The reason this class exists. `Composer` is not remounted on a room
  // switch (see `draftTracker.ts`), so without this the composer would
  // happily show — and Send would happily send — one room's file under
  // another room's header. That class of bug has shipped here twice, and
  // for an attachment it means sending a file to the wrong people with no
  // redaction command to undo it.

  it("never shows one room's staged file to another room", () => {
    const tracker = new StagedAttachmentTracker();
    tracker.stage("!a:x.org", staged({ token: "tok-a" }));
    expect(tracker.stagedFor("!b:x.org")).toBeNull();
  });

  it("hides a staged file again once the reader has switched away", () => {
    const tracker = new StagedAttachmentTracker();
    tracker.stage("!a:x.org", staged({ token: "tok-a" }));
    tracker.switchTo("!b:x.org");
    expect(tracker.stagedFor("!b:x.org")).toBeNull();
    // And not by hiding it *conditionally*: it is gone, not merely
    // out of view, so switching back does not resurrect a token the core
    // has already dropped (`StagedAttachments::retain_room`).
    expect(tracker.stagedFor("!a:x.org")).toBeNull();
  });

  it("hands the abandoned attachment back on a switch, so its token can be discarded", () => {
    const tracker = new StagedAttachmentTracker();
    const file = staged({ token: "tok-a" });
    tracker.stage("!a:x.org", file);
    expect(tracker.switchTo("!b:x.org")).toBe(file);
    // Nothing left to discard the second time.
    expect(tracker.switchTo("!c:x.org")).toBeNull();
  });

  it("is a no-op when the switch effect re-runs for the room already focused", () => {
    // `Composer`'s room-switch effect calls this unconditionally, the same
    // way it calls `DraftTracker.switchTo`. A same-room re-run must not
    // throw away a file the reader just attached.
    const tracker = new StagedAttachmentTracker();
    const file = staged({ token: "tok-a" });
    tracker.stage("!a:x.org", file);
    expect(tracker.switchTo("!a:x.org")).toBeNull();
    expect(tracker.stagedFor("!a:x.org")).toBe(file);
  });

  it("replaces rather than accumulates, and hands back the superseded file", () => {
    // The core's own judgement call (`StagedAttachments::insert_at`): a
    // reader who opens the picker with a file already staged has changed
    // their mind. The superseded token is dropped core-side, so holding a
    // list here would be holding tokens that no longer resolve.
    const tracker = new StagedAttachmentTracker();
    const first = staged({ token: "tok-1", filename: "first.png" });
    const second = staged({ token: "tok-2", filename: "second.png" });
    tracker.stage("!a:x.org", first);
    expect(tracker.stage("!a:x.org", second)).toBe(first);
    expect(tracker.stagedFor("!a:x.org")).toBe(second);
  });

  it("re-attributes a file staged while a different room was held", () => {
    // A `sm://attachment/staged` event names no room; the composer
    // attributes it to whichever room is focused when it arrives.
    const tracker = new StagedAttachmentTracker();
    const first = staged({ token: "tok-1" });
    const second = staged({ token: "tok-2" });
    tracker.stage("!a:x.org", first);
    expect(tracker.stage("!b:x.org", second)).toBe(first);
    expect(tracker.stagedFor("!a:x.org")).toBeNull();
    expect(tracker.stagedFor("!b:x.org")).toBe(second);
  });

  it("clears only the attachment a finished send belonged to", () => {
    // A send is asynchronous. If it resolves after the reader has already
    // attached something else, clearing the strip would delete a file they
    // are still expecting to send.
    const tracker = new StagedAttachmentTracker();
    const sent = staged({ token: "tok-sent" });
    const next = staged({ token: "tok-next" });
    tracker.stage("!a:x.org", sent);
    tracker.stage("!a:x.org", next);

    expect(tracker.takeToken(sent.token)).toBeNull();
    expect(tracker.stagedFor("!a:x.org")).toBe(next);
    expect(tracker.takeToken(next.token)).toBe(next);
    expect(tracker.stagedFor("!a:x.org")).toBeNull();
  });

  it("has nothing to clear by token once a switch has already dropped it", () => {
    const tracker = new StagedAttachmentTracker();
    const file = staged({ token: "tok-a" });
    tracker.stage("!a:x.org", file);
    tracker.switchTo("!b:x.org");
    expect(tracker.takeToken("tok-a")).toBeNull();
  });

  it("gives up what it holds on take, whichever room it belonged to", () => {
    const tracker = new StagedAttachmentTracker();
    const file = staged({ token: "tok-a" });
    tracker.stage("!a:x.org", file);
    expect(tracker.take()).toBe(file);
    expect(tracker.take()).toBeNull();
    expect(tracker.stagedFor("!a:x.org")).toBeNull();
  });
});
