import type { FirstRunScript } from "$lib/ipc";

/**
 * `core::first_run::script()` as the core returns it, for stories (which have no core to ask). The
 * words are the core's, copied from `first_run.rs`; its own test pins them to iOS's.
 */
export const firstRunScriptFixture: FirstRunScript = {
  agentName: "Atlas",
  greeting: "Hi — I'm Atlas, a sample agent. I'd like to tidy the release notes before today's build.",
  request: "Can I edit docs/release-notes.md?",
  banner: "A sample conversation — nothing here is sent anywhere.",
  typingLabel: "Atlas is typing",
  agentTag: "Agent",
  cardLabel: "Permission",
  cardEventType: "dev.agentpod.permission.request.v1",
  roomId: "demo.atlas",
  initial: "✳",
  approvedTitle: "That's it — approvals from chat.",
  approvedBody: "When an agent needs you, it asks in its room, and the Needs you tab keeps the list.",
  finishLabel: "Get started",
  skipLabel: "Skip",
  card: {
    status: "rendered",
    fields: [
      { label: "File", value: "docs/release-notes.md" },
      { label: "Change", value: "Tidy headings, add today's fixes" },
    ],
    reasoning: null,
    newerVersion: false,
    decision: {
      prompt: "Can I edit docs/release-notes.md?",
      options: [
        { label: "Approve", id: "approve" },
        { label: "Reject", id: "reject" },
      ],
      subject: "demo-permission",
    },
    outcome: null,
  },
  stepDelaysMs: [1100, 900],
};
