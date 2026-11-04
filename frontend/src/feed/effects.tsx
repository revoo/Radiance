import { useEffect, useRef, useState } from "react";
import { worldNow } from "../live/store.ts";

/**
 * Little moments of game feel on what just happened. They play once per item, only for something that
 * really is new, and sparks have a cooldown: a thing that fires every time stops being a reward.
 */
const played = new Set<string>();

/** Whether this item is landing now: recent, and not seen landing yet. True for the life of the component that asked. */
export function useLanding(id: string, createdAt: number, withinMs: number): boolean {
  const [landing] = useState(() => {
    if (id.startsWith("temp-") || played.has(id) || worldNow() - createdAt >= withinMs) return false;
    played.add(id);
    return true;
  });
  return landing;
}

const SPARK_REST_MS = 8_000;
let lastSpark = 0;

/** Sparks unless some went off a moment ago. */
export function useSparks(wanted: boolean): boolean {
  const [on] = useState(() => {
    if (!wanted || Date.now() - lastSpark < SPARK_REST_MS) return false;
    lastSpark = Date.now();
    return true;
  });
  return on;
}

/** For tests: everything can land again. */
export function resetEffects() {
  played.clear();
  lastSpark = 0;
}

const SPARK_COLORS = ["#ff8a00", "#ff3d77", "#7c4dff"];

/** A small burst from the middle of the parent, which must be positioned. */
export function Sparks({ count = 8 }: { count?: number }) {
  return (
    <span aria-hidden="true" data-testid="sparks" className="pointer-events-none absolute left-1/2 top-1/2 z-10">
      {Array.from({ length: count }, (_, i) => {
        const angle = (i / count) * 2 * Math.PI + 0.4;
        const reach = 24 + ((i * 7) % 18);
        return (
          <span
            key={i}
            className="spark"
            style={
              {
                "--dx": `${Math.round(Math.cos(angle) * reach)}px`,
                "--dy": `${Math.round(Math.sin(angle) * reach)}px`,
                background: SPARK_COLORS[i % SPARK_COLORS.length],
                animationDelay: `${(i % 3) * 30}ms`,
              } as React.CSSProperties
            }
          />
        );
      })}
    </span>
  );
}

/** Three comments inside this long is a thread catching fire. */
const HOT_WINDOW_MS = 60_000;
const HOT_COMMENTS = 3;
const HOT_SHOWS_MS = 12_000;

/** "4 comments": the number pops when another lands, and a little flame shows while they're coming fast. */
export function CommentCount({ count, onClick }: { count: number; onClick: () => void }) {
  const [seen, setSeen] = useState({ count, bumps: 0 });
  if (count !== seen.count) setSeen({ count, bumps: count > seen.count ? seen.bumps + 1 : seen.bumps });
  const [hot, setHot] = useState(false);
  const times = useRef<number[]>([]);

  useEffect(() => {
    if (seen.bumps === 0) return;
    const now = Date.now();
    times.current = [...times.current.filter((t) => now - t < HOT_WINDOW_MS), now];
    if (times.current.length < HOT_COMMENTS) return;
    const on = setTimeout(() => setHot(true), 0);
    const off = setTimeout(() => setHot(false), HOT_SHOWS_MS);
    return () => {
      clearTimeout(on);
      clearTimeout(off);
    };
  }, [seen.bumps]);

  return (
    <button onClick={onClick} className="flex items-center gap-1 text-sm text-muted hover:underline">
      {hot && (
        <span aria-hidden="true" data-testid="thread-hot" className="ember">
          🔥
        </span>
      )}
      <span key={seen.bumps} className={seen.bumps > 0 ? "count-bump" : ""}>
        {count}
      </span>{" "}
      comment{count === 1 ? "" : "s"}
    </button>
  );
}
