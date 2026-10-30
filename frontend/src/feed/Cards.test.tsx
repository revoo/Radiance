import { act, fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { live } from "../live/store.ts";
import { mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { NewPostsPill } from "./NewPostsPill.tsx";
import { RecapCard } from "./RecapCard.tsx";

afterEach(() => {
  live.reset();
  sessionStorage.clear();
});

describe("NewPostsPill", () => {
  it("stays hidden until posts air, then shows them on a click", () => {
    const onShow = vi.fn();
    renderWith(<NewPostsPill onShow={onShow} />);
    expect(screen.queryByRole("button")).toBeNull();
    act(() => live.addNewPosts(3));
    const pill = screen.getByRole("button", { name: /3 new posts/ });
    window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
    fireEvent.click(pill);
    expect(onShow).toHaveBeenCalled();
    expect(live.get().newPosts).toBe(0);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("says post for one", () => {
    renderWith(<NewPostsPill onShow={() => {}} />);
    act(() => live.addNewPosts(1));
    expect(screen.getByRole("button", { name: /^1 new post$/ })).toBeInTheDocument();
  });
});

describe("RecapCard", () => {
  const recap = {
    since: 1000,
    lines: [
      { eventId: "e1", line: "Sarah got engaged!", importance: 9, at: Date.now() - 3_600_000, postId: "p1", actor: SARAH },
      { eventId: "e2", line: "The softball team lost again", importance: 6, at: Date.now() - 7_200_000, postId: null, actor: null },
    ],
  };

  it("lists what happened, linking to the posts, with big news flagged", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/world/recap": recap }).fn);
    renderWith(<RecapCard />);
    expect(await screen.findByRole("heading", { name: "While you were away" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Sarah got engaged!/ })).toHaveAttribute("href", "/posts/p1");
    expect(screen.getByText("The softball team lost again").closest("a")).toBeNull();
    expect(screen.getAllByText("Big")).toHaveLength(1);
  });

  it("stays dismissed for the session", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/world/recap": recap }).fn);
    const { unmount } = renderWith(<RecapCard />);
    fireEvent.click(await screen.findByRole("button", { name: "Dismiss the recap" }));
    expect(screen.queryByRole("heading", { name: "While you were away" })).toBeNull();
    unmount();
    renderWith(<RecapCard />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByRole("heading", { name: "While you were away" })).toBeNull();
  });

  it("shows nothing when nothing happened", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/world/recap": { since: null, lines: [] } }).fn);
    const { container } = renderWith(<RecapCard />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container).toBeEmptyDOMElement();
  });
});
