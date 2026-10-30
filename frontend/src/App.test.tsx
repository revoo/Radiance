import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import App from "./App.tsx";
import { ME, mockFetch, post, renderWith } from "./test/render.tsx";

describe("App", () => {
  it("asks R to log in when there's no session", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/me": new Response("", { status: 401 }) }).fn);
    renderWith(<App />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
  });

  it("starts onboarding once the world exists but R doesn't", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      mockFetch({ "GET /api/me": { username: "r", worldReady: true, onboarded: false, actor: null, networkName: "Radiance" } }).fn,
    );
    renderWith(<App />);
    expect(await screen.findByRole("heading", { name: "Who are you?" })).toBeInTheDocument();
  });

  it("shows the feed straight away for an onboarded R", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      mockFetch({
        "GET /api/me": { username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" },
        "GET /api/feed": { items: [post()], nextCursor: null },
        "GET /api/world/recap": { since: null, lines: [] },
        "GET /api/world": { name: "Chicagoland", networkName: "Radiance", timezone: "America/Chicago", worldTime: Date.now(), committedUntil: 0, scriptedUntil: 0, paused: false, backfilling: false },
        "GET /api/people": [],
        "GET /api/notifications": { items: [], unread: 0 },
        "POST /api/session/heartbeat": { worldTime: Date.now(), scriptedUntil: 0, backfilling: false, sessionActive: true },
      }).fn,
    );
    renderWith(<App />);
    expect(await screen.findByRole("article", { name: "Post by Sarah Kim" })).toBeInTheDocument();
    expect(screen.getByLabelText("Write a post")).toHaveAttribute("placeholder", "What's on your mind, Sam?");
  });
});

describe("logging out", () => {
  // regression: the cache was cleared but who R is was never asked again, so the feed stayed up
  it("returns to the login page", async () => {
    const routes: Record<string, unknown> = {
      "GET /api/me": { username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" },
      "GET /api/feed": { items: [post()], nextCursor: null },
      "GET /api/world/recap": { since: null, lines: [] },
      "GET /api/world": { name: "Chicagoland", networkName: "Radiance", timezone: "America/Chicago", worldTime: Date.now(), committedUntil: 0, scriptedUntil: 0, paused: false, backfilling: false },
      "GET /api/people": [],
      "GET /api/notifications": { items: [], unread: 0 },
      "POST /api/session/heartbeat": { worldTime: Date.now(), scriptedUntil: 0, backfilling: false, sessionActive: true },
      "POST /api/auth/logout": undefined,
    };
    const m = mockFetch(routes);
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    renderWith(<App />);
    await screen.findByRole("article", { name: "Post by Sarah Kim" });

    fireEvent.click(screen.getByRole("button", { name: "Your account" }));
    // the server ends the session: who R is now answers 401
    routes["GET /api/me"] = new Response("", { status: 401 });
    fireEvent.click(screen.getByRole("menuitem", { name: "Log out" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(m.calls.some((c) => c.method === "POST" && c.url === "/api/auth/logout")).toBe(true);
  });

  it("is in the account menu at every screen size", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      mockFetch({
        "GET /api/me": { username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" },
        "GET /api/feed": { items: [], nextCursor: null },
      }).fn,
    );
    renderWith(<App />);
    const menu = await screen.findByRole("button", { name: "Your account" });
    expect(menu.className).not.toMatch(/\bhidden\b/);
    fireEvent.click(menu);
    expect(screen.getByRole("menuitem", { name: "Log out" })).toBeVisible();
  });
});
