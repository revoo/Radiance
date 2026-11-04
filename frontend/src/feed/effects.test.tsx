import { act, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ME, SARAH } from "../test/render.tsx";
import type { Comment } from "../api/types.ts";
import { worldNow } from "../live/store.ts";
import { CommentItem } from "./Comments.tsx";
import { CommentCount, resetEffects, useLanding, useSparks } from "./effects.tsx";

function Landing({ id, at }: { id: string; at: number }) {
  return <p>{useLanding(id, at, 15_000) ? "landing" : "still"}</p>;
}

function Spark() {
  return <p>{useSparks(true) ? "sparks" : "none"}</p>;
}

const comment = (over: Partial<Comment>): Comment =>
  ({
    id: "c1",
    postId: "p1",
    parentId: null,
    author: SARAH,
    createdAt: worldNow(),
    body: "hey",
    reactions: { total: 0, top: [], byKind: {}, mine: null, recent: [] },
    replies: [],
    ...over,
  }) as Comment;

const shown = (c: Comment, toYou = false) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <CommentItem comment={c} me={ME} onReply={() => undefined} toYou={toYou} />
      </MemoryRouter>
    </QueryClientProvider>,
  );

describe("effects", () => {
  beforeEach(() => resetEffects());

  it("lands once, for something new, and never for an old item or a temp one", () => {
    const first = render(<Landing id="a" at={worldNow()} />);
    expect(screen.getByText("landing")).toBeInTheDocument();
    first.unmount();
    // scrolling away and back doesn't replay it
    render(<Landing id="a" at={worldNow()} />);
    expect(screen.getByText("still")).toBeInTheDocument();
  });

  it("doesn't land what's already old when the page loads", () => {
    render(<Landing id="b" at={worldNow() - 60_000} />);
    expect(screen.getByText("still")).toBeInTheDocument();
    render(<Landing id="temp-1" at={worldNow()} />);
    expect(screen.getAllByText("still")).toHaveLength(2);
  });

  it("rests between sparks, so they don't become wallpaper", () => {
    render(<Spark />);
    render(<Spark />);
    expect(screen.getAllByText("sparks")).toHaveLength(1);
    expect(screen.getAllByText("none")).toHaveLength(1);
  });

  it("a resident's new comment lands; R's own and old ones don't", () => {
    const { container } = shown(comment({ id: "c-new" }));
    expect(container.querySelector(".land")).toBeInTheDocument();
    const old = shown(comment({ id: "c-old", createdAt: worldNow() - 3_600_000 }));
    expect(old.container.querySelector(".land")).toBeNull();
    const mine = shown(comment({ id: "c-mine", author: ME }));
    expect(mine.container.querySelector(".land")).toBeNull();
  });

  it("an answer to R gets a little more", () => {
    const { container } = shown(comment({ id: "c-you" }), true);
    expect(container.querySelector(".land-you")).toBeInTheDocument();
    expect(screen.getByTestId("sparks")).toBeInTheDocument();
  });

  it("the count pops as comments land, and three quickly is a thread on fire", () => {
    vi.useFakeTimers();
    const view = render(<CommentCount count={1} onClick={() => undefined} />);
    expect(view.container.querySelector(".count-bump")).toBeNull();
    for (const n of [2, 3]) {
      view.rerender(<CommentCount count={n} onClick={() => undefined} />);
      act(() => void vi.advanceTimersByTime(5));
    }
    expect(view.container.querySelector(".count-bump")).toBeInTheDocument();
    expect(screen.queryByTestId("thread-hot")).toBeNull();
    view.rerender(<CommentCount count={4} onClick={() => undefined} />);
    act(() => void vi.advanceTimersByTime(5));
    expect(screen.getByTestId("thread-hot")).toBeInTheDocument();
    act(() => void vi.advanceTimersByTime(13_000));
    expect(screen.queryByTestId("thread-hot")).toBeNull();
    vi.useRealTimers();
  });
});
