import { ThumbsUp } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { REACTION_KINDS, type ReactionKind } from "../api/types.ts";
import { REACTION_COLOR, REACTION_EMOJI, REACTION_LABEL } from "../ui/format.ts";

const OPEN_DELAY = 450;
const CLOSE_DELAY = 300;

/**
 * The Like button and its reaction tray (SPEC §8): hover on desktop or long-press on touch opens the
 * tray; a plain click likes, or takes R's reaction back. Arrow-up opens it from the keyboard.
 */
export function ReactionPicker({ mine, onPick, compact = false }: {
  mine: ReactionKind | null | undefined;
  onPick: (kind: ReactionKind | null) => void;
  compact?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [burst, setBurst] = useState<ReactionKind | null>(null);
  const openTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pressed = useRef(false);

  useEffect(() => () => {
    clearTimeout(openTimer.current);
    clearTimeout(closeTimer.current);
  }, []);

  const scheduleOpen = () => {
    clearTimeout(closeTimer.current);
    openTimer.current = setTimeout(() => setOpen(true), OPEN_DELAY);
  };
  const scheduleClose = () => {
    clearTimeout(openTimer.current);
    closeTimer.current = setTimeout(() => setOpen(false), CLOSE_DELAY);
  };

  const pick = (kind: ReactionKind | null) => {
    setOpen(false);
    clearTimeout(openTimer.current);
    if (kind) {
      setBurst(kind);
      setTimeout(() => setBurst(null), 700);
    }
    onPick(kind);
  };

  const current = mine ?? null;
  return (
    <div className="relative" onMouseEnter={scheduleOpen} onMouseLeave={scheduleClose}>
      {open && (
        <div
          role="menu"
          aria-label="Reactions"
          className="animate-rise absolute bottom-full left-0 z-30 mb-2 flex gap-1 rounded-full border border-line bg-panel px-2 py-1.5 shadow-lift"
          onMouseEnter={() => clearTimeout(closeTimer.current)}
        >
          {REACTION_KINDS.map((k, i) => (
            <button
              key={k}
              role="menuitem"
              aria-label={REACTION_LABEL[k]}
              onClick={() => pick(k)}
              style={{ animationDelay: `${i * 30}ms` }}
              className="group/r animate-pop relative flex size-10 items-center justify-center rounded-full text-[28px] leading-none transition-transform duration-150 hover:-translate-y-1.5 hover:scale-[1.35] focus-visible:scale-125"
            >
              <span aria-hidden="true">{REACTION_EMOJI[k]}</span>
              <span className="pointer-events-none absolute -top-7 rounded-full bg-ink px-2 py-0.5 text-[11px] font-semibold text-panel opacity-0 transition group-hover/r:opacity-100">
                {REACTION_LABEL[k]}
              </span>
            </button>
          ))}
        </div>
      )}
      <button
        aria-haspopup="menu"
        aria-pressed={current !== null}
        aria-label={current ? `${REACTION_LABEL[current]} (click to remove)` : "Like"}
        onClick={() => {
          if (pressed.current) {
            pressed.current = false;
            return;
          }
          pick(current ? null : "LIKE");
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowUp") {
            e.preventDefault();
            setOpen(true);
          } else if (e.key === "Escape") setOpen(false);
        }}
        onTouchStart={() => {
          openTimer.current = setTimeout(() => {
            pressed.current = true;
            setOpen(true);
          }, OPEN_DELAY);
        }}
        onTouchEnd={() => clearTimeout(openTimer.current)}
        className={
          compact
            ? `relative text-xs font-bold hover:underline ${current ? REACTION_COLOR[current] : "text-muted"}`
            : `relative flex w-full items-center justify-center gap-2 rounded-xl py-2 text-sm font-semibold transition hover:bg-panel-2 active:scale-[0.97] ${
                current ? REACTION_COLOR[current] : "text-muted"
              }`
        }
      >
        {!compact &&
          (current && current !== "LIKE" ? (
            <span aria-hidden="true" className="text-lg leading-none">{REACTION_EMOJI[current]}</span>
          ) : (
            <ThumbsUp className={`size-[18px] ${current === "LIKE" ? "fill-current" : ""}`} aria-hidden="true" />
          ))}
        {current ? REACTION_LABEL[current] : "Like"}
        {burst && (
          <span aria-hidden="true" className="animate-float-up pointer-events-none absolute -top-2 left-1/2 text-2xl">
            {REACTION_EMOJI[burst]}
          </span>
        )}
      </button>
    </div>
  );
}
