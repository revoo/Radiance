import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReactionPicker } from "./ReactionPicker.tsx";

describe("ReactionPicker", () => {
  it("likes on a plain click, and takes R's reaction back on the next", () => {
    const onPick = vi.fn();
    const { rerender } = render(<ReactionPicker mine={null} onPick={onPick} />);
    fireEvent.click(screen.getByRole("button", { name: "Like" }));
    expect(onPick).toHaveBeenLastCalledWith("LIKE");

    rerender(<ReactionPicker mine="LOVE" onPick={onPick} />);
    const button = screen.getByRole("button", { name: /Love/ });
    expect(button).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(button);
    expect(onPick).toHaveBeenLastCalledWith(null);
  });

  it("opens the tray on hover and picks a reaction", () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    const { container } = render(<ReactionPicker mine={null} onPick={onPick} />);
    fireEvent.mouseEnter(container.firstElementChild!);
    expect(screen.queryByRole("menu")).toBeNull();
    act(() => {
      vi.advanceTimersByTime(500);
    });
    const items = screen.getAllByRole("menuitem");
    expect(items.map((i) => i.getAttribute("aria-label"))).toEqual(["Like", "Love", "Care", "Haha", "Wow", "Sad", "Angry"]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Haha" }));
    expect(onPick).toHaveBeenCalledWith("HAHA");
    expect(screen.queryByRole("menu")).toBeNull();
    vi.useRealTimers();
  });

  it("opens from the keyboard with arrow up", () => {
    render(<ReactionPicker mine={null} onPick={() => {}} />);
    fireEvent.keyDown(screen.getByRole("button", { name: "Like" }), { key: "ArrowUp" });
    expect(screen.getByRole("menu", { name: "Reactions" })).toBeInTheDocument();
  });
});
