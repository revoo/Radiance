import { useEffect, useMemo, useState } from "react";
import { useCameras } from "../api/queries.ts";
import { Modal } from "../ui/kit.tsx";

const ROTATE_EVERY = 20_000;
const LICENSE = "https://creativecommons.org/licenses/by-sa/2.0/";

/** How old a picture is, in words. Never just "live": these update every few minutes. */
export function camAge(takenAt: number, now: number): string {
  const min = Math.floor(Math.max(0, now - takenAt) / 60_000);
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  return `${Math.floor(min / 60)} hr ago`;
}

/** Changes every couple of minutes, so a browser shows a new picture when there's likely to be one. */
function imageUrl(id: string, now: number) {
  return `/api/cameras/${id}/image?t=${Math.floor(now / 120_000)}`;
}

function Credit() {
  return (
    <p className="text-[10px] leading-tight text-faint">
      Illinois DOT ·{" "}
      <a href={LICENSE} target="_blank" rel="noopener noreferrer" className="underline hover:text-ink">
        CC BY-SA
      </a>
    </p>
  );
}

/** A window on real Chicago (ADR 0020): hand-picked cameras, taking turns. Nothing shows when none are up. */
export function LiveCams() {
  const cameras = useCameras().data;
  const [failed, setFailed] = useState<ReadonlySet<string>>(new Set());
  const [index, setIndex] = useState(0);
  const [held, setHeld] = useState(false);
  const [open, setOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const up = useMemo(() => (cameras ?? []).filter((c) => !failed.has(c.id)), [cameras, failed]);

  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 20_000);
    return () => clearInterval(tick);
  }, []);

  // takes turns, except while someone's looking closely, the tab's hidden, or they've asked for less motion
  useEffect(() => {
    if (held || open || up.length < 2) return;
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const turn = setInterval(() => {
      if (!document.hidden) setIndex((i) => i + 1);
    }, ROTATE_EVERY);
    return () => clearInterval(turn);
  }, [held, open, up.length]);

  const camera = up.length ? up[index % up.length] : undefined;
  const next = up.length > 1 ? up[(index + 1) % up.length] : undefined;

  // load the next one ahead, so its turn starts with a picture
  useEffect(() => {
    if (next) new Image().src = imageUrl(next.id, now);
  }, [next, now]);

  if (!camera) return null;
  const src = imageUrl(camera.id, now);
  return (
    <section aria-label="Live from Chicago" className="mt-3 px-1" onMouseEnter={() => setHeld(true)} onMouseLeave={() => setHeld(false)}>
      <h2 className="flex items-center gap-2 px-1 pb-1.5 text-xs font-extrabold uppercase tracking-[0.12em] text-muted">
        <span className="relative flex size-2" aria-hidden="true">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-[#f03e3e] opacity-70" />
          <span className="relative inline-flex size-2 rounded-full bg-[#f03e3e]" />
        </span>
        Outside right now
      </h2>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={`See ${camera.name} bigger`}
        className="group relative block aspect-video w-full overflow-hidden rounded-xl bg-black shadow-md transition hover:shadow-lg"
      >
        <img
          key={camera.id}
          src={src}
          alt={camera.name}
          onError={() => setFailed((f) => new Set(f).add(camera.id))}
          className="cam-in absolute inset-0 size-full object-contain transition-transform duration-700 group-hover:scale-105"
        />
        <span className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 to-transparent px-2 pb-1 pt-5 text-left text-white">
          <span className="block truncate text-xs font-bold">{camera.name}</span>
          <span className="block text-[10px] font-medium tabular-nums text-white/75">{camAge(camera.takenAt, now)}</span>
        </span>
      </button>
      <div className="flex items-center justify-between gap-2 px-1 pt-1.5">
        <Credit />
        {up.length > 1 && (
          <div className="flex gap-0.5">
            {up.map((c, i) => (
              <button
                key={c.id}
                type="button"
                aria-label={`Show ${c.name}`}
                aria-current={c.id === camera.id}
                onClick={() => setIndex(i)}
                className="flex size-4 items-center justify-center"
              >
                <span className={`size-1.5 rounded-full transition ${c.id === camera.id ? "bg-accent" : "bg-faint/50 hover:bg-faint"}`} />
              </button>
            ))}
          </div>
        )}
      </div>
      {open && (
        <Modal title={camera.name} onClose={() => setOpen(false)}>
          <img src={src} alt={camera.name} className="w-full rounded-xl bg-black" />
          <div className="flex items-center justify-between gap-2 pt-2 text-sm text-muted">
            <span>{camAge(camera.takenAt, now)}</span>
            <Credit />
          </div>
        </Modal>
      )}
    </section>
  );
}
