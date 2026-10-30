import { ChevronDown, Flame, Sun, X } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { useRecap } from "../api/queries.ts";
import { relativeTime } from "../ui/format.ts";
import { Avatar, Card } from "../ui/kit.tsx";
import { worldNow } from "../live/store.ts";

/**
 * "While you were away" (SPEC §5.14): the top lines among events that aired since R's last session,
 * pre-rendered by the batch, each linking to its post. Collapsible; dismissed for the session.
 */
export function RecapCard() {
  const recap = useRecap();
  const [closed, setClosed] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  if (!recap.data || recap.data.lines.length === 0) return null;
  if (closed || sessionStorageGet("recap-dismissed") === String(recap.data.since)) return null;
  const lines = collapsed ? recap.data.lines.slice(0, 3) : recap.data.lines;

  return (
    <Card className="animate-rise overflow-hidden">
      <div className="bg-brand relative overflow-hidden px-5 py-4 text-white">
        <div aria-hidden="true" className="animate-glow absolute -right-6 -top-10 size-36 rounded-full bg-white/25 blur-2xl" />
        <div className="relative flex items-start justify-between gap-3">
          <div>
            <p className="flex items-center gap-2 text-xs font-bold uppercase tracking-[0.18em] text-white/80">
              <Sun className="size-4" aria-hidden="true" /> Catch up
            </p>
            <h2 className="mt-1 text-xl font-extrabold tracking-tight">While you were away</h2>
          </div>
          <button
            aria-label="Dismiss the recap"
            onClick={() => {
              sessionStorageSet("recap-dismissed", String(recap.data?.since));
              setClosed(true);
            }}
            className="rounded-full p-1.5 text-white/80 transition hover:bg-white/20 hover:text-white"
          >
            <X className="size-4" />
          </button>
        </div>
      </div>
      <ol className="divide-y divide-line">
        {lines.map((l, i) => {
          const body = (
            <div className="flex items-start gap-3 px-5 py-3 transition hover:bg-panel-2">
              {l.actor ? (
                <Avatar actor={l.actor} size="sm" link={false} />
              ) : (
                <span className="bg-brand-soft flex size-8 items-center justify-center rounded-full text-accent">
                  <Sun className="size-4" />
                </span>
              )}
              <div className="min-w-0 flex-1">
                <p className="text-[15px] leading-snug">{l.line}</p>
                <p className="mt-0.5 text-xs text-muted">{relativeTime(l.at, worldNow())}</p>
              </div>
              {l.importance >= 8 && (
                <span className="flex items-center gap-0.5 rounded-full bg-accent-soft px-2 py-0.5 text-[11px] font-bold text-accent">
                  <Flame className="size-3" aria-hidden="true" /> Big
                </span>
              )}
            </div>
          );
          return (
            <li key={l.eventId} className="animate-rise" style={{ animationDelay: `${i * 50}ms` }}>
              {l.postId ? <Link to={`/posts/${l.postId}`}>{body}</Link> : body}
            </li>
          );
        })}
      </ol>
      {recap.data.lines.length > 3 && (
        <button
          onClick={() => setCollapsed((c) => !c)}
          className="flex w-full items-center justify-center gap-1 border-t border-line py-2 text-sm font-semibold text-muted hover:bg-panel-2"
        >
          {collapsed ? `Show all ${recap.data.lines.length}` : "Show less"}
          <ChevronDown className={`size-4 transition ${collapsed ? "" : "rotate-180"}`} />
        </button>
      )}
    </Card>
  );
}

function sessionStorageGet(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function sessionStorageSet(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // private mode: the card just comes back next time
  }
}
