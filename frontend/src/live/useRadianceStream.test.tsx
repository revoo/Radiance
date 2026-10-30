import { QueryClient } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { keys } from "../api/queries.ts";
import { live } from "./store.ts";
import { applyEvent, useRadianceStream } from "./useRadianceStream.ts";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

afterEach(() => live.reset());

class FakeEventSource {
  static last: FakeEventSource | null = null;
  url: string;
  closed = false;
  listeners = new Map<string, ((e: MessageEvent) => void)[]>();
  constructor(url: string) {
    this.url = url;
    FakeEventSource.last = this;
  }
  addEventListener(name: string, h: (e: MessageEvent) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), h]);
  }
  removeEventListener(name: string, h: (e: MessageEvent) => void) {
    this.listeners.set(name, (this.listeners.get(name) ?? []).filter((x) => x !== h));
  }
  emit(name: string, data: unknown) {
    (this.listeners.get(name) ?? []).forEach((h) => h(new MessageEvent(name, { data: JSON.stringify(data) })));
  }
  close() {
    this.closed = true;
  }
}

describe("applyEvent", () => {
  it("puts new posts behind the pill instead of refetching the feed", () => {
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, "invalidateQueries");
    applyEvent(qc, "feed.new", { count: 2 });
    applyEvent(qc, "feed.new", { count: 1 });
    expect(live.get().newPosts).toBe(3);
    expect(invalidate).not.toHaveBeenCalled();
  });

  it("refreshes the post and its thread when a comment lands", () => {
    const qc = new QueryClient();
    const invalidate = vi.spyOn(qc, "invalidateQueries");
    const refresh = vi.fn(() => Promise.resolve());
    applyEvent(qc, "comment.aired", { id: "c1", postId: "p1", authorId: "a1" }, refresh);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: keys.comments("p1") });
    expect(refresh).toHaveBeenCalledWith(qc, "p1");
  });

  it("starts and stops typing indicators", () => {
    const qc = new QueryClient();
    applyEvent(qc, "typing.started", { postId: "p1", key: "COMMENT:c1", actorId: "a1", name: "Sarah Kim" });
    expect(live.get().typing.p1).toEqual([{ key: "COMMENT:c1", actorId: "a1", name: "Sarah Kim" }]);
    applyEvent(qc, "typing.stopped", { postId: "p1", key: "COMMENT:c1" });
    expect(live.get().typing.p1).toBeUndefined();
  });

  it("turns a grouped notification into one toast", () => {
    const qc = new QueryClient();
    applyEvent(qc, "notification.created", { kind: "REACTION", refType: "POST", refId: "p1", actors: ["Sarah Kim", "Jake Z", "Amra H"], count: 3 });
    expect(live.get().toasts).toHaveLength(1);
    expect(live.get().toasts[0]!.text).toBe("Sarah Kim and 2 others reacted to your post");
    expect(live.get().toasts[0]!.postId).toBe("p1");
  });
});

describe("useRadianceStream", () => {
  it("subscribes to /api/stream while signed in and closes on unmount", () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    const qc = new QueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    const { unmount } = renderHook(() => useRadianceStream(true), { wrapper });
    const source = FakeEventSource.last!;
    expect(source.url).toBe("/api/stream");
    act(() => source.emit("feed.new", { count: 4 }));
    expect(live.get().newPosts).toBe(4);
    unmount();
    expect(source.closed).toBe(true);
    vi.unstubAllGlobals();
  });
});
