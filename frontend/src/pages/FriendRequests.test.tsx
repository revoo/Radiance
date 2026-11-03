import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { ME, mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { FriendsPage } from "./FriendsPage.tsx";
import { NotificationsPage } from "./NotificationsPage.tsx";
import { ProfilePage } from "./ProfilePage.tsx";

afterEach(() => vi.unstubAllGlobals());

const person = (over: object) => ({
  actor: SARAH,
  bio: null,
  employer: null,
  hometown: null,
  relationshipStatus: null,
  birthday: null,
  relationship: "ACQUAINTANCE",
  friend: false,
  mutualFriends: 0,
  friends: [],
  ...over,
});

function profile(p: object) {
  const m = mockFetch({
    "GET /api/people/sarah.kim/posts": { items: [], nextCursor: null },
      "GET /api/people/sarah.kim/photos": [],
    "GET /api/people/sarah.kim": p,
    "GET /api/me": { likeMeters: true, username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" },
    "POST /api/people/sarah.kim/friend-request": undefined,
    "POST /api/friend-requests/f1/accept": undefined,
    "POST /api/friend-requests/f1/decline": undefined,
  });
  vi.stubGlobal("fetch", m.fn);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/people/sarah.kim"]}>
        <Routes>
          <Route path="/people/:handle" element={<ProfilePage me={ME} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return m;
}

describe("a resident's profile", () => {
  it("offers Add friend to someone who isn't one, and sends the request", async () => {
    const m = profile(person({}));
    fireEvent.click(await screen.findByRole("button", { name: /Add friend/ }));
    await waitFor(() => expect(m.calls.some((c) => c.method === "POST" && c.url.endsWith("/friend-request"))).toBe(true));
  });

  it("shows a request R has sent as sent, with nothing to press", async () => {
    profile(person({ request: "SENT", requestId: "f1" }));
    expect(await screen.findByText("Request sent")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add friend/ })).not.toBeInTheDocument();
  });

  it("lets R answer a request from them", async () => {
    const m = profile(person({ request: "RECEIVED", requestId: "f1" }));
    fireEvent.click(await screen.findByRole("button", { name: /Accept/ }));
    await waitFor(() => expect(m.calls.some((c) => c.url.endsWith("/friend-requests/f1/accept"))).toBe(true));
    expect(screen.getByRole("button", { name: "Decline" })).toBeInTheDocument();
  });

  it("offers nothing to do with a friend, and shows the meter", async () => {
    profile(person({ friend: true, relationship: "FRIEND", meter: 42 }));
    expect(await screen.findByText("Friend")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add friend/ })).not.toBeInTheDocument();
    expect(await screen.findByRole("meter")).toHaveAttribute("aria-valuenow", "42");
  });

  it("hides the meter when like-meters are off", async () => {
    const m = mockFetch({
      "GET /api/people/sarah.kim/posts": { items: [], nextCursor: null },
      "GET /api/people/sarah.kim/photos": [],
      "GET /api/people/sarah.kim": person({ friend: true, meter: 42 }),
      "GET /api/me": { likeMeters: false, username: "r", worldReady: true, onboarded: true, actor: ME, networkName: "Radiance" },
    });
    vi.stubGlobal("fetch", m.fn);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/people/sarah.kim"]}>
          <Routes>
            <Route path="/people/:handle" element={<ProfilePage me={ME} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("heading", { name: "Sarah Kim" });
    await waitFor(() => expect(m.calls.some((c) => c.url === "/api/me")).toBe(true));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
  });
});

describe("the People page", () => {
  it("shows requests waiting for R, and answers them", async () => {
    const m = mockFetch({
      "GET /api/friend-requests": [{ id: "f1", from: SARAH, createdAt: 1 }],
      "GET /api/people": [],
      "POST /api/friend-requests/f1/decline": undefined,
    });
    vi.stubGlobal("fetch", m.fn);
    renderWith(<FriendsPage />);
    const card = (await screen.findByRole("heading", { name: "Friend requests" })).parentElement!;
    expect(within(card).getByText("Sarah Kim")).toBeInTheDocument();
    fireEvent.click(within(card).getByRole("button", { name: "Decline" }));
    await waitFor(() => expect(m.calls.some((c) => c.url.endsWith("/friend-requests/f1/decline"))).toBe(true));
  });

  it("shows no requests section when nobody's waiting", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/friend-requests": [], "GET /api/people": [] }).fn);
    renderWith(<FriendsPage />);
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByRole("heading", { name: "Friend requests" })).not.toBeInTheDocument();
  });
});

describe("the Notifications page", () => {
  it("says who accepted or asked, and links to their profile", async () => {
    const note = (id: string, kind: string) => ({
      id, kind, actors: [SARAH], refType: "ACTOR", refId: SARAH.id, postId: null, snippet: null, createdAt: 1, read: false,
    });
    vi.stubGlobal(
      "fetch",
      mockFetch({
        "GET /api/notifications": { unread: 2, items: [note("n1", "FRIEND_ACCEPT"), note("n2", "FRIEND_REQUEST")], nextCursor: null },
        "POST /api/notifications": undefined,
      }).fn,
    );
    renderWith(<NotificationsPage />);
    expect(await screen.findByText(/accepted your friend request/)).toBeInTheDocument();
    expect(screen.getByText(/sent you a friend request/)).toBeInTheDocument();
    expect(screen.getByText(/accepted your friend request/).closest("a")).toHaveAttribute("href", "/people/sarah.kim");
  });
});
