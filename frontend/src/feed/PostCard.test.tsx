import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { keys } from "../api/queries.ts";
import { live } from "../live/store.ts";
import { ME, mockFetch, post, renderWith } from "../test/render.tsx";
import { PostCard } from "./PostCard.tsx";
import { TypingRow } from "./TypingRow.tsx";

afterEach(() => live.reset());

describe("PostCard", () => {
  it("shows who posted, the text with mention links, and who reacted", () => {
    renderWith(<PostCard post={post()} me={ME} />);
    expect(screen.getByRole("article", { name: "Post by Sarah Kim" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "@sam.rivera" })).toHaveAttribute("href", "/people/sam.rivera");
    expect(screen.getByTitle("Jake Zielinski, Amra Hodzic and 1 other")).toBeInTheDocument();
    expect(screen.getByText("5m")).toBeInTheDocument();
  });

  it("shows two preview comments and how many more there are", () => {
    const c = (id: string, body: string) => ({
      id,
      postId: "01POST",
      parentId: null,
      author: ME,
      createdAt: Date.now() - 60_000,
      body,
      reactions: { total: 0, top: [], byKind: {}, mine: null, recent: [] },
      replies: [],
    });
    renderWith(<PostCard post={post({ commentCount: 5, preview: [c("c1", "lol same"), c("c2", "bring cocoa")] })} me={ME} />);
    expect(screen.getByText("lol same")).toBeInTheDocument();
    expect(screen.getByText("bring cocoa")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "View 3 more comments" })).toBeInTheDocument();
  });

  it("shows a typing row while a resident is writing, and hides it when they're done", () => {
    renderWith(<PostCard post={post()} me={ME} />);
    expect(screen.queryByRole("status")).toBeNull();
    act(() => live.typingStarted("01POST", { key: "COMMENT:x", actorId: "01JAKE", name: "Jake Zielinski" }));
    expect(screen.getByRole("status")).toHaveTextContent("Jake is writing a comment");
    act(() => live.typingStopped("01POST", "COMMENT:x"));
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("moves the count at once when R reacts, and tells the server", async () => {
    const m = mockFetch({ "PUT /api/reactions": undefined });
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    const p = post();
    const { client } = renderWith(<PostCard post={p} me={ME} />);
    client.setQueryData(keys.post(p.id), p);
    fireEvent.click(screen.getByRole("button", { name: "Like" }));
    await waitFor(() => expect(m.calls.some((c) => c.method === "PUT" && c.url === "/api/reactions")).toBe(true));
    const sent = JSON.parse(m.calls.find((c) => c.method === "PUT")!.body!);
    expect(sent).toEqual({ targetType: "POST", targetId: "01POST", kind: "LIKE" });
    expect(client.getQueryData<typeof p>(keys.post(p.id))!.reactions.total).toBe(4);
  });
});

describe("TypingRow", () => {
  it("names one writer, two, or a crowd", () => {
    const t = (n: string) => ({ actorId: n, name: `${n} Doe` });
    const { rerender } = renderWith(<TypingRow typing={[t("Ann")]} />);
    expect(screen.getByRole("status")).toHaveTextContent("Ann is writing a comment");
    rerender(<TypingRow typing={[t("Ann"), t("Bo")]} />);
    expect(screen.getByRole("status")).toHaveTextContent("Ann and Bo are writing comments");
    rerender(<TypingRow typing={[t("Ann"), t("Bo"), t("Cy")]} />);
    expect(screen.getByRole("status")).toHaveTextContent("Ann and 2 others are writing comments");
  });
});
