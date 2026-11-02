import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch, renderWith } from "../test/render.tsx";
import { TalkedAbout, WeatherChip, WeatherCredit } from "./Rail.tsx";

afterEach(() => vi.unstubAllGlobals());

const RAIL = {
  weather: { tempF: 34, sky: "light snow", description: "34°F, feels like 27, light snow" },
  talkedAbout: [
    { title: "Senate passes the budget", source: "The Hill", postId: "p1", posts: 3, comments: 1 },
    { title: "Bears lose again", source: "Sun-Times", postId: "p2", posts: 1, comments: 0 },
  ],
};

describe("WeatherChip", () => {
  it("shows the temperature and the sky", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": RAIL }).fn);
    renderWith(<WeatherChip />);
    expect(await screen.findByText(/34°F · light snow/)).toBeInTheDocument();
  });

  it("shows nothing when there's no weather yet", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": { weather: null, talkedAbout: [] } }).fn);
    const { container } = renderWith(<WeatherChip />);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });
});

describe("TalkedAbout", () => {
  it("links each story to a post about it and counts the talk", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": RAIL }).fn);
    renderWith(<TalkedAbout />);
    const first = await screen.findByRole("link", { name: /Senate passes the budget/ });
    expect(first).toHaveAttribute("href", "/posts/p1");
    expect(first).toHaveTextContent("The Hill · 3 posts · 1 comment");
    expect(screen.getByRole("link", { name: /Bears lose again/ })).toHaveTextContent("Sun-Times · 1 post");
  });

  it("is hidden when nobody's talking about the news", async () => {
    vi.stubGlobal("fetch", mockFetch({ "GET /api/rail": { weather: null, talkedAbout: [] } }).fn);
    const { container } = renderWith(<TalkedAbout />);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
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
