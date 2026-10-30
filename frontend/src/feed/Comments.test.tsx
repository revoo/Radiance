import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Comment } from "../api/types.ts";
import { ME, renderWith, SARAH } from "../test/render.tsx";
import { CommentItem } from "./Comments.tsx";

function comment(over: Partial<Comment> = {}): Comment {
  return {
    id: "c1",
    postId: "p1",
    parentId: null,
    author: SARAH,
    createdAt: Date.now() - 120_000,
    body: "ok",
    reactions: { total: 2, top: ["HAHA"], byKind: { HAHA: 2 }, mine: null, recent: ["Jake Z", "Amra H"] },
    replies: [],
    ...over,
  };
}

describe("CommentItem", () => {
  // regression: the reaction badge sat inside the bubble's corner and covered short replies
  it("hangs the reaction badge off the bubble, never over the words", () => {
    const reply = comment({ id: "r1", parentId: "c1", body: "lol" });
    renderWith(<CommentItem comment={comment({ replies: [reply] })} me={ME} onReply={() => {}} />);
    const text = screen.getByText("lol");
    const bubble = text.closest(".rounded-2xl")!;
    const badges = screen.getAllByTestId("comment-reactions");
    expect(badges).toHaveLength(2);
    for (const badge of badges) {
      expect(bubble.contains(badge)).toBe(false);
      expect(badge.className).toMatch(/\bleft-full\b/);
    }
    // the bubble leaves room on its right for the badge
    expect(bubble.parentElement!.className).toMatch(/max-w-\[calc\(100%-3rem\)\]/);
  });

  it("offers Reply and hands the comment to answer", () => {
    const onReply = vi.fn();
    renderWith(<CommentItem comment={comment()} me={ME} onReply={onReply} />);
    fireEvent.click(screen.getByRole("button", { name: "Reply" }));
    expect(onReply).toHaveBeenCalledWith(expect.objectContaining({ id: "c1" }));
  });

  it("has no actions on R's comment while it's still being sent", () => {
    renderWith(<CommentItem comment={comment({ id: "temp-1", author: ME })} me={ME} onReply={() => {}} />);
    expect(screen.queryByRole("button", { name: "Reply" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Like" })).toBeNull();
  });
});
