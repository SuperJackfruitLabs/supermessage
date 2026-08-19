// The emoji a reaction can be, and how to find one by typing.
//
// Reacting was six hard-coded keys plus whatever someone else had already
// used, which means the vocabulary of a room was fixed by whoever reacted
// first. This is the rest of it.
//
// **A curated list, not a Unicode dump.** A full emoji dataset is ~1800
// entries with skin-tone and ZWJ variants, and shipping one would add a
// megabyte to a desktop app so that somebody can react with a flag. What is
// here is the set that actually gets used in a working conversation —
// approval, refusal, attention, state, and the small vocabulary of things
// that go wrong — each with the words a person would reach for. Add to it
// when a real conversation wants something it lacks; that is a better signal
// than completeness.
//
// The search is a plain substring match over name and keywords. Fuzzy
// matching sounds better and reads worse: with a list this size, "fire"
// ranking `🔥` below something that merely contains f-i-r-e in sequence is a
// bug nobody can explain.

export interface Emoji {
  /** The character itself — this is what gets sent as the reaction key. */
  char: string;
  /** What it is called, shown as the accessible name. */
  name: string;
  /** Other words a person might type to find it. */
  keywords: string[];
}

/**
 * The six offered inline on every message.
 *
 * Kept as the fast path — one click, no panel — and deliberately unchanged:
 * these are the ones a reader reaches for without thinking, and putting them
 * behind a picker would make the common case slower to serve the rare one.
 */
/**
 * Approval, refusal, attention, thanks — the working vocabulary of a room
 * whose other occupants are agents, and what these rooms observably use. The
 * previous six were the generic social set: 🎉 and 🙏 went unused here while
 * ✅ and 👀 were being picked out of the full picker every time.
 *
 * Kept identical to iOS's `quickReactions`. Two clients offering different
 * quick reactions is two different apps.
 */
export const QUICK_REACTIONS = ["✅", "👍", "❌", "👀"] as const;

