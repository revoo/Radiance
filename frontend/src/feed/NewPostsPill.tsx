import { ArrowUp } from "lucide-react";
import { live, useLive } from "../live/store.ts";

/**
 * "N new posts" (SPEC §5.8): posts that air during a session wait here instead of shifting the feed
 * under R. Clicking scrolls to the top and loads them.
 */
export function NewPostsPill({ onShow }: { onShow: () => void }) {
  const n = useLive((s) => s.newPosts);
  if (n === 0) return null;
  return (
    <button
      onClick={() => {
        live.clearNewPosts();
        window.scrollTo({ top: 0, behavior: "smooth" });
        onShow();
      }}
      className="animate-slide-down bg-brand fixed left-1/2 top-[72px] z-40 flex items-center gap-2 rounded-full py-2 pl-3 pr-4 text-sm font-bold text-white shadow-lift transition hover:brightness-110 active:scale-95"
    >
      <span className="animate-bob flex size-5 items-center justify-center rounded-full bg-white/25">
        <ArrowUp className="size-3.5" aria-hidden="true" />
      </span>
      {n} new post{n === 1 ? "" : "s"}
    </button>
  );
}
