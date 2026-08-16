import { describe, expect, it } from "vitest";
import { projectSearchResults, roomLabel, snippet, SNIPPET_MAX } from "./searchView";
import type { RoomSummary, SearchResult } from "$lib/ipc";

function room(id: string, name: string): RoomSummary {
  return {
    id,
    name,
    avatarUrl: null,
    unread: 0,
    lastMessage: null,
    lastMessageIsOwn: false,
    lastMessageNamesSender: false,
    lastEventType: null,
    lastActivityMs: null,
    membership: "joined",
  };
}

const HIT: SearchResult = {
  eventId: "$one",
  roomId: "!echo:example.org",
  sender: "@agent_echo:example.org",
  body: "the migration finished",
  timestampMs: 1_700_000_000_000,
};

describe("snippet", () => {
  it("collapses a multi-line message to one line", () => {
    // A hit inside a fenced block or a long answer is the common case in these
    // rooms; a row that grows to forty lines stops being a list.
    expect(snippet("one\n\n  two   three\n")).toBe("one two three");
  });

  it("bounds a long message with an ellipsis", () => {
    const long = "x".repeat(SNIPPET_MAX + 50);
    const result = snippet(long);

    expect(result).toHaveLength(SNIPPET_MAX);
    expect(result.endsWith("…")).toBe(true);
  });

  it("leaves a short message alone", () => {
    expect(snippet("short")).toBe("short");
  });
});

describe("roomLabel", () => {
  it("names the room from the roster", () => {
    expect(roomLabel("!echo:example.org", [room("!echo:example.org", "analyst-echo")])).toBe(
      "analyst-echo",
    );
  });

  it("falls back to the room id rather than inventing a name", () => {
    // Search reaches rooms the current space filter hides, so a hit from a
    // room the roster does not list is a real case. An id is ugly and true.
    expect(roomLabel("!elsewhere:example.org", [])).toBe("!elsewhere:example.org");
  });
});

describe("projectSearchResults", () => {
  it("carries what a row needs and nothing else", () => {
    const [view] = projectSearchResults([HIT], [room("!echo:example.org", "analyst-echo")]);

    expect(view).toEqual({
      eventId: "$one",
      roomId: "!echo:example.org",
      roomLabel: "analyst-echo",
      sender: "@agent_echo:example.org",
      snippet: "the migration finished",
      timestampMs: 1_700_000_000_000,
    });
  });
});
