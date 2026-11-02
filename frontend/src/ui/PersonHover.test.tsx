import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ME, mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { Name } from "./kit.tsx";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const SARAH_PROFILE = {
  actor: SARAH, bio: null, employer: null, hometown: null, relationshipStatus: null, birthday: null,
  relationship: "FRIEND", friend: true, mutualFriends: 2, friends: [], meter: 64,
};
const me = (likeMeters: boolean) => ({ likeMeters, username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" });

function setup(likeMeters = true) {
  const m = mockFetch({ "GET /api/people/sarah.kim": SARAH_PROFILE, "GET /api/me": me(likeMeters) });
  vi.stubGlobal("fetch", m.fn);
  renderWith(<Name actor={SARAH} />);
  return m;
}

/** The name's link, whose wrapper reacts to the pointer. */
const trigger = () => screen.getByRole("link", { name: SARAH.name }).parentElement!;

describe("a person's hover card", () => {
  it("loads nothing until it opens, then shows who they are and their meter", async () => {
    const m = setup();
    expect(m.calls).toHaveLength(0);
    fireEvent.mouseEnter(trigger());
    expect(await screen.findByRole("meter", {}, { timeout: 2_000 })).toHaveAttribute("aria-valuenow", "64");
    expect(screen.getByRole("tooltip")).toHaveTextContent("2 mutual friends");
  });

  it("doesn't flash open when the pointer just passes over", async () => {
    vi.useFakeTimers();
    const m = setup();
    fireEvent.mouseEnter(trigger());
    act(() => {
      vi.advanceTimersByTime(100);
    });
    fireEvent.mouseLeave(trigger());
    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    expect(m.calls).toHaveLength(0);
  });

  it("closes when the pointer leaves, and stays while it's on the card", async () => {
    setup();
    fireEvent.mouseEnter(trigger());
    const card = await screen.findByRole("tooltip", {}, { timeout: 2_000 });
    fireEvent.mouseLeave(trigger());
    fireEvent.mouseEnter(card);
    await new Promise((r) => setTimeout(r, 250));
    expect(screen.getByRole("tooltip")).toBeInTheDocument();
    fireEvent.mouseLeave(card);
    await waitFor(() => expect(screen.queryByRole("tooltip")).not.toBeInTheDocument());
  });

  it("opens on keyboard focus too", async () => {
    setup();
    fireEvent.focus(screen.getByRole("link", { name: SARAH.name }));
    expect(await screen.findByRole("tooltip", {}, { timeout: 2_000 })).toBeInTheDocument();
  });

  it("leaves the meter out when like-meters are off", async () => {
    setup(false);
    fireEvent.mouseEnter(trigger());
    await screen.findByRole("tooltip", {}, { timeout: 2_000 });
    await waitFor(() => expect(screen.getByRole("tooltip")).toHaveTextContent(SARAH.name));
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
  });
});
