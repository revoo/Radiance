import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { keys } from "../api/queries.ts";
import type { DmConversation, DmThread } from "../api/types.ts";
import { live } from "../live/store.ts";
import { applyEvent } from "../live/useRadianceStream.ts";
import { ME, mockFetch, SARAH } from "../test/render.tsx";
import { emojiOnly } from "../ui/format.ts";
import { MessengerPage } from "./MessengerPage.tsx";

afterEach(() => live.reset());

const NOW = Date.now();

const thread: DmThread = {
  id: "t1",
  with: SARAH,
  lastMessage: { id: "m3", threadId: "t1", senderId: SARAH.id, you: false, body: "omg yes", createdAt: NOW - 30_000, readAt: null },
  unread: 1,
  typing: false,
};

const convo: DmConversation = {
  thread,
  messages: [
    { id: "m1", threadId: "t1", senderId: ME.id, you: true, body: "coffee this week?", createdAt: NOW - 90_000, readAt: NOW - 80_000 },
    { id: "m2", threadId: "t1", senderId: SARAH.id, you: false, body: "ooh where", createdAt: NOW - 60_000, readAt: null },
    { id: "m3", threadId: "t1", senderId: SARAH.id, you: false, body: "omg yes", createdAt: NOW - 30_000, readAt: null },
  ],
  typing: [],
};

function renderAt(path: string, routes: Record<string, unknown>) {
  const m = mockFetch(routes);
  vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/messages" element={<MessengerPage me={ME} />} />
          <Route path="/messages/:threadId" element={<MessengerPage me={ME} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { calls: m.calls, client };
}

describe("MessengerPage", () => {
  it("lists chats with the last message and unread count", async () => {
    renderAt("/messages", { "GET /api/dm/threads": [thread] });
    const row = await screen.findByRole("link", { name: /Sarah Kim/ });
    expect(row).toHaveTextContent("omg yes");
    expect(within(row).getByText("1")).toBeInTheDocument();
  });

  it("shows the conversation, Seen under R's last message, and reads the thread", async () => {
    const { calls } = renderAt("/messages/t1", {
      "GET /api/dm/threads/t1/messages": convo,
      "GET /api/dm/threads": [thread],
      "POST /api/dm/threads/t1/read": undefined,
    });
    expect(await screen.findByText("coffee this week?")).toBeInTheDocument();
    expect(screen.getByText("ooh where")).toBeInTheDocument();
    expect(screen.getByText(/^Seen/)).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url === "/api/dm/threads/t1/read")).toBe(true));
  });

  it("shows typing dots while the resident types, from the stream", async () => {
    const { client } = renderAt("/messages/t1", { "GET /api/dm/threads/t1/messages": convo, "GET /api/dm/threads": [thread] });
    await screen.findByText("coffee this week?");
    expect(screen.queryByRole("status", { name: "Sarah is typing" })).toBeNull();
    act(() => applyEvent(client, "typing.started", { threadId: "t1", key: "DM:x", actorId: SARAH.id, name: "Sarah Kim", kind: "dm" }));
    expect(screen.getByRole("status", { name: "Sarah is typing" })).toBeInTheDocument();
    act(() => applyEvent(client, "typing.stopped", { threadId: "t1", key: "DM:x", actorId: SARAH.id }));
    expect(screen.queryByRole("status", { name: "Sarah is typing" })).toBeNull();
  });

  it("sends on Enter and shows R's message at once", async () => {
    const { calls } = renderAt("/messages/t1", {
      "GET /api/dm/threads/t1/messages": convo,
      "GET /api/dm/threads": [thread],
      "POST /api/dm/threads/t1/read": undefined,
      "POST /api/dm/threads/t1/messages": { id: "m4", threadId: "t1", senderId: ME.id, you: true, body: "the place on Milwaukee?", createdAt: NOW, readAt: null },
    });
    const box = await screen.findByLabelText("Message Sarah Kim");
    fireEvent.change(box, { target: { value: "the place on Milwaukee?" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(await screen.findByText("the place on Milwaukee?")).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url === "/api/dm/threads/t1/messages")).toBe(true));
    expect(JSON.parse(calls.find((c) => c.url === "/api/dm/threads/t1/messages" && c.method === "POST")!.body!)).toEqual({
      text: "the place on Milwaukee?",
    });
  });

  it("a new message from the stream refetches the conversation", async () => {
    const { client } = renderAt("/messages/t1", { "GET /api/dm/threads/t1/messages": convo, "GET /api/dm/threads": [thread] });
    await screen.findByText("coffee this week?");
    const invalidate = vi.spyOn(client, "invalidateQueries");
    act(() => applyEvent(client, "dm.message", { threadId: "t1", id: "m9", senderId: SARAH.id }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: keys.conversation("t1") });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: keys.threads });
  });
});

describe("DM notifications", () => {
  it("toast to Messages, except while a conversation is open", () => {
    const qc = new QueryClient();
    applyEvent(qc, "notification.created", { kind: "DM", refType: "DM", refId: "m1", actors: ["Sarah Kim"], count: 1 });
    expect(live.get().toasts.at(-1)).toMatchObject({ text: "Sarah Kim sent you a message", to: "/messages" });
    live.setOpenThread("t1");
    applyEvent(qc, "notification.created", { kind: "DM", refType: "DM", refId: "m2", actors: ["Sarah Kim"], count: 1 });
    expect(live.get().toasts).toHaveLength(1);
  });
});

describe("emojiOnly", () => {
  it("is true for a few emoji and nothing else", () => {
    expect(emojiOnly("👋")).toBe(true);
    expect(emojiOnly("😂😂")).toBe(true);
    expect(emojiOnly("❤️")).toBe(true);
    expect(emojiOnly("ok 👍")).toBe(false);
    expect(emojiOnly("42")).toBe(false);
    expect(emojiOnly("")).toBe(false);
  });
});
