import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { storyMood, TalkedAbout, WeatherCard, WeatherChip, WeatherCredit } from "./Rail.tsx";
import { isDaytime, skyIcon, skyOf, weatherQuip } from "./weatherLook.ts";

afterEach(() => vi.unstubAllGlobals());

const TIM = { ...SARAH, id: "01TIM", handle: "tim.roe", name: "Tim Roe" };
const story = (over: object) => ({
  title: "A story",
  source: "Wire",
  postId: "p",
  posts: 1,
  comments: 0,
  region: "NATIONAL",
  controversy: 0.1,
  sensitivity: "NORMAL",
  people: [SARAH],
  ...over,
});

const AT = Date.UTC(2026, 10, 2, 18); // noon in Chicago
const RAIL = {
  weather: {
    hour: AT,
    tempF: 34,
    feelsLikeF: 27,
    windMph: 12,
    wmo: 73,
    highF: 36,
    lowF: 22,
    sky: "light snow",
    description: "34°F, feels like 27, light snow",
    next: [
      { hour: AT + 2 * 3_600_000, tempF: 33, wmo: 73 },
      { hour: AT + 4 * 3_600_000, tempF: 30, wmo: 3 },
    ],
  },
  talkedAbout: [
    story({ title: "Senate passes the budget", source: "The Hill", postId: "p1", posts: 3, comments: 4, controversy: 0.8, people: [SARAH, TIM] }),
    story({ title: "Bears lose again", source: "Sun-Times", postId: "p2", region: "LOCAL" }),
  ],
};

describe("WeatherCard", () => {
  it("shows the temperature, the sky, the numbers, the next hours and an opinion", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": RAIL }).fn);
    renderWith(<WeatherCard />);
    const card = await screen.findByRole("region", { name: "Weather" });
    expect(within(card).getByText("34°")).toBeInTheDocument();
    expect(card).toHaveTextContent("light snow");
    expect(card).toHaveTextContent("Feels 27°");
    expect(card).toHaveTextContent("H 36° · L 22°");
    expect(card).toHaveTextContent("12 mph");
    expect(card).toHaveTextContent("Flurries. Nobody panic.");
    expect(within(card).getAllByRole("listitem")).toHaveLength(2);
    expect(within(card).getAllByRole("listitem")[0]).toHaveTextContent("2p");
  });

  it("shows nothing when there's no weather yet", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": { weather: null, talkedAbout: [] } }).fn);
    const { container } = renderWith(<WeatherCard />);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });
});

describe("WeatherChip", () => {
  it("shows the temperature and the sky", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": RAIL }).fn);
    renderWith(<WeatherChip />);
    expect(await screen.findByText(/34°F · light snow/)).toBeInTheDocument();
  });
});

describe("the sky", () => {
  it.each([
    [0, "clear"],
    [1, "partly"],
    [2, "partly"],
    [3, "cloudy"],
    [45, "fog"],
    [48, "fog"],
    [51, "drizzle"],
    [57, "drizzle"],
    [61, "rain"],
    [67, "rain"],
    [71, "snow"],
    [77, "snow"],
    [80, "rain"],
    [82, "rain"],
    [85, "snow"],
    [86, "snow"],
    [95, "storm"],
    [99, "storm"],
  ])("WMO %i is %s", (wmo, sky) => {
    expect(skyOf(wmo)).toBe(sky);
  });

  it("uses a moon at night and a sun by day", () => {
    expect(isDaytime(6)).toBe(true);
    expect(isDaytime(18)).toBe(true);
    expect(isDaytime(19)).toBe(false);
    expect(isDaytime(5)).toBe(false);
    expect(skyIcon("clear", true)).not.toBe(skyIcon("clear", false));
    expect(skyIcon("rain", true)).toBe(skyIcon("rain", false));
  });

  it.each([
    ["storm", 70, 70, "Stay off the lakefront."],
    ["snow", 20, 10, "Dibs season is open."],
    ["snow", 30, 25, "Flurries. Nobody panic."],
    ["clear", 12, 2, "Brutal. Check on your car battery."],
    ["rain", 50, 50, "Bring an umbrella. It won't help."],
    ["clear", 95, 100, "Find the AC. Or the lake."],
    ["clear", 75, 75, "Patio weather. Go."],
    ["clear", 55, 55, "Shorts, if you're brave."],
    ["cloudy", 45, 40, "Classic Chicago gray."],
    ["clear", 45, 42, "Cooler by the lake."],
    ["cloudy", 35, 28, "Real coat weather."],
  ] as const)("%s at %i°, feeling like %i°, gets: %s", (sky, temp, feels, quip) => {
    expect(weatherQuip(sky, temp, feels)).toBe(quip);
  });
});

describe("TalkedAbout", () => {
  it("leads with the biggest story, showing who's on it, and ranks the rest", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": RAIL }).fn);
    renderWith(<TalkedAbout />);
    const hero = await screen.findByRole("link", { name: /Senate passes the budget/ });
    expect(hero).toHaveAttribute("href", "/posts/p1");
    expect(hero).toHaveTextContent("Heated");
    expect(hero).toHaveTextContent("Sarah +2 · 4 replies");
    expect(hero).toHaveTextContent("The Hill");
    const next = screen.getByRole("link", { name: /Bears lose again/ });
    expect(next).toHaveAttribute("href", "/posts/p2");
    expect(next).toHaveTextContent("New");
    expect(next).not.toHaveTextContent("+");
  });

  it("is hidden when nobody's talking about the news", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": { weather: null, talkedAbout: [] } }).fn);
    const { container } = renderWith(<TalkedAbout />);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });

  it("shows at most four, so contacts keep their room", async () => {
    const many = Array.from({ length: 5 }, (_, i) => story({ title: `Story ${i}`, postId: `p${i}` }));
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": { weather: null, talkedAbout: many } }).fn);
    renderWith(<TalkedAbout />);
    expect(await screen.findByRole("link", { name: /Story 3/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Story 4/ })).not.toBeInTheDocument();
  });
});

describe("a story's mood", () => {
  it("treats tragedy gently, however controversial or talked about", () => {
    expect(storyMood(story({ sensitivity: "TRAGIC", controversy: 0.9, posts: 9 }) as never).label).toBe("Heavy");
  });

  it("calls an argument an argument, and talk just talk", () => {
    expect(storyMood(story({ controversy: 0.7, posts: 3 }) as never).label).toBe("Heated");
    expect(storyMood(story({ controversy: 0.7, posts: 1 }) as never).label).toBe("Touchy");
    expect(storyMood(story({ controversy: 0.2, posts: 2, comments: 1 }) as never).label).toBe("Buzzing");
    expect(storyMood(story({ controversy: 0.2, posts: 1 }) as never).label).toBe("New");
  });
});

describe("WeatherCredit", () => {
  it("credits Open-Meteo with a safe outside link, as its license requires", () => {
    renderWith(<WeatherCredit />);
    const link = screen.getByRole("link", { name: "Open-Meteo.com" });
    expect(link).toHaveAttribute("href", "https://open-meteo.com/");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });
});
