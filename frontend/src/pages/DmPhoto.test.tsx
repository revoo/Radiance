import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { ME, mockFetch, SARAH } from "../test/render.tsx";
import { MessengerPage } from "./MessengerPage.tsx";

afterEach(() => vi.unstubAllGlobals());

const message = (over: object) => ({ id: "m1", threadId: "t1", senderId: SARAH.id, you: false, body: "look at this", createdAt: Date.now(), readAt: null, ...over });

function show(media: object) {
  const thread = { id: "t1", with: SARAH, lastMessage: null, unread: 0, typing: false };
  vi.stubGlobal("fetch", mockFetch({
    "GET /api/dm/threads/t1/messages": { thread, messages: [message({ media })], typing: [] },
    "GET /api/dm/threads": [thread],
  }).fn);
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/messages/t1"]}>
        <Routes>
          <Route path="/messages/:threadId" element={<MessengerPage me={ME} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("a photo in a DM", () => {
  it("shows with the words that go with it, and opens bigger", async () => {
    show({ id: "p", url: "/api/media/p", thumbUrl: null, description: null, status: "READY", width: 1024, height: 1024 });
    const picture = await screen.findByTestId("dm-photo");
    expect(picture.querySelector("img")).toHaveAttribute("src", "/api/media/p");
    expect(screen.getAllByText("look at this").length).toBeGreaterThan(0);
  });

  it("holds its place while the picture is being made", async () => {
    show({ id: "p", url: null, thumbUrl: null, description: null, status: "PENDING", width: null, height: null });
    const picture = await screen.findByTestId("dm-photo");
    expect(picture.querySelector("img")).toBeNull();
  });
});
