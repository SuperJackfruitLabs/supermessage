// How the Needs you inbox is sectioned (iOS `NeedsYouView`). The core decides what needs the
// reader (`roster_needs_you_inbox`); this only names and orders the two groups.

import type { NeedsYouInbox, RosterRow } from "$lib/ipc";

export interface InboxSection {
  id: "decisions" | "invitations";
  title: string;
  count: number;
  rows: RosterRow[];
}

/** Decisions, then invitations; a group with no rows is not drawn. */
export function inboxSections(inbox: NeedsYouInbox): InboxSection[] {
  const sections: InboxSection[] = [
    { id: "decisions", title: "Waiting on you", count: inbox.decisions.length, rows: inbox.decisions },
    { id: "invitations", title: "Invitations", count: inbox.invitations.length, rows: inbox.invitations },
  ];
  return sections.filter((s) => s.rows.length > 0);
}

/** Nothing waits on the reader: decisions and invitations together are none. */
export function isCaughtUp(inbox: NeedsYouInbox): boolean {
  return inbox.count === 0;
}
