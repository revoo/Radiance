import { describe, expect, it } from "vitest";
import { withReaction } from "../api/queries.ts";
import type { Reactions } from "../api/types.ts";
import { initials, relativeTime, whoReacted } from "./format.ts";

describe("relativeTime", () => {
  const now = Date.UTC(2026, 9, 29, 18, 0); // 1 pm in Chicago
  it("counts minutes and hours, then names the day", () => {
    expect(relativeTime(now - 20_000, now)).toBe("Just now");
    expect(relativeTime(now - 5 * 60_000, now)).toBe("5m");
    expect(relativeTime(now - 3 * 3_600_000, now)).toBe("3h");
    expect(relativeTime(now - 26 * 3_600_000, now)).toMatch(/^Yesterday at \d{1,2}:\d{2} [AP]M$/);
    expect(relativeTime(now - 5 * 86_400_000, now)).toMatch(/^Oct 24 at /);
  });
  it("never says a time is in the future", () => {
    expect(relativeTime(now + 60_000, now)).toBe("Just now");
  });
});

describe("whoReacted", () => {
  it("puts R first, then names two and counts the rest", () => {
    expect(whoReacted(1, [], true)).toBe("You");
    expect(whoReacted(2, ["Sarah Kim"], true)).toBe("You and Sarah Kim");
    expect(whoReacted(4, ["Sarah Kim", "Jake Z"], true)).toBe("You, Sarah Kim and 2 others");
    expect(whoReacted(3, ["Sarah Kim", "Jake Z", "Amra H"], false)).toBe("Sarah Kim, Jake Z and 1 other");
  });
});

describe("initials", () => {
  it("takes first and last", () => {
    expect(initials("Mary Ann Kowalski")).toBe("MK");
    expect(initials("Cher")).toBe("C");
  });
});

describe("withReaction (R's optimistic reaction)", () => {
  const base: Reactions = { total: 3, top: ["LIKE", "HAHA"], byKind: { LIKE: 2, HAHA: 1 }, mine: null, recent: [] };
  it("adds R's reaction", () => {
    const r = withReaction(base, "LOVE");
    expect(r.total).toBe(4);
    expect(r.mine).toBe("LOVE");
    expect(r.byKind.LOVE).toBe(1);
  });
  it("switching keeps the total and moves the count", () => {
    const r = withReaction(withReaction(base, "LIKE"), "HAHA");
    expect(r.total).toBe(4);
    expect(r.byKind).toEqual({ LIKE: 2, HAHA: 2 });
    expect(r.mine).toBe("HAHA");
  });
  it("taking it back removes it, and the kind if it was the last", () => {
    const r = withReaction(withReaction(base, "WOW"), null);
    expect(r).toMatchObject({ total: 3, mine: null, byKind: { LIKE: 2, HAHA: 1 } });
    expect(r.top).toEqual(["LIKE", "HAHA"]);
  });
});
