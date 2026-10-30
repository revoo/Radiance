import { screen } from "@testing-library/react";
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
