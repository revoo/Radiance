/** "Just now", "5m", "3h", "Yesterday at 3:12 PM", "Oct 27 at 9:00 AM" — in the world's time zone. */
export function relativeTime(at: number, now: number, timeZone = "America/Chicago"): string {
  const diff = Math.max(0, now - at);
  const min = Math.floor(diff / 60_000);
  if (min < 1) return "Just now";
  if (min < 60) return `${min}m`;
  const hours = Math.floor(min / 60);
  if (hours < 24) return `${hours}h`;
  const time = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZone }).format(at);
  const day = (t: number) => new Intl.DateTimeFormat("en-CA", { timeZone }).format(t);
  if (day(at) === day(now - 86_400_000)) return `Yesterday at ${time}`;
  const date = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone }).format(at);
  return `${date} at ${time}`;
}

export function fullTime(at: number, timeZone = "America/Chicago"): string {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "full", timeStyle: "short", timeZone }).format(at);
}

const PALETTE = ["#e8590c", "#1971c2", "#2f9e44", "#9c36b5", "#c2255c", "#0c8599", "#5f3dc4", "#e67700", "#364fc7"];

export function colorFor(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length] ?? "#e8590c";
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

export const REACTION_EMOJI: Record<string, string> = {
  LIKE: "👍",
  LOVE: "❤️",
  CARE: "🤗",
  HAHA: "😆",
  WOW: "😮",
  SAD: "😢",
  ANGRY: "😡",
};

export const REACTION_LABEL: Record<string, string> = {
  LIKE: "Like",
  LOVE: "Love",
  CARE: "Care",
  HAHA: "Haha",
  WOW: "Wow",
  SAD: "Sad",
  ANGRY: "Angry",
};

export const REACTION_COLOR: Record<string, string> = {
  LIKE: "text-[#1877f2]",
  LOVE: "text-[#e41e3f]",
  CARE: "text-[#f7b125]",
  HAHA: "text-[#f7b125]",
  WOW: "text-[#f7b125]",
  SAD: "text-[#f7b125]",
  ANGRY: "text-[#e9710f]",
};

/** "Sarah, Jake and 3 others" from a reaction summary. */
export function whoReacted(total: number, recent: string[], mine: boolean): string {
  const names = mine ? ["You", ...recent] : recent;
  if (names.length === 0) return String(total);
  const shown = names.slice(0, 2);
  const rest = total - shown.length;
  if (rest <= 0) return shown.join(" and ");
  return `${shown.join(", ")} and ${rest} other${rest > 1 ? "s" : ""}`;
}

/** One to three emoji and nothing else: Messenger shows those big, without a bubble. */
export function emojiOnly(text: string): boolean {
  const t = text.trim();
  if (!t || t.length > 12) return false;
  return /^(\p{Extended_Pictographic}|\p{Emoji_Component}|\u200d|\ufe0f|\s)+$/u.test(t) && !/^[\d#*\s]+$/.test(t);
}
