import { useEffect, useRef, useState } from "react";
import type { Reactions } from "../api/types.ts";
import { REACTION_EMOJI, whoReacted } from "../ui/format.ts";

/**
 * Top three reaction icons and who reacted ("Sarah, Jake and 3 others"). When someone else's reaction
 * lands while R watches, a +N floats up from the icons, like a score.
 */
export function ReactionSummary({ reactions, size = "md" }: { reactions: Reactions; size?: "sm" | "md" }) {
  const [gain, setGain] = useState<{ n: number; id: number } | null>(null);
  const prev = useRef(reactions.total);
  const prevMine = useRef(reactions.mine ?? null);

  useEffect(() => {
    const fromOthers = reactions.total - prev.current - ((reactions.mine ? 1 : 0) - (prevMine.current ? 1 : 0));
    prev.current = reactions.total;
    prevMine.current = reactions.mine ?? null;
    if (fromOthers > 0) {
      const id = Date.now();
      // a +N score pop, the moment another resident's reaction lands
      setGain({ n: fromOthers, id });
      const t = setTimeout(() => setGain((g) => (g?.id === id ? null : g)), 1_100);
      return () => clearTimeout(t);
    }
  }, [reactions.total, reactions.mine]);

  if (reactions.total === 0) return null;
  const icon = size === "sm" ? "size-4 text-[10px]" : "size-[22px] text-[13px]";
  const label = whoReacted(reactions.total, reactions.recent, Boolean(reactions.mine));
  return (
    <span className="relative inline-flex items-center gap-1.5" title={label}>
      <span className="flex -space-x-1">
        {reactions.top.map((k, i) => (
          <span
            key={k}
            style={{ zIndex: 3 - i }}
            className={`${icon} inline-flex items-center justify-center rounded-full bg-panel ring-2 ring-panel`}
            aria-hidden="true"
          >
            {REACTION_EMOJI[k]}
          </span>
        ))}
      </span>
      <span className={size === "sm" ? "text-[11px] text-muted" : "text-sm text-muted"}>
        {size === "sm" ? reactions.total : label}
      </span>
      {gain && (
        <span
          key={gain.id}
          aria-hidden="true"
          className="animate-float-up pointer-events-none absolute -top-3 left-2 rounded-full bg-brand px-1.5 text-[11px] font-bold text-white shadow-lift"
        >
          +{gain.n}
        </span>
      )}
      <span className="sr-only">{label} reacted</span>
    </span>
  );
}
