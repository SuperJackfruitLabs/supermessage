import type { AgentState, AvatarRole } from "$lib/ipc";

/**
 * The seven palette roles the core draws an agent tile in, as Tailwind classes
 * (iOS `RoomAvatar.colour`). Written out in full so the class scanner sees each
 * one; the core never hands out amber or red (`signal`, `danger`).
 */
const BG: Record<AvatarRole, string> = {
  accent: "bg-accent",
  accentSoft: "bg-accent-soft",
  accentContent: "bg-accent-content",
  ok: "bg-ok",
  contentMuted: "bg-content-muted",
  surface: "bg-surface",
  surfaceRaised: "bg-surface-raised",
};

const TEXT: Record<AvatarRole, string> = {
  accent: "text-accent",
  accentSoft: "text-accent-soft",
  accentContent: "text-accent-content",
  ok: "text-ok",
  contentMuted: "text-content-muted",
  surface: "text-surface",
  surfaceRaised: "text-surface-raised",
};

/** The class that paints `role` as a ground (`bg`) or as ink (`text`). */
export function avatarRoleClass(role: AvatarRole, use: "bg" | "text"): string {
  return use === "bg" ? BG[role] : TEXT[role];
}

/**
 * The state dot's colour class, or `null` for none (iOS `RoomAvatar.dotColour`).
 * Quiet draws nothing: absence is not a state worth a mark of its own.
 */
export function dotClass(state: AgentState | null): string | null {
  switch (state) {
    case "needsYou":
      // A pending decision: the one meaning amber has.
      return "bg-signal";
    case "active":
      return "bg-ok";
    case "idle":
      return "bg-content-faint";
    case "quiet":
    case null:
      return null;
  }
}

const WORD: Record<AgentState, string> = {
  needsYou: "needs you",
  active: "active",
  idle: "idle",
  quiet: "quiet",
};

/**
 * What the avatar says to a screen reader: "Agent" for an agent's room, then
 * the state's word when a dot is drawn (iOS `accessibilityText`), joined by ", ".
 */
export function avatarLabel(describesAgent: boolean, state: AgentState | null): string {
  const parts: string[] = [];
  if (describesAgent) parts.push("Agent");
  if (state !== null && dotClass(state) !== null) parts.push(WORD[state]);
  return parts.join(", ");
}

/**
 * The state the avatar's dot may draw: when the reader wants it and it means
 * something — always for a pending decision, otherwise only for an agent's room
 * (iOS `RoomRowView.drawsState`).
 */
export function drawnState(
  state: AgentState,
  describesAgent: boolean,
  showsState: boolean,
): AgentState | null {
  return showsState && (describesAgent || state === "needsYou") ? state : null;
}

/** The unread badge's text: "99+" over 99. */
export function unreadLabel(count: number): string {
  return count > 99 ? "99+" : String(count);
}
