import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch } from "../test/render.tsx";
import { live } from "./store.ts";
import { useHeartbeat } from "./useHeartbeat.ts";

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
});
