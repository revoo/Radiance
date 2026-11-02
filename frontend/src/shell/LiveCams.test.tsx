import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch, renderWith } from "../test/render.tsx";
import { camAge, LiveCams } from "./LiveCams.tsx";

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const AGO = (min: number) => Date.now() - min * 60_000;
const CAMS = () => [
  { id: "loop", name: "The Loop from UIC", takenAt: AGO(3) },
  { id: "river", name: "River West skyline", takenAt: AGO(70) },
];

function setup(cameras: unknown[] = CAMS()) {
  const m = mockFetch({ "GET /api/cameras": cameras });
  vi.stubGlobal("fetch", m.fn);
  return renderWith(<LiveCams />);
}

describe("camAge", () => {
  it.each([
    [0, "just now"],
    [59_999, "just now"],
    [60_000, "1 min ago"],
    [59 * 60_000, "59 min ago"],
    [60 * 60_000, "1 hr ago"],
    [130 * 60_000, "2 hr ago"],
    [-5_000, "just now"],
  ])("%i ms old reads as %s", (age, text) => {
    expect(camAge(1_000_000_000, 1_000_000_000 + age)).toBe(text);
  });
});

describe("LiveCams", () => {
  it("shows a camera with its name, its real age and the credit its license needs", async () => {
    setup();
    const image = await screen.findByRole("img", { name: "The Loop from UIC" });
    expect(image.getAttribute("src")).toMatch(/^\/api\/cameras\/loop\/image\?t=\d+$/);
    expect(screen.getByText("3 min ago")).toBeInTheDocument();
    expect(screen.getByText("Illinois DOT ·", { exact: false })).toBeInTheDocument();
    const license = screen.getByRole("link", { name: "CC BY-SA" });
    expect(license).toHaveAttribute("href", "https://creativecommons.org/licenses/by-sa/2.0/");
    expect(license).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("never crops the picture, so the burned-in time and logo stay", async () => {
    setup();
    expect(await screen.findByRole("img", { name: "The Loop from UIC" })).toHaveClass("object-contain");
  });

  it("shows nothing when no camera is up: off, a dead source, or the world's clock has moved", async () => {
    const { container } = setup([]);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });

  it("moves on from a camera whose picture won't load, and disappears if none will", async () => {
    const { container } = setup();
    fireEvent.error(await screen.findByRole("img", { name: "The Loop from UIC" }));
    const second = await screen.findByRole("img", { name: "River West skyline" });
    expect(screen.getByText("1 hr ago")).toBeInTheDocument();
    fireEvent.error(second);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("lets you pick a camera, and open it bigger", async () => {
    setup();
    await screen.findByRole("img", { name: "The Loop from UIC" });
    fireEvent.click(screen.getByRole("button", { name: "Show River West skyline" }));
    expect(screen.getByRole("img", { name: "River West skyline" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "See River West skyline bigger" }));
    expect(await screen.findByRole("heading", { name: "River West skyline" })).toBeInTheDocument();
    expect(screen.getAllByRole("img", { name: "River West skyline" })).toHaveLength(2);
  });

  it("takes turns on its own, but holds still while the pointer's on it", async () => {
    setup();
    await screen.findByRole("img", { name: "The Loop from UIC" });
    vi.useFakeTimers();
    const section = screen.getByRole("region", { name: "Live from Chicago" });
    fireEvent.mouseEnter(section);
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByRole("img", { name: "The Loop from UIC" })).toBeInTheDocument();
    fireEvent.mouseLeave(section);
    act(() => {
      vi.advanceTimersByTime(20_000);
    });
    expect(screen.getByRole("img", { name: "River West skyline" })).toBeInTheDocument();
  });
});
