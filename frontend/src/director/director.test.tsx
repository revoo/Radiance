import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ME, mockFetch } from "../test/render.tsx";
import { Layout } from "../shell/Layout.tsx";
import type { GraphEdge } from "./api.ts";
import type { PacerStep } from "../api/types.ts";
import { QueueTimeline } from "../pages/PacerPage.tsx";
import { DirectorPage } from "./DirectorPage.tsx";
import { filterTies, forceLayout, tieColor, tiesFrom, tieWidth } from "./layout.ts";
import { directorOn, isDirectorShortcut, reloadDirector, setDirector } from "./mode.ts";
import { span, usd } from "./ui.tsx";

const WHO = (id: string, name: string) => ({ id, handle: name.toLowerCase().replace(" ", "."), name, you: false, avatarUrl: null });
const JESS = WHO("a", "Jess Park");
const MIKE = WHO("b", "Mike Dolan");
const NOW = Date.now();
const edge = (from: string, to: string, over: Partial<GraphEdge> = {}): GraphEdge => ({
  from, to, kind: "FRIEND", affinity: 60, trust: 50, attraction: 0, familiarity: 70, respect: 40, connected: true, tags: [], ...over,
});
const TIME = { worldTime: NOW, paused: false, offsetMs: 0, committedUntil: NOW, scriptedUntil: NOW + 3_600_000, backfilling: false };
const SETTINGS = {
  knobs: { dramaIntensity: 1, profile: "STEADY", affairs: true, eventMix: { DAILY: 0.5, DRAMA: 0.5 }, regionMix: { LOCAL: 1 }, contentKindWeights: { TEXT: 1 } },
  knobDefaults: { dramaIntensity: 1, profile: "STEADY", affairs: true, eventMix: { DAILY: 0.5, DRAMA: 0.5 }, regionMix: { LOCAL: 1 }, contentKindWeights: { TEXT: 1 } },
  ranges: { dramaIntensity: { min: 0, max: 3, whole: false } }, profiles: ["CHAOTIC", "COZY", "STEADY"],
  blacklist: [{ id: "pregnancy", locked: true }, { id: "feud", locked: false }], arcs: ["feud", "new_hobby", "pregnancy"],
  routes: { "render-thread": { model: "haiku", fallback: [] } }, models: [{ alias: "haiku", provider: "anthropic", available: true, vision: true, inputPrice: 1, outputPrice: 5 }],
  budgets: { dailyUsd: 1, perBatchUsd: 0.3, monthlyUsd: 10 }, imagesPerDay: 12, pacing: { typing: { showWhenLate: true } },
};
const INJECTABLES = {
  events: [{ id: "flat_tire", category: "DAILY", description: "Gets a flat", importance: 2, otherRole: null }, { id: "argument", category: "DRAMA", description: "A fight", importance: 5, otherRole: "b" }],
  arcs: [{ id: "new_hobby", category: "HOBBY", description: "Picks something up", blacklisted: false, needsOther: false, sensitive: false }, { id: "feud", category: "DRAMA", description: "A feud", blacklisted: true, needsOther: true, sensitive: false }],
  residents: [JESS, MIKE],
  couples: [[JESS, MIKE]],
};
const ROUTES = {
  "GET /api/director/time": TIME,
  "GET /api/director/settings": SETTINGS,
  "GET /api/director/injectables": INJECTABLES,
};

function Where() {
  return <span data-testid="at">{useLocation().pathname}</span>;
}

