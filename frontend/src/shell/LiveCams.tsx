import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useCameras } from "../api/queries.ts";
import { Modal } from "../ui/kit.tsx";

const ROTATE_EVERY = 20_000;
const LICENSE = "https://creativecommons.org/licenses/by-sa/2.0/";
/** How far a finger has to travel to mean "next". */
const SWIPE_PX = 40;

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
  const previous = up.length > 2 ? up[(index - 1 + up.length) % up.length] : undefined;
  const count = up.length;
  const touchStart = useRef<number | null>(null);

  // load the next one ahead, so its turn starts with a picture; and the one before, for going back in the big view
  useEffect(() => {
    if (next) new Image().src = imageUrl(next.id, now);
    if (open && previous) new Image().src = imageUrl(previous.id, now);
  }, [next, previous, open, now]);

  // in the big view, the arrow keys go through the cameras
  useEffect(() => {
    if (!open || count < 2) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "ArrowRight") setIndex((i) => (i % count + 1) % count);
      if (e.key === "ArrowLeft") setIndex((i) => (i % count - 1 + count) % count);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, count]);

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
      <div className="flex flex-wrap items-center justify-between gap-x-2 px-1 pt-1.5">
        <Credit />
        {up.length > 1 && (
          <div className="flex flex-wrap justify-end">
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
        <Modal wide title={camera.name} onClose={() => setOpen(false)}>
          <div
            className="relative"
            onTouchStart={(e) => {
              touchStart.current = e.touches[0]?.clientX ?? null;
            }}
            onTouchEnd={(e) => {
              const from = touchStart.current;
              const to = e.changedTouches[0]?.clientX;
              touchStart.current = null;
              if (from == null || to == null || count < 2 || Math.abs(to - from) < SWIPE_PX) return;
              setIndex((i) => (i % count + (to < from ? 1 : -1) + count) % count);
            }}
          >
            <img
              key={camera.id}
              src={src}
              alt={camera.name}
              onError={() => setFailed((f) => new Set(f).add(camera.id))}
              className="cam-in max-h-[75vh] w-full rounded-xl bg-black object-contain"
            />
            {count > 1 && (
              <>
                <button
                  type="button"
                  aria-label="Previous camera"
                  onClick={() => setIndex((i) => (i % count - 1 + count) % count)}
                  className="absolute left-2 top-1/2 flex size-10 -translate-y-1/2 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm transition hover:scale-105 hover:bg-black/75"
                >
                  <ChevronLeft className="size-6" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  aria-label="Next camera"
                  onClick={() => setIndex((i) => (i % count + 1) % count)}
                  className="absolute right-2 top-1/2 flex size-10 -translate-y-1/2 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm transition hover:scale-105 hover:bg-black/75"
                >
                  <ChevronRight className="size-6" aria-hidden="true" />
                </button>
              </>
            )}
          </div>
          <div className="flex items-center justify-between gap-2 pt-2 text-sm text-muted">
            <span className="tabular-nums">
              {camAge(camera.takenAt, now)}
              {count > 1 && ` · ${(index % count) + 1} of ${count}`}
            </span>
            <Credit />
          </div>
        </Modal>
      )}
    </section>
  );
}
