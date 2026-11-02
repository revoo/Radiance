import { type ReactNode, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMe, usePerson } from "../api/queries.ts";
import { Avatar } from "./kit.tsx";
import { LikeMeter } from "./LikeMeter.tsx";

const OPEN_AFTER = 350;
const CLOSE_AFTER = 150;
const WIDTH = 280;

function Body({ handle }: { handle: string }) {
  const person = usePerson(handle).data;
  const likeMeters = useMe().data?.likeMeters !== false;
  if (!person) return <div className="h-16 animate-pulse rounded-xl bg-panel-2" />;
  const { actor } = person;
  const about = [actor.occupation, actor.livesIn].filter(Boolean).join(" · ");
  return (
    <>
      <div className="flex items-center gap-3">
        <Avatar actor={actor} size="lg" link={false} />
        <div className="min-w-0">
          <p className="truncate font-bold">{actor.name}</p>
          {about && <p className="truncate text-xs text-muted">{about}</p>}
        </div>
      </div>
      {person.mutualFriends > 0 && !actor.you && (
        <p className="mt-2 text-xs text-muted">
          {person.mutualFriends} mutual {person.mutualFriends === 1 ? "friend" : "friends"}
        </p>
      )}
      {likeMeters && person.meter != null && (
        <div className="mt-3">
          <LikeMeter value={person.meter} name={actor.name} />
        </div>
      )}
    </>
  );
}

/**
 * A card on hover or keyboard focus (SPEC §8), with the resident's like-meter. It goes on the page's body so a
 * post card's rounded corners can't clip it, and only loads anything once it opens.
 */
export function PersonHover({ handle, children }: { handle: string; children: ReactNode }) {
  const [at, setAt] = useState<{ left: number; top: number } | null>(null);
  const anchor = useRef<HTMLSpanElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const open = () => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      const box = anchor.current?.getBoundingClientRect();
      if (!box) return;
      setAt({ left: Math.max(8, Math.min(box.left, window.innerWidth - WIDTH - 8)), top: box.bottom + 6 });
    }, OPEN_AFTER);
  };
  const close = () => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setAt(null), CLOSE_AFTER);
  };

  return (
    <span ref={anchor} className="inline-block max-w-full" onMouseEnter={open} onMouseLeave={close} onFocus={open} onBlur={close}>
      {children}
      {at &&
        createPortal(
          <div
            role="tooltip"
            onMouseEnter={() => clearTimeout(timer.current)}
            onMouseLeave={close}
            className="fixed z-50 rounded-2xl border border-line bg-surface p-4 shadow-xl"
            style={{ left: at.left, top: at.top, width: WIDTH }}
          >
            <Body handle={handle} />
          </div>,
          document.body,
        )}
    </span>
  );
}
