import { describe, expect, it } from "vitest";
import { EditStash, composerPlaceholder, sendIntent, sendLabel } from "./editMode";

const edit = { eventId: "$e", body: "old" };

describe("EditStash", () => {
  it("begin shows the body and keeps the draft", () => {
    const s = new EditStash();
    expect(s.begin("half typed", edit)).toBe("old");
    expect(s.editing).toEqual(edit);
    expect(s.end()).toBe("half typed");
  });
  it("EditStash restores the draft on a room switch", () => {
    const s = new EditStash();
    s.begin("draft", edit);
    expect(s.end()).toBe("draft");
    expect(s.editing).toBeNull();
  });
  it("a second begin keeps the original draft, not the edit text", () => {
    const s = new EditStash();
    s.begin("draft", edit);
    s.begin("old", { eventId: "$f", body: "other" });
    expect(s.end()).toBe("draft");
  });
  it("end without begin returns an empty string", () => {
    expect(new EditStash().end()).toBe("");
  });
});

describe("sendIntent", () => {
  it("never sends while editing", () => {
    expect(sendIntent(edit, true, "new")).toBe("edit");
    expect(sendIntent(edit, true, "   ")).toBe("nothing");
    expect(sendIntent(edit, false, "")).toBe("nothing");
  });
  it("otherwise attachment, text, nothing", () => {
    expect(sendIntent(null, true, "")).toBe("attachment");
    expect(sendIntent(null, false, " hi ")).toBe("text");
    expect(sendIntent(null, false, "  ")).toBe("nothing");
  });
});

describe("placeholder and label", () => {
  it("placeholder", () => {
    expect(composerPlaceholder(true, "Atlas")).toBe("Edit message");
    expect(composerPlaceholder(false, "Atlas")).toBe("Reply to Atlas…");
    expect(composerPlaceholder(false, null)).toBe("Message…");
  });
  it("label", () => {
    expect(sendLabel(true, true)).toBe("Save edit");
    expect(sendLabel(false, true)).toBe("Send file");
    expect(sendLabel(false, false)).toBe("Send");
  });
});
