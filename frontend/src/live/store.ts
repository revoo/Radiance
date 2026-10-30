import { useSyncExternalStore } from "react";

export type TypingEntry = { key: string; actorId: string; name: string };

export type Toast = { id: string; text: string; postId: string | null };

export type LiveState = {
  /** Posts that aired since the feed was loaded, waiting behind the "N new posts" pill. */
  newPosts: number;
  /** Typing indicators by post: "Sarah is writing a comment…". */
  typing: Record<string, TypingEntry[]>;
  toasts: Toast[];
  backfilling: boolean;
  /** World time minus this browser's clock, from the last heartbeat. */
  clockOffset: number;
};

let state: LiveState = { newPosts: 0, typing: {}, toasts: [], backfilling: false, clockOffset: 0 };
const listeners = new Set<() => void>();

function set(next: Partial<LiveState>) {
  state = { ...state, ...next };
  listeners.forEach((l) => l());
}

export const live = {
  get: () => state,
  subscribe(l: () => void) {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  addNewPosts(n: number) {
    set({ newPosts: state.newPosts + n });
  },
  clearNewPosts() {
    set({ newPosts: 0 });
  },
  typingStarted(postId: string, entry: TypingEntry) {
    const list = (state.typing[postId] ?? []).filter((t) => t.key !== entry.key);
    set({ typing: { ...state.typing, [postId]: [...list, entry] } });
  },
  typingStopped(postId: string, key: string) {
    const list = (state.typing[postId] ?? []).filter((t) => t.key !== key);
    const typing = { ...state.typing };
    if (list.length) typing[postId] = list;
    else delete typing[postId];
    set({ typing });
  },
  toast(t: Toast) {
    set({ toasts: [...state.toasts, t].slice(-3) });
    setTimeout(() => set({ toasts: state.toasts.filter((x) => x.id !== t.id) }), 6_000);
  },
  dismiss(id: string) {
    set({ toasts: state.toasts.filter((x) => x.id !== id) });
  },
  setBackfilling(b: boolean) {
    if (b !== state.backfilling) set({ backfilling: b });
  },
  setClockOffset(ms: number) {
    set({ clockOffset: ms });
  },
  reset() {
    state = { newPosts: 0, typing: {}, toasts: [], backfilling: false, clockOffset: 0 };
    listeners.forEach((l) => l());
  },
};

export function useLive<T>(select: (s: LiveState) => T): T {
  return useSyncExternalStore(live.subscribe, () => select(live.get()));
}

/** World time now, by this browser's clock plus the offset from the last heartbeat. */
export function worldNow(): number {
  return Date.now() + state.clockOffset;
}

// one shared ticker, so "Just now" becomes "1m" while R watches
let tick = 0;
const tickers = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;

function subscribeTick(l: () => void) {
  tickers.add(l);
  timer ??= setInterval(() => {
    tick += 1;
    tickers.forEach((x) => x());
  }, 30_000);
  return () => {
    tickers.delete(l);
    if (tickers.size === 0 && timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  };
}

/** World time now, re-rendering every 30 seconds. */
export function useWorldNow(): number {
  useSyncExternalStore(subscribeTick, () => tick);
  return worldNow();
}
