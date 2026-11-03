import { describe, expect, it } from "vitest";

describe("the flash on a new comment", () => {
  /** Regression: it faded to transparent and held there, so a new comment's bubble vanished until it stopped being new. */
  it("fades back to the bubble's own color", async () => {
    // read as a file: the test setup turns CSS imports off
    const fs = await import(/* @vite-ignore */ "node:" + "fs");
    const css: string = fs.readFileSync("src/index.css", "utf8");
    const flash = /@keyframes flash \{([\s\S]*?)\n\}/.exec(css)?.[1];
    expect(flash).toContain("--accent-soft");
    expect(flash).not.toContain("transparent");
  });
});
