import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api } from "../api/client.ts";
import type { Status } from "../api/types.ts";
import { live } from "./store.ts";

const INTERVAL = 30_000;

/**
 * Keeps R's session alive while the tab is visible (SPEC §5.1: heartbeats every 30 s, driven by the
 * Page Visibility API), and keeps the world clock and the backfill flag current.
 */
export function useHeartbeat(enabled: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!enabled) return;
    let wasBackfilling = false;
    const beat = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        const s = await api<Status>("/api/session/heartbeat", { method: "POST" });
        live.setClockOffset(s.worldTime - Date.now());
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
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [enabled, qc]);
}
