import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api } from "../api/client.ts";
import type { Status } from "../api/types.ts";
import { live } from "./store.ts";

const INTERVAL = 30_000;
/** A tab left open isn't R being here: with no input for this long the beats stop, and the session ends (ADR 0024). */
export const AWAY_AFTER = 10 * 60_000;
const ACTIVITY = ["pointerdown", "pointermove", "keydown", "wheel", "scroll", "touchstart"] as const;

/**
 * Keeps R's session alive while the tab is visible and he's actually using it (SPEC §5.1: heartbeats every 30 s,
 * driven by the Page Visibility API; ADR 0024: and by input, so an open tab left overnight spends nothing), and keeps
 * the world clock and the backfill flag current. Coming back after being away beats at once, and starts a new visit.
 */
export function useHeartbeat(enabled: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!enabled) return;
    let wasBackfilling = false;
    let lastInput = Date.now();
    const beat = async () => {
      if (document.visibilityState !== "visible") return;
      if (Date.now() - lastInput > AWAY_AFTER) return;
      try {
        const s = await api<Status>("/api/session/heartbeat", { method: "POST" });
        live.setClock(s.worldTime, s.paused);
        live.setBackfilling(s.backfilling);
        // the background backfill finished: there's history to see behind the pill
        if (wasBackfilling && !s.backfilling) live.addNewPosts(1);
        wasBackfilling = s.backfilling;
        if (s.backfilling) void qc.invalidateQueries({ queryKey: ["world"] });
      } catch {
        // signed out or offline: the next beat tries again
      }
    };
    void beat();
    const timer = setInterval(() => void beat(), INTERVAL);
    const onVisible = () => {
      if (document.visibilityState === "visible") void beat();
    };
    const onInput = () => {
      const wasAway = Date.now() - lastInput > AWAY_AFTER;
      lastInput = Date.now();
      if (wasAway) void beat();
    };
    document.addEventListener("visibilitychange", onVisible);
    for (const name of ACTIVITY) window.addEventListener(name, onInput, { passive: true, capture: true });
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      for (const name of ACTIVITY) window.removeEventListener(name, onInput, { capture: true });
    };
  }, [enabled, qc]);
}
