import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { RelationshipsPage } from "./RelationshipsPage.tsx";

afterEach(() => vi.unstubAllGlobals());

const TIM = { ...SARAH, id: "01TIM", handle: "tim.roe", name: "Tim Roe" };
const ANN = { ...SARAH, id: "01ANN", handle: "ann.lee", name: "Ann Lee" };

describe("RelationshipsPage", () => {
  it("lists everyone in the order given, with their meter and 7-day trend", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch({
        "GET /api/me/relationships": [
          { actor: SARAH, meter: 62, trend: 5, kind: "FRIEND", friend: true },
          { actor: ANN, meter: 3, trend: null, kind: "ACQUAINTANCE", friend: false },
          { actor: TIM, meter: -40, trend: -7, kind: "ACQUAINTANCE", friend: true },
        ],
      }).fn,
    );
    renderWith(<RelationshipsPage />);
    const rows = await screen.findAllByRole("listitem");
    expect(rows.map((r) => within(r).getByRole("link").getAttribute("href"))).toEqual([
      "/people/sarah.kim",
      "/people/ann.lee",
      "/people/tim.roe",
    ]);
    expect(within(rows[0]!).getByText("+62")).toBeInTheDocument();
    expect(within(rows[0]!).getByText("Really likes you")).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent("+5");
    expect(within(rows[1]!).getByText("—")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("-40")).toBeInTheDocument();
    expect(rows[2]).toHaveTextContent("−7");
  });
});
