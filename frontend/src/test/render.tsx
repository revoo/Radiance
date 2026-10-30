import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import type { Actor, Post } from "../api/types.ts";

export function renderWith(ui: ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter>{ui}</MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

export const ME: Actor = { id: "01ME", handle: "sam.rivera", name: "Sam Rivera", you: true, occupation: null, livesIn: null };
export const SARAH: Actor = { id: "01SARAH", handle: "sarah.kim", name: "Sarah Kim", you: false, occupation: "Nurse", livesIn: "Evanston" };

export function post(over: Partial<Post> = {}): Post {
  return {
    id: "01POST",
    author: SARAH,
    createdAt: Date.now() - 5 * 60_000,
    kind: "STATUS",
    audience: "FRIENDS",
    body: "First snow of the year and @sam.rivera still owes me coffee",
    reactions: { total: 3, top: ["LIKE", "HAHA"], byKind: { LIKE: 2, HAHA: 1 }, mine: null, recent: ["Jake Zielinski", "Amra Hodzic"] },
    commentCount: 0,
    preview: [],
    typing: [],
    ...over,
  };
}

/** A fetch mock that answers by method and path. */
export function mockFetch(routes: Record<string, unknown>) {
  const calls: { method: string; url: string; body?: string }[] = [];
  const fn = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body ? String(init.body) : undefined });
    const key = Object.keys(routes).find((k) => {
      const [m, p] = k.split(" ");
      return m === method && url.startsWith(p!);
    });
    if (!key) return new Response(JSON.stringify({ status: 404 }), { status: 404 });
    const value = routes[key];
    if (value instanceof Response) return value;
    if (value === undefined) return new Response(null, { status: 204 });
    return new Response(JSON.stringify(value), { status: 200 });
  };
  return { fn, calls };
}
