// Pure helpers for the composer's edit mode (iOS `ComposerView.swift`).

import type { PendingEdit } from "$lib/stores/editTarget.svelte";

/**
 * Holds the half-typed draft while the field shows the message being edited,
 * so cancelling (or leaving the room) gives it back.
 */
export class EditStash {
  private draft = "";
  private edit: PendingEdit | null = null;

  get editing(): PendingEdit | null {
    return this.edit;
  }

  /** Starts editing; returns the text to show. Re-beginning keeps the first draft. */
  begin(draft: string, edit: PendingEdit): string {
    if (this.edit === null) this.draft = draft;
    this.edit = edit;
    return edit.body;
  }

  /** Stops editing; returns the draft that was kept. */
  end(): string {
    const draft = this.draft;
    this.draft = "";
    this.edit = null;
    return draft;
  }
}

export type SendIntent = "edit" | "attachment" | "text" | "nothing";

/** What Send does. While editing it only ever saves the edit. */
export function sendIntent(edit: PendingEdit | null, staged: boolean, text: string): SendIntent {
  const hasText = text.trim() !== "";
  if (edit) return hasText ? "edit" : "nothing";
  if (staged) return "attachment";
  return hasText ? "text" : "nothing";
}

export function composerPlaceholder(edit: boolean, replySender: string | null): string {
  if (edit) return "Edit message";
  return replySender ? `Reply to ${replySender}…` : "Message…";
}

export function sendLabel(edit: boolean, staged: boolean): string {
  if (edit) return "Save edit";
  return staged ? "Send file" : "Send";
}
