import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch } from "../test/render.tsx";
import { live } from "./store.ts";
import { AWAY_AFTER, useHeartbeat } from "./useHeartbeat.ts";

afterEach(() => live.reset());

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>;
}

describe("useHeartbeat", () => {
  it("beats at once, syncs the world clock and the backfill flag", async () => {
    const worldTime = Date.now() + 3_600_000; // the world runs an hour ahead (a fast-forward)
    const m = mockFetch({ "POST /api/session/heartbeat": { worldTime, scriptedUntil: 0, backfilling: true, sessionActive: true } });
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    renderHook(() => useHeartbeat(true), { wrapper });
    await waitFor(() => expect(live.get().backfilling).toBe(true));
    expect(live.get().clockOffset).toBeGreaterThan(3_590_000);
    expect(m.calls.filter((c) => c.url === "/api/session/heartbeat")).toHaveLength(1);
  });

  it("doesn't beat while signed out", async () => {
    const m = mockFetch({});
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    renderHook(() => useHeartbeat(false), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(m.calls).toHaveLength(0);
  });

  it("stops beating when nobody's touched the page for a while, and beats again on the next input", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const m = mockFetch({ "POST /api/session/heartbeat": { worldTime: Date.now(), scriptedUntil: 0, backfilling: false, sessionActive: true } });
      vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
      renderHook(() => useHeartbeat(true), { wrapper });
      const beats = () => m.calls.filter((c) => c.url === "/api/session/heartbeat").length;
      await waitFor(() => expect(beats()).toBe(1));
      await vi.advanceTimersByTimeAsync(AWAY_AFTER + 60_000);
      const whenAway = beats();
      await vi.advanceTimersByTimeAsync(5 * 60_000);
      expect(beats()).toBe(whenAway);
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "a" }));
      await waitFor(() => expect(beats()).toBe(whenAway + 1));
    } finally {
      vi.useRealTimers();
    }
  });
});
