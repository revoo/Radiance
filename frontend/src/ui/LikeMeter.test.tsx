import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { live } from "../live/store.ts";
import { Avatar } from "./kit.tsx";
import { LikeMeter, meterLabel } from "./LikeMeter.tsx";
import { MemoryRouter } from "react-router";

afterEach(() => {
  live.reset();
  vi.useRealTimers();
});

describe("meterLabel", () => {
  it.each([
    [-100, "Can't stand you"],
    [-60, "Can't stand you"],
    [-59, "Dislikes you"],
    [-25, "Dislikes you"],
    [-24, "Not a fan"],
    [-6, "Not a fan"],
    [-5, "Neutral"],
    [0, "Neutral"],
    [10, "Neutral"],
    [11, "Friendly"],
    [30, "Friendly"],
    [31, "Likes you"],
    [55, "Likes you"],
    [56, "Really likes you"],
    [80, "Really likes you"],
    [81, "Adores you"],
    [100, "Adores you"],
  ])("%i reads as %s", (value, label) => {
    expect(meterLabel(value)).toBe(label);
  });
});

describe("LikeMeter", () => {
  it("shows the number and label, and tells screen readers", () => {
    render(<LikeMeter value={42} name="Sarah Kim" />);
    const meter = screen.getByRole("meter", { name: "How Sarah Kim feels about you" });
    expect(meter).toHaveAttribute("aria-valuenow", "42");
    expect(meter).toHaveAttribute("aria-valuetext", "Likes you");
    expect(screen.getByText("+42")).toBeInTheDocument();
  });

  it("keeps a value past the ends on the bar", () => {
    render(<LikeMeter value={140} name="Sarah Kim" />);
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuenow", "100");
  });
});

describe("a meter pulse", () => {
  const sarah = { id: "s", name: "Sarah Kim", handle: "sarah.kim" };

  it("floats +N in green and −N in red over the avatar, then goes away", () => {
    vi.useFakeTimers();
    render(
      <MemoryRouter>
        <Avatar actor={sarah} />
      </MemoryRouter>,
    );
    act(() => live.pulse("s", 2));
    expect(screen.getByText("+2")).toHaveClass("text-good");
    act(() => live.pulse("s", -3));
    expect(screen.getByText("−3")).toHaveClass("text-bad");
    expect(screen.queryByText("+2")).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.queryByText("−3")).not.toBeInTheDocument();
  });

  it("only shows on that resident's avatar", () => {
    render(
      <MemoryRouter>
        <Avatar actor={{ id: "other", name: "Tim Roe", handle: "tim.roe" }} />
      </MemoryRouter>,
    );
    act(() => live.pulse("s", 2));
    expect(screen.queryByText("+2")).not.toBeInTheDocument();
  });
});
