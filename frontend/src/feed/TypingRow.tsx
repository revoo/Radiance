import type { Typing } from "../api/types.ts";
import { Avatar } from "../ui/kit.tsx";

/**
 * "Sarah is writing a comment…" (SPEC §5.1, §8): shown while a resident's reply has its slot but not
 * yet its words, so a slow render reads as anticipation instead of a gap.
 */
export function TypingRow({ typing }: { typing: Typing[] }) {
  if (typing.length === 0) return null;
  const names = typing.map((t) => t.name.split(" ")[0]);
  const text =
    names.length === 1
      ? `${names[0]} is writing a comment`
      : names.length === 2
        ? `${names[0]} and ${names[1]} are writing comments`
        : `${names[0]} and ${names.length - 1} others are writing comments`;
  return (
    <div className="animate-rise flex items-center gap-2 py-1" role="status" aria-live="polite">
      <span className="flex -space-x-2">
        {typing.slice(0, 3).map((t) => (
          <Avatar key={t.actorId} actor={{ id: t.actorId, name: t.name, handle: "" }} size="xs" link={false} />
        ))}
      </span>
      <span className="flex items-center gap-1.5 rounded-2xl bg-panel-2 px-3 py-1.5 text-xs text-muted">
        {text}
        <span className="flex items-end gap-0.5 pb-0.5" aria-hidden="true">
          <span className="typing-dot size-1 rounded-full bg-muted" />
          <span className="typing-dot size-1 rounded-full bg-muted" />
          <span className="typing-dot size-1 rounded-full bg-muted" />
        </span>
      </span>
    </div>
  );
}