export const EMOJI: readonly Emoji[] = [
  // Approval, refusal, attention — the working vocabulary of an agent room.
  { char: "👍", name: "thumbs up", keywords: ["yes", "approve", "ok", "good", "lgtm"] },
  { char: "👎", name: "thumbs down", keywords: ["no", "reject", "deny", "bad"] },
  { char: "✅", name: "check mark", keywords: ["done", "yes", "approve", "pass", "green"] },
  { char: "❌", name: "cross mark", keywords: ["no", "fail", "reject", "stop", "red"] },
  { char: "👀", name: "eyes", keywords: ["look", "watching", "review", "seen"] },
  { char: "🚀", name: "rocket", keywords: ["ship", "deploy", "launch", "fast"] },
  { char: "🔥", name: "fire", keywords: ["hot", "burning", "great", "incident"] },
  { char: "⚠️", name: "warning", keywords: ["careful", "caution", "alert"] },
  { char: "🛑", name: "stop sign", keywords: ["halt", "stop", "block"] },
  { char: "🐛", name: "bug", keywords: ["defect", "issue", "broken"] },
  { char: "🎯", name: "target", keywords: ["exact", "precise", "goal", "bullseye"] },
  { char: "💡", name: "light bulb", keywords: ["idea", "insight", "suggestion"] },
  { char: "📌", name: "pushpin", keywords: ["pin", "important", "keep"] },
  { char: "🔒", name: "locked", keywords: ["secure", "private", "closed"] },
  { char: "🔓", name: "unlocked", keywords: ["open", "insecure"] },
  { char: "⏳", name: "hourglass", keywords: ["wait", "waiting", "pending", "slow"] },
  { char: "⏰", name: "alarm clock", keywords: ["time", "deadline", "late"] },
  { char: "🧹", name: "broom", keywords: ["clean", "cleanup", "tidy"] },
  { char: "🧪", name: "test tube", keywords: ["test", "experiment", "lab"] },
  { char: "📈", name: "chart increasing", keywords: ["up", "growth", "better"] },
  { char: "📉", name: "chart decreasing", keywords: ["down", "loss", "worse"] },
  { char: "💰", name: "money bag", keywords: ["cost", "budget", "spend", "cash"] },
  { char: "🏆", name: "trophy", keywords: ["win", "won", "best", "award"] },
  { char: "🥇", name: "first place", keywords: ["gold", "first", "winner"] },
  { char: "🧠", name: "brain", keywords: ["think", "smart", "clever", "reasoning"] },
  { char: "🤖", name: "robot", keywords: ["bot", "agent", "machine", "ai"] },
  { char: "🛠️", name: "hammer and wrench", keywords: ["tools", "fix", "build", "work"] },
  { char: "🔧", name: "wrench", keywords: ["fix", "repair", "config"] },
  { char: "📦", name: "package", keywords: ["build", "release", "box", "ship"] },
  { char: "🗑️", name: "wastebasket", keywords: ["delete", "trash", "remove"] },
  { char: "📝", name: "memo", keywords: ["note", "write", "doc", "notes"] },
  { char: "📄", name: "page", keywords: ["file", "document", "doc"] },
  { char: "🔗", name: "link", keywords: ["url", "chain", "reference"] },
  { char: "🔍", name: "magnifying glass", keywords: ["search", "find", "look", "investigate"] },
  { char: "💾", name: "floppy disk", keywords: ["save", "disk", "storage"] },
  { char: "🖥️", name: "desktop computer", keywords: ["machine", "host", "server", "node"] },
  { char: "☁️", name: "cloud", keywords: ["cloud", "remote", "hosted"] },
  { char: "⚡", name: "high voltage", keywords: ["fast", "power", "quick", "energy"] },
  { char: "🌐", name: "globe", keywords: ["web", "network", "internet", "world"] },

  // People and faces.
  { char: "😀", name: "grinning face", keywords: ["happy", "smile", "grin"] },
  { char: "😂", name: "face with tears of joy", keywords: ["laugh", "funny", "lol"] },
  { char: "🙂", name: "slightly smiling face", keywords: ["smile", "happy", "ok"] },
  { char: "😉", name: "winking face", keywords: ["wink", "joke"] },
  { char: "😅", name: "grinning face with sweat", keywords: ["nervous", "phew", "relief"] },
  { char: "🤔", name: "thinking face", keywords: ["hmm", "think", "unsure", "consider"] },
  { char: "😐", name: "neutral face", keywords: ["meh", "flat", "indifferent"] },
  { char: "🙃", name: "upside-down face", keywords: ["irony", "sarcasm", "oh well"] },
  { char: "😬", name: "grimacing face", keywords: ["awkward", "yikes", "oops"] },
  { char: "😴", name: "sleeping face", keywords: ["asleep", "idle", "zzz", "tired"] },
  { char: "🤯", name: "exploding head", keywords: ["mind blown", "wow", "shock"] },
  { char: "😮", name: "face with open mouth", keywords: ["surprise", "wow", "oh", "shock"] },
  { char: "😱", name: "screaming face", keywords: ["fear", "panic", "shock"] },
  { char: "😢", name: "crying face", keywords: ["sad", "tears", "upset"] },
  { char: "😡", name: "enraged face", keywords: ["angry", "mad", "furious"] },
  { char: "🥳", name: "partying face", keywords: ["celebrate", "party", "yay"] },
  { char: "😎", name: "smiling face with sunglasses", keywords: ["cool", "confident"] },
  { char: "🤝", name: "handshake", keywords: ["deal", "agree", "partner"] },
  { char: "🙏", name: "folded hands", keywords: ["thanks", "please", "gratitude"] },
  { char: "👏", name: "clapping hands", keywords: ["applause", "well done", "bravo"] },
  { char: "🙌", name: "raising hands", keywords: ["celebrate", "praise", "yay"] },
  { char: "👋", name: "waving hand", keywords: ["hello", "hi", "bye", "wave"] },
  { char: "✋", name: "raised hand", keywords: ["stop", "wait", "hand"] },
  { char: "👌", name: "ok hand", keywords: ["ok", "fine", "perfect"] },
  { char: "🤞", name: "crossed fingers", keywords: ["hope", "luck", "fingers crossed"] },
  { char: "💪", name: "flexed biceps", keywords: ["strong", "power", "effort"] },
  { char: "🫡", name: "saluting face", keywords: ["yes sir", "acknowledged", "salute", "ack"] },

  // Hearts and marks.
  { char: "❤️", name: "red heart", keywords: ["love", "like", "heart"] },
  { char: "💜", name: "purple heart", keywords: ["love", "heart", "purple"] },
  { char: "💔", name: "broken heart", keywords: ["sad", "broken", "heartbreak"] },
  { char: "⭐", name: "star", keywords: ["favourite", "favorite", "star", "good"] },
  { char: "✨", name: "sparkles", keywords: ["magic", "new", "shiny", "clean"] },
  { char: "🎉", name: "party popper", keywords: ["celebrate", "hooray", "launch", "party"] },
  { char: "🎊", name: "confetti ball", keywords: ["celebrate", "party"] },
  { char: "💯", name: "hundred points", keywords: ["100", "perfect", "agree", "full"] },
  { char: "❓", name: "question mark", keywords: ["question", "ask", "why"] },
  { char: "❗", name: "exclamation mark", keywords: ["important", "urgent", "attention"] },
  { char: "➕", name: "plus", keywords: ["add", "more", "plus one"] },
  { char: "➖", name: "minus", keywords: ["remove", "less", "minus"] },
  { char: "🔁", name: "repeat", keywords: ["retry", "again", "loop", "rerun"] },
  { char: "⏸️", name: "pause", keywords: ["pause", "hold", "wait"] },
  { char: "▶️", name: "play", keywords: ["start", "run", "go", "resume"] },

  // Food, weather, and the rest of being a person.
  { char: "☕", name: "hot beverage", keywords: ["coffee", "tea", "break"] },
  { char: "🍕", name: "pizza", keywords: ["food", "lunch", "dinner"] },
  { char: "🍺", name: "beer", keywords: ["drink", "pub", "friday"] },
  { char: "🌞", name: "sun", keywords: ["sunny", "day", "morning"] },
  { char: "🌧️", name: "rain", keywords: ["rain", "weather", "wet"] },
  { char: "🌙", name: "moon", keywords: ["night", "late", "evening"] },
  { char: "🐕", name: "dog", keywords: ["dog", "pet", "animal"] },
  { char: "🐈", name: "cat", keywords: ["cat", "pet", "animal"] },
];

