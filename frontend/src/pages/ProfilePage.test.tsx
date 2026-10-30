import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { ME, mockFetch, SARAH } from "../test/render.tsx";
import { ProfilePage } from "./ProfilePage.tsx";

describe("ProfilePage", () => {
  it("messages a resident from their profile", async () => {
    const m = mockFetch({
      "GET /api/people/sarah.kim/posts": { items: [], nextCursor: null },
      "GET /api/people/sarah.kim": {
        actor: SARAH, bio: "Nurse, night owl.", employer: "Evanston Hospital", hometown: "Skokie", relationshipStatus: "SINGLE",
        birthday: "03-14", relationship: "FRIEND", friend: true, mutualFriends: 3, friends: [],
      },
      "POST /api/dm/threads": { id: "t7", with: SARAH, lastMessage: null, unread: 0, typing: false },
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/people/sarah.kim"]}>
          <Routes>
            <Route path="/people/:handle" element={<ProfilePage me={ME} />} />
            <Route path="/messages/:threadId" element={<p>chat open</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("button", { name: /Message/ }));
    expect(await screen.findByText("chat open")).toBeInTheDocument();
    await waitFor(() => expect(JSON.parse(m.calls.find((c) => c.method === "POST")!.body!)).toEqual({ handle: "sarah.kim" }));
  });

  it("has no Message button on R's own profile", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      mockFetch({
        "GET /api/people/sam.rivera/posts": { items: [], nextCursor: null },
        "GET /api/people/sam.rivera": {
          actor: ME, bio: null, employer: null, hometown: null, relationshipStatus: null, birthday: null, relationship: null,
          friend: false, mutualFriends: 0, friends: [],
        },
      }).fn,
    );
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/people/sam.rivera"]}>
          <Routes>
            <Route path="/people/:handle" element={<ProfilePage me={ME} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("button", { name: /Edit profile/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Message/ })).toBeNull();
  });
});
