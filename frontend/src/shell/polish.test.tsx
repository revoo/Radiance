import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Avatar } from "../ui/kit.tsx";
import { ME, mockFetch, SARAH, renderWith } from "../test/render.tsx";
import { ErrorBoundary } from "./ErrorBoundary.tsx";
import { Layout } from "./Layout.tsx";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.title = "";
});

function shell(routes: Record<string, unknown>) {
  vi.stubGlobal("fetch", mockFetch({ "GET /api/dm/threads": [], "GET /api/world": { timezone: "America/Chicago", paused: false }, ...routes }).fn);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><Layout me={ME} network="Radiance"><p>page</p></Layout></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("the crash screen", () => {
  it("takes over from a screen that throws, with a way back", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const Broken = () => {
      throw new Error("boom");
    };
    render(<ErrorBoundary><Broken /></ErrorBoundary>);
    expect(screen.getByRole("alert")).toHaveTextContent("Something broke on this screen");
    expect(screen.getByRole("button", { name: "Back to the feed" })).toBeInTheDocument();
  });

  it("stays out of the way otherwise", () => {
    render(<ErrorBoundary><p>fine</p></ErrorBoundary>);
    expect(screen.getByText("fine")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("the tab title", () => {
  it("counts what's waiting", async () => {
    shell({ "GET /api/people": [], "GET /api/notifications": { unread: 3, items: [] } });
    await waitFor(() => expect(document.title).toBe("(3) Radiance"));
  });

  it("is just the name when nothing is", async () => {
    shell({ "GET /api/people": [], "GET /api/notifications": { unread: 0, items: [] } });
    await waitFor(() => expect(document.title).toBe("Radiance"));
  });
});

describe("presence and birthdays", () => {
  const DANA = { ...SARAH, id: "01DANA", name: "Dana Wu", handle: "dana.wu" };

  it("puts who's around at the top of the contacts, with a dot", async () => {
    shell({ "GET /api/people": [SARAH, DANA], "GET /api/rail": { weather: null, talkedAbout: [], online: ["01DANA"], birthdays: [] } });
    const dot = await screen.findByTitle("Around now");
    expect(dot.closest("a")).toHaveAttribute("href", "/people/dana.wu");
    expect(screen.getByText("1 around")).toBeInTheDocument();
    const contacts = screen.getByText(/^Contacts/).parentElement!.querySelectorAll("li a");
    expect(contacts[0]).toHaveAttribute("href", "/people/dana.wu");
    expect(contacts[1]).toHaveAttribute("href", `/people/${SARAH.handle}`);
  });

  it("shows today's birthdays, and nothing on a day without any", async () => {
    shell({ "GET /api/people": [], "GET /api/rail": { weather: null, talkedAbout: [], online: [], birthdays: [DANA] } });
    expect(await screen.findByText("Birthdays today")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dana Wu" })).toHaveAttribute("href", "/people/dana.wu");
  });

  it("has no dot unless they're around", () => {
    renderWith(<Avatar actor={SARAH} />);
    expect(screen.queryByTitle("Around now")).toBeNull();
    renderWith(<Avatar actor={SARAH} online />);
    expect(screen.getByTitle("Around now")).toBeInTheDocument();
  });
});