/** How many results the picker shows at once. */
export const SEARCH_LIMIT = 60;

/**
 * The emoji matching `query`, best first.
 *
 * An empty query is not an empty result — it is the whole list, which is what
 * makes the picker usable by browsing rather than only by typing.
 *
 * Ranking is: a name that starts with the query, then a name that contains
 * it, then a keyword match. That ordering is the reason "fire" puts 🔥 first
 * instead of whichever entry happens to be earliest in the array.
 */
export function searchEmoji(query: string, limit: number = SEARCH_LIMIT): Emoji[] {
  const q = query.trim().toLowerCase();
  if (q === "") return EMOJI.slice(0, limit);

  // Typing (or pasting) the character itself finds it — the one case where
  // the query is not a word.
  const literal = EMOJI.filter((e) => e.char === query.trim());
  if (literal.length > 0) return literal;

  const startsWith: Emoji[] = [];
  const contains: Emoji[] = [];
  const byKeyword: Emoji[] = [];

  for (const emoji of EMOJI) {
    const name = emoji.name.toLowerCase();
    if (name.startsWith(q)) startsWith.push(emoji);
    else if (name.includes(q)) contains.push(emoji);
    else if (emoji.keywords.some((k) => k.toLowerCase().includes(q))) byKeyword.push(emoji);
  }

  return [...startsWith, ...contains, ...byKeyword].slice(0, limit);
}