function at(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Where />
          <Routes>
            <Route path="/director" element={<DirectorPage />} />
            <Route path="/director/:tab" element={<DirectorPage />} />
            <Route path="/director/:tab/:sub" element={<DirectorPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=tok; path=/";
  localStorage.clear();
  reloadDirector();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("forceLayout", () => {
  const ids = ["a", "b", "c", "d", "e", "f", "g", "h"];
  const box = { width: 400, height: 300 };

  it("is the same every time, whatever order people come in", () => {
    const ties = [{ a: "a", b: "b", weight: 1 }];
    expect(forceLayout(ids, ties, box)).toEqual(forceLayout([...ids].reverse(), ties, box));
  });

  it("keeps everyone inside the box", () => {
    const placed = forceLayout(ids, ids.slice(1).map((b) => ({ a: "a", b, weight: 1 })), box, { margin: 20 });
    for (const id of ids) {
      expect(placed[id]!.x).toBeGreaterThanOrEqual(20);
      expect(placed[id]!.x).toBeLessThanOrEqual(380);
      expect(placed[id]!.y).toBeGreaterThanOrEqual(20);
      expect(placed[id]!.y).toBeLessThanOrEqual(280);
    }
  });

  it("puts people with a strong tie nearer than people with none", () => {
    const placed = forceLayout(ids, [{ a: "a", b: "b", weight: 1 }], box);
    const d = (x: string, y: string) => Math.hypot(placed[x]!.x - placed[y]!.x, placed[x]!.y - placed[y]!.y);
    const others = ids.filter((x) => x !== "a" && x !== "b").map((x) => d("a", x));
    expect(d("a", "b")).toBeLessThan(Math.min(...others));
  });

  it("never stacks two people on one spot", () => {
    const placed = forceLayout(ids, [], box);
    const spots = new Set(ids.map((i) => `${Math.round(placed[i]!.x)},${Math.round(placed[i]!.y)}`));
    expect(spots.size).toBe(ids.length);
  });

  it("copes with nobody, one person, and ties to someone who isn't there", () => {
    expect(forceLayout([], [], box)).toEqual({});
    expect(forceLayout(["a"], [], box).a).toEqual({ x: 200, y: 150 });
    expect(Object.keys(forceLayout(["a", "b"], [{ a: "a", b: "gone", weight: 1 }], box))).toHaveLength(2);
  });
});

describe("ties", () => {
  it("merges the two directions into one line, averaged", () => {
    const ties = tiesFrom([edge("a", "b", { affinity: 80 }), edge("b", "a", { affinity: 20, kind: "RIVAL", tags: ["rival"] })], "affinity");
    expect(ties).toHaveLength(1);
    expect(ties[0]).toMatchObject({ a: "a", b: "b", value: 50, kinds: ["FRIEND", "RIVAL"], tags: ["rival"] });
  });

  it("filters by kind, tag, strength either way, and being friends", () => {
    const ties = tiesFrom([edge("a", "b", { affinity: -70, kind: "RIVAL", tags: ["debate_rival:economics"] }), edge("c", "d", { affinity: 10, connected: false })], "affinity");
    expect(filterTies(ties, { min: 50 }).map((t) => t.a)).toEqual(["a"]);
    expect(filterTies(ties, { kind: "FRIEND" }).map((t) => t.a)).toEqual(["c"]);
    expect(filterTies(ties, { tag: "ECON" }).map((t) => t.a)).toEqual(["a"]);
    expect(filterTies(ties, { connected: true }).map((t) => t.a)).toEqual(["a"]);
    expect(filterTies(ties, {})).toHaveLength(2);
  });

  it("diverges on two-sided dimensions and stays one hue on the others", () => {
    expect(tieColor(50, "affinity")).toContain("--viz-pos");
    expect(tieColor(-50, "affinity")).toContain("--viz-neg");
    expect(tieColor(0, "affinity")).toContain(" 0%");
    expect(tieColor(50, "trust")).toContain("--viz-pos");
    expect(tieColor(250, "familiarity")).toContain(" 100%");
    expect(tieWidth(-100)).toBe(tieWidth(100));
    expect(tieWidth(100)).toBeGreaterThan(tieWidth(0));
  });
});

describe("the pacing timeline", () => {
  it("puts each step in its state's row, and only the last five minutes", () => {
    const step = (state: PacerStep["state"], ago: number, kind: PacerStep["kind"] = "REACTION"): PacerStep => ({ at: NOW - ago, state, kind, actorId: "a" });
    const { container } = render(
      <QueueTimeline now={NOW} steps={[step("PLANNED", 1000), step("PLANNED", 2000, "COMMENT"), step("RELEASED", 500), step("HELD", 3000), step("DROPPED", 400), step("DROPPED", 10 * 60_000)]} />,
    );
    expect(screen.getByTestId("lane-PLANNED")).toHaveTextContent("2");
    expect(screen.getByTestId("lane-RELEASED")).toHaveTextContent("1");
    expect(screen.getByTestId("lane-HELD")).toHaveTextContent("1");
    expect(screen.getByTestId("lane-DROPPED")).toHaveTextContent("1");
    expect(screen.getByTestId("lane-BACKGROUND")).toHaveTextContent("0");
    expect(container.querySelectorAll("rect")).toHaveLength(1);
    expect(screen.getByRole("img")).toHaveAccessibleName(/2 planned, 1 held, 1 released, 1 dropped, 0 left to air later/);
  });
});

describe("formats", () => {
  it.each([
    [NOW + 30_000, "in 30s"], [NOW - 5 * 60_000, "5m ago"], [NOW + 3 * 3_600_000, "in 3h"], [NOW - 3 * 86_400_000, "3d ago"], [NOW, "in 1s"],
  ])("span %i is %s", (at, text) => {
    expect(span(at, NOW)).toBe(text);
  });

  it("shows tiny costs instead of rounding them to nothing", () => {
    expect(usd(0.0012)).toBe("$0.0012");
    expect(usd(0)).toBe("$0.00");
    expect(usd(1.5)).toBe("$1.50");
  });
});

describe("director mode", () => {
  it("is off by default, sticks, and has one shortcut", () => {
    expect(directorOn()).toBe(false);
    setDirector(true);
    reloadDirector();
    expect(directorOn()).toBe(true);
    const key = { key: "D", ctrlKey: true, metaKey: false, shiftKey: true };
    expect(isDirectorShortcut(key)).toBe(true);
    expect(isDirectorShortcut({ ...key, key: "d", ctrlKey: false, metaKey: true })).toBe(true);
    expect(isDirectorShortcut({ ...key, shiftKey: false })).toBe(false);
    expect(isDirectorShortcut({ ...key, key: "x" })).toBe(false);
  });

  function shell() {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/people": [], "GET /api/dm/threads": [], "GET /api/world": { paused: false, timezone: "America/Chicago" }, ...ROUTES }).fn);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/"]}>
          <Where />
          <Layout me={ME} network="Radiance"><p>page</p></Layout>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it("hides the console from the player until it's turned on from the menu", () => {
    shell();
    expect(screen.queryByText("Director's console")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Your account" }));
    expect(screen.queryByRole("menuitem", { name: /Director's console/ })).toBeNull();
    expect(screen.queryByText("Pacing monitor")).toBeNull();
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: /Director mode: off/ }));
    expect(directorOn()).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Your account" }));
    expect(screen.getByRole("menuitem", { name: /Director's console/ })).toHaveAttribute("href", "/director");
  });

  it("opens with the shortcut, and leaves with it", async () => {
    shell();
    fireEvent.keyDown(document, { key: "D", ctrlKey: true, shiftKey: true });
    await waitFor(() => expect(screen.getByTestId("at")).toHaveTextContent("/director"));
    expect(directorOn()).toBe(true);
    fireEvent.keyDown(document, { key: "D", ctrlKey: true, shiftKey: true });
    await waitFor(() => expect(screen.getByTestId("at").textContent).toBe("/"));
    expect(directorOn()).toBe(false);
  });

  it("says so when the world is paused", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/people": [], "GET /api/dm/threads": [], "GET /api/world": { paused: true, timezone: "America/Chicago" } }).fn);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter><Layout me={ME} network="Radiance"><p>page</p></Layout></MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText("The world is paused.")).toBeInTheDocument();
  });
});

describe("time and incidents", () => {
  it("breaks a couple up, then offers to move the clock", async () => {
    const m = mockFetch({ ...ROUTES, "POST /api/director/inject": { events: 2, posts: 1, comments: 4, reactions: 9, firstAt: NOW + 30_000 }, "POST /api/director/time/fast-forward": TIME });
    vi.stubGlobal("fetch", m.fn);
    at("/director/time");
    const fire = await screen.findByRole("button", { name: /Fire it/ });
    await waitFor(() => expect(fire).toBeEnabled());
    fireEvent.click(fire);
    const done = await screen.findByText(/Scripted: 2 events, 1 post, 4 comments, 9 reactions/);
    expect(JSON.parse(m.calls.find((c) => c.url === "/api/director/inject")!.body!)).toEqual({ kind: "BREAKUP", actor: "a", other: "b" });
    const banner = done.closest("[role=status]") as HTMLElement;
    fireEvent.click(within(banner).getByRole("button", { name: /Six hours on/ }));
    await waitFor(() => expect(m.calls.some((c) => c.url === "/api/director/time/fast-forward?hours=6")).toBe(true));
  });

  it("asks for who else only when the event needs one", async () => {
    const m = mockFetch({ ...ROUTES, "POST /api/director/inject": { events: 1, posts: 0, comments: 0, reactions: 0, firstAt: null } });
    vi.stubGlobal("fetch", m.fn);
    at("/director/time");
    fireEvent.click(await screen.findByRole("tab", { name: "Event" }));
    const fire = screen.getByRole("button", { name: /Fire it/ });
    expect(fire).toBeDisabled();
    fireEvent.change(screen.getByRole("combobox", { name: "Which event" }), { target: { value: "flat_tire" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Who it happens to" }), { target: { value: "a" } });
    expect(screen.queryByRole("combobox", { name: /Who else/ })).toBeNull();
    expect(fire).toBeEnabled();
    fireEvent.change(screen.getByRole("combobox", { name: "Which event" }), { target: { value: "argument" } });
    expect(fire).toBeDisabled();
    fireEvent.change(screen.getByRole("combobox", { name: /Who else/ }), { target: { value: "b" } });
    fireEvent.click(fire);
    await waitFor(() => expect(m.calls.some((c) => c.url === "/api/director/inject")).toBe(true));
    expect(JSON.parse(m.calls.find((c) => c.url === "/api/director/inject")!.body!)).toEqual({ kind: "EVENT", defId: "argument", actor: "a", other: "b" });
  });

  it("won't offer a blacklisted storyline", async () => {
    vi.stubGlobal("fetch", mockFetch(ROUTES).fn);
    at("/director/time");
    fireEvent.click(await screen.findByRole("tab", { name: "Storyline" }));
    expect(screen.getByRole("option", { name: /feud \(blacklisted\)/ })).toBeDisabled();
  });

  it("shows why a request was refused", async () => {
    const refused = new Response(JSON.stringify({ detail: "They aren't together" }), { status: 400 });
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "POST /api/director/inject": refused }).fn);
    at("/director/time");
    const fire = await screen.findByRole("button", { name: /Fire it/ });
    await waitFor(() => expect(fire).toBeEnabled());
    fireEvent.click(fire);
    expect(await screen.findByRole("alert")).toHaveTextContent("They aren't together");
  });

  it("pauses and resumes, with the right button for the state", async () => {
    const m = mockFetch({ ...ROUTES, "POST /api/director/time/pause": { ...TIME, paused: true } });
    vi.stubGlobal("fetch", m.fn);
    at("/director/time");
    fireEvent.click(await screen.findByRole("button", { name: /Pause the world/ }));
    await waitFor(() => expect(m.calls.some((c) => c.method === "POST" && c.url === "/api/director/time/pause")).toBe(true));
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "GET /api/director/time": { ...TIME, paused: true } }).fn);
    at("/director/time");
    expect(await screen.findByRole("button", { name: /Resume/ })).toBeInTheDocument();
  });
});

describe("knobs", () => {
  it("sends a change and says it's live", async () => {
    const m = mockFetch({ ...ROUTES, "PUT /api/director/settings": SETTINGS });
    vi.stubGlobal("fetch", m.fn);
    at("/director/knobs");
    const slider = await screen.findByRole("slider", { name: "Drama intensity" });
    fireEvent.change(slider, { target: { value: "2.4" } });
    fireEvent.click(within(screen.getByRole("heading", { name: "World" }).closest("div")!.parentElement!.parentElement!).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(m.calls.some((c) => c.method === "PUT")).toBe(true));
    expect(JSON.parse(m.calls.find((c) => c.method === "PUT")!.body!)).toEqual({ knobs: { dramaIntensity: 2.4 } });
    expect(await screen.findByText(/It's live/)).toBeInTheDocument();
  });

  it("blocks an arc with a click, and can't unblock what the config rules out", async () => {
    const m = mockFetch({ ...ROUTES, "PUT /api/director/settings": SETTINGS });
    vi.stubGlobal("fetch", m.fn);
    at("/director/knobs");
    expect(await screen.findByRole("button", { name: "pregnancy", pressed: true })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "new hobby", pressed: false }));
    await waitFor(() => expect(m.calls.some((c) => c.method === "PUT")).toBe(true));
    expect(JSON.parse(m.calls.find((c) => c.method === "PUT")!.body!)).toEqual({ blacklist: ["feud", "new_hobby"] });
  });

  it("catches broken pacing JSON before sending it", async () => {
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", m.fn);
    at("/director/knobs");
    const box = await screen.findByRole("textbox", { name: "Pacing rules" });
    fireEvent.change(box, { target: { value: "{ nope" } });
    fireEvent.click(within(box.parentElement!).getByRole("button", { name: "Save" }));
    expect(await screen.findByText("That isn't valid JSON.")).toBeInTheDocument();
    expect(m.calls.some((c) => c.method === "PUT")).toBe(false);
  });

  it("shows what the server refused", async () => {
    const refused = new Response(JSON.stringify({ detail: "route 'render-thread': unknown model alias 'x'" }), { status: 400 });
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "PUT /api/director/settings": refused }).fn);
    at("/director/knobs");
    fireEvent.change(await screen.findByRole("textbox", { name: "render-thread fallbacks" }), { target: { value: "x" } });
    fireEvent.click(within(screen.getByRole("heading", { name: "LLM routes" }).closest("div")!.parentElement!.parentElement!).getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("unknown model alias");
  });
});

