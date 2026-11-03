import { QueryClient } from "@tanstack/react-query";
import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ME, mockFetch, renderWith } from "../test/render.tsx";
import { PostPage } from "./PostPage.tsx";

afterEach(() => vi.unstubAllGlobals());

describe("PostPage", () => {
  /** Regression: a story in the rail led to a post R can't see, and the page spun through its retries. */
  it("says a post isn't here at once, without retrying a 404", async () => {
    const api = mockFetch({});
    vi.stubGlobal("fetch", api.fn);
    // the app's own client, with the default retries the page has to opt out of
    renderWith(<PostPage me={ME} />, new QueryClient());
    expect(await screen.findByText("This post isn't here", {}, { timeout: 500 })).toBeInTheDocument();
    expect(api.calls).toHaveLength(1);
  });
});