describe("the panels", () => {
  it("defaults to time, and every tab links by URL", async () => {
    vi.stubGlobal("fetch", mockFetch(ROUTES).fn);
    at("/director");
    expect(await screen.findByRole("heading", { name: "Time" })).toBeInTheDocument();
    const tabs = within(screen.getByRole("navigation", { name: "Console" })).getAllByRole("link");
    expect(tabs.map((t) => t.getAttribute("href"))).toEqual(["time", "storylines", "graph", "residents", "log", "batch", "pacer", "costs", "knobs", "prompts"].map((x) => `/director/${x}`));
  });

  it("storylines: the phase we're in, what's next, and a secret's knowers", async () => {
    const arc = {
      id: "1", defId: "affair", description: "x", status: "ACTIVE", phase: "seeing", phases: ["flirting", "seeing", "caught"], visibility: "SECRET", startedAt: NOW - 86_400_000 * 3, phaseEnteredAt: NOW - 3_600_000,
      nextTransitionAt: NOW + 2 * 3_600_000, lastBeatAt: null, endedAt: null, summary: "Jess and Mike: affair", sensitive: true, people: [JESS, MIKE], knowers: [WHO("c", "Dana Wu")], constraints: ["secret_keeper"],
    };
    const m = mockFetch({ ...ROUTES, "GET /api/director/arcs/1/events": [{ id: "e", at: NOW, type: "ARC_TRANSITION", defId: "affair", actor: JESS, target: MIKE, arcId: "1", importance: 5, visibility: "SECRET", source: "ARC", status: "AIRED", text: null, recapLine: "It started" }], "GET /api/director/arcs": [arc, { ...arc, id: "2", status: "RESOLVED", summary: "Old news" }] });
    vi.stubGlobal("fetch", m.fn);
    at("/director/storylines");
    expect(await screen.findByText("Jess and Mike: affair")).toBeInTheDocument();
    expect(screen.queryByText("Old news")).toBeNull();
    expect(screen.getByText("seeing")).toHaveAttribute("aria-current", "step");
    expect(screen.getByText("in 2h")).toBeInTheDocument();
    expect(screen.getByText("Who knows")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /What's happened/ }));
    expect(await screen.findByText("It started")).toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText(/Someone's name/), { target: { value: "nobody" } });
    expect(screen.getByText("No storylines here")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: /Resolved 1/ }));
    fireEvent.change(screen.getByPlaceholderText(/Someone's name/), { target: { value: "" } });
    expect(screen.getByText("Old news")).toBeInTheDocument();
  });

  it("graph: filters thin the lines, and a line opens its history", async () => {
    const graph = {
      nodes: [{ ...JESS, gender: "F", mood: 0.2, circles: [] }, { ...MIKE, gender: "M", mood: -0.1, circles: [] }, { ...WHO("c", "Dana Wu"), you: true, gender: "F", mood: 0, circles: [] }],
      edges: [edge("a", "b", { affinity: 80, tags: ["crush"] }), edge("b", "a", { affinity: 80 }), edge("a", "c", { affinity: 10 })],
    };
    const history = { from: JESS, to: MIKE, there: graph.edges[0], back: graph.edges[1], summary: "Old friends", events: [{ id: "e", at: NOW, type: "ARGUMENT", defId: null, actor: JESS, target: MIKE, arcId: null, importance: 3, visibility: "PUBLIC", source: "STORYTELLER", status: "AIRED", text: null, recapLine: null }] };
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "GET /api/director/graph/edge": history, "GET /api/director/graph": graph }).fn);
    const { container } = at("/director/graph");
    expect(await screen.findByText("1 ties")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("slider", { name: "Minimum strength" }), { target: { value: "0" } });
    expect(screen.getByText("2 ties")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "Tag" }), { target: { value: "crush" } });
    expect(screen.getByText("1 ties")).toBeInTheDocument();
    fireEvent.click(container.querySelector('[data-testid="tie"]')!);
    expect(await screen.findByText("Old friends")).toBeInTheDocument();
    expect(screen.getByText("argument")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("button", { name: "Jess Park" }), { key: "Enter" });
    expect(await screen.findByRole("link", { name: "Open in the inspector" })).toHaveAttribute("href", "/director/residents/jess.park");
    fireEvent.change(screen.getByRole("combobox", { name: "Color by" }), { target: { value: "trust" } });
    expect(screen.getByText("A lot")).toBeInTheDocument();
  });

  it("inspector: mood, drift from baseline, memories with their scores", async () => {
    const sheet = {
      who: JESS, occupation: "Nurse", livesIn: "Pilsen", relationshipStatus: "DATING", bio: "Hi", backstory: "Grew up in Berwyn.", lately: "Busy", traits: { warmth: 70 }, traitOffsets: { warmth: -5 }, temperament: {}, quirks: [], interests: {}, politics: {}, voice: {}, routine: {},
      mood: 0.25, energy: 0.5, stress: 0.1, partnerSatisfaction: null, location: "HOME", constraints: ["budget_tight"], entities: [{ id: "x", kind: "CAR", name: "The Civic", status: "OWNED", description: null }],
      memories: [{ id: "m", at: NOW - 3_600_000, kind: "EPISODE", text: "Argued with Mike", importance: 6, score: 0.73 }], impressionOfR: edge("a", "me", { tags: ["crush"] }), impressionSummary: "Thinks you're funny", history: [{ day: "2026-11-01", affinity: 40, mood: 0.1 }, { day: "2026-11-02", affinity: 44, mood: null }],
    };
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "GET /api/director/residents/jess.park": sheet }).fn);
    at("/director/residents/jess.park");
    expect(await screen.findByRole("heading", { name: "Jess Park" })).toBeInTheDocument();
    expect(screen.getByText("Thinks you're funny")).toBeInTheDocument();
    expect(screen.getByTitle("warmth: baseline 70, now 65")).toBeInTheDocument();
    expect(screen.getByTitle("Retrieval score")).toHaveTextContent("0.73");
    expect(screen.getByText("budget_tight")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Recent prompts/ })).toHaveAttribute("href", "/director/prompts?q=%40jess.park");
    expect(screen.getByRole("img", { name: /Like-meter for you over 2 days/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Mike Dolan" })).toHaveAttribute("href", "/director/residents/mike.dolan");
  });

  it("log: filters go to the server, older pages load, and reports explain drops", async () => {
    const line = (i: number) => ({ id: `e${i}`, at: NOW - i, type: "NEW_JOB", defId: "new_job", actor: JESS, target: null, arcId: null, importance: 5, visibility: "PUBLIC", source: "ARC", status: i === 0 ? "DROPPED" : "AIRED", text: "Got a job", recapLine: null });
    const page = Array.from({ length: 60 }, (_, i) => line(i));
    const m = mockFetch({ ...ROUTES, "GET /api/director/reports": { continuity: [{ id: "r", kind: "NIGHTLY", status: "DONE", startedAt: NOW, finishedAt: NOW, scriptFrom: null, scriptTo: null, fidelity: null, planned: 1, rendered: 1, dropped: 0, costUsd: 0, rows: 1, error: null, notes: ["continuity findings: [a mess]"] }], drops: [{ at: NOW, reason: "arc feud is blacklisted" }], droppedThisWeek: 3 }, "GET /api/director/events": page });
    vi.stubGlobal("fetch", m.fn);
    at("/director/log");
    expect(await screen.findAllByText("Got a job")).toHaveLength(60);
    fireEvent.change(screen.getByRole("combobox", { name: "Source" }), { target: { value: "DIRECTOR" } });
    await waitFor(() => expect(m.calls.some((c) => c.url === "/api/director/events?source=DIRECTOR")).toBe(true));
    fireEvent.click(await screen.findByRole("button", { name: "Older" }));
    await waitFor(() => expect(m.calls.some((c) => c.url.includes("before="))).toBe(true));
    fireEvent.click(screen.getByRole("tab", { name: "Reports" }));
    expect(await screen.findByText("arc feud is blacklisted")).toBeInTheDocument();
    expect(screen.getByText("continuity findings: [a mess]")).toBeInTheDocument();
    expect(screen.getByText(/3 posts and comments dropped/)).toBeInTheDocument();
  });

  it("batch: runs, mix against target, what's on the script, and running one now", async () => {
    const view = {
      running: false, scriptedUntil: NOW + 3_600_000, runs: [{ id: "r", kind: "NIGHTLY", status: "DONE", startedAt: NOW - 60_000, finishedAt: NOW, scriptFrom: null, scriptTo: null, fidelity: "FULL", planned: 300, rendered: 280, dropped: 2, costUsd: 0.12, rows: 900, error: null, notes: [] }],
      eventMix: [{ key: "DAILY", target: 0.75, actual: 30 }, { key: "DRAMA", target: 0.25, actual: 10 }], regions: [{ key: "LOCAL", target: 1, actual: 0 }],
      upcoming: { posts: 4, comments: 5, reactions: 6, dms: 7, events: [{ id: "e", at: NOW + 3_600_000, type: "NEW_JOB", defId: null, actor: JESS, target: null, arcId: null, importance: 7, visibility: "PUBLIC", source: "ARC", status: "SCRIPTED", text: null, recapLine: null }] },
    };
    const m = mockFetch({ ...ROUTES, "GET /api/director/batch": view, "POST /api/director/batch/run": undefined });
    vi.stubGlobal("fetch", m.fn);
    at("/director/batch");
    expect(await screen.findByText("280")).toBeInTheDocument();
    expect(screen.getByText("$0.12")).toBeInTheDocument();
    expect(screen.getByTitle("DAILY: target 75%, actual 75% (30)")).toBeInTheDocument();
    expect(screen.getByText(/4 posts, 5 comments, 6 reactions and 7 messages/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Run the batch now/ }));
    await waitFor(() => expect(m.calls.some((c) => c.url === "/api/director/batch/run")).toBe(true));
  });

  it("costs: spend, windows, parked work", async () => {
    const costs = {
      today: 0.5, month: 3.25, byPurpose: [{ key: "render-thread", usd: 3, calls: 40 }], byProvider: [{ key: "anthropic", usd: 3, calls: 40 }], byModel: [{ key: "haiku", usd: 3, calls: 40 }], perBatch: [],
      cacheHitRate: 0.62, fallbackRate: 0.05, calls: 40, windows: [{ id: "usd:daily", used: 0.5, limit: 1, resetsAt: NOW + 1000, closedUntil: null }, { id: "fake:closed", used: 0, limit: 0, resetsAt: NOW, closedUntil: NOW + 3_600_000 }], parked: [{ reason: "usd:daily", jobs: 12, resumes: NOW + 1000 }],
    };
    vi.stubGlobal("fetch", mockFetch({ ...ROUTES, "GET /api/director/costs": costs }).fn);
    at("/director/costs");
    expect(await screen.findByText("$3.25")).toBeInTheDocument();
    expect(screen.getByText("62%")).toBeInTheDocument();
    expect(screen.getByText("$0.50 / $1.00")).toBeInTheDocument();
    expect(screen.getByText(/fake:closed is closed until/)).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
  });

  it("prompts: reads one, and picks up a search from the link", async () => {
    const row = { id: "c1", at: NOW, purpose: "render-thread", alias: "haiku", model: "m", outcome: "OK", costUsd: 0.001, latencyMs: 1200 };
    const m = mockFetch({ ...ROUTES, "GET /api/director/prompts/c1": { callId: "c1", purpose: "render-thread", alias: "haiku", model: "m", prefix: ["World bible"], content: ["Write this"], outcome: "OK", response: "{\"items\":[]}", error: null }, "GET /api/director/prompts": [row] });
    vi.stubGlobal("fetch", m.fn);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/director/prompts?q=%40jess.park"]}>
          <Routes><Route path="/director/:tab" element={<DirectorPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("button", { name: /render thread/ }));
    expect(await screen.findByText("Write this")).toBeInTheDocument();
    expect(screen.getByText("{\"items\":[]}")).toBeInTheDocument();
    expect(m.calls.some((c) => c.url === "/api/director/prompts?q=%40jess.park")).toBe(true);
    await act(async () => {});
  });
});
