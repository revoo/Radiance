import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { live } from "../live/store.ts";
import { ME, mockFetch, renderWith, SARAH } from "../test/render.tsx";
import { Composer } from "./Composer.tsx";

afterEach(() => live.reset());

function type(el: HTMLTextAreaElement, value: string) {
  fireEvent.change(el, { target: { value, selectionStart: value.length } });
  el.setSelectionRange(value.length, value.length);
}

describe("Composer", () => {
  it("greets R by first name", () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/people": [] }).fn);
    renderWith(<Composer me={ME} />);
    expect(screen.getByLabelText("Write a post")).toHaveAttribute("placeholder", "What's on your mind, Sam?");
  });

  it("autocompletes @mentions from R's friends", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/people": [SARAH] }).fn);
    renderWith(<Composer me={ME} />);
    const box = screen.getByLabelText<HTMLTextAreaElement>("Write a post");
    await waitFor(() => {
      type(box, "brunch with @sar");
      expect(screen.getByRole("listbox", { name: "Mention a friend" })).toBeInTheDocument();
    });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(box.value).toBe("brunch with @sarah.kim ");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("posts on Ctrl+Enter and clears", async () => {
    const m = mockFetch({ "GET /api/people": [], "POST /api/posts": { id: "p9" } });
    vi.spyOn(globalThis, "fetch").mockImplementation(m.fn);
    renderWith(<Composer me={ME} />);
    const box = screen.getByLabelText<HTMLTextAreaElement>("Write a post");
    type(box, "  hello Chicagoland  ");
    fireEvent.keyDown(box, { key: "Enter", ctrlKey: true });
    await waitFor(() => expect(m.calls.some((c) => c.method === "POST" && c.url === "/api/posts")).toBe(true));
    expect(JSON.parse(m.calls.find((c) => c.method === "POST")!.body!)).toEqual({ text: "hello Chicagoland" });
    expect(box.value).toBe("");
  });

  it("starts a post from a prompt chip", () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(mockFetch({ "GET /api/people": [] }).fn);
    renderWith(<Composer me={ME} />);
    fireEvent.click(screen.getByRole("button", { name: "Hot take" }));
    expect(screen.getByLabelText<HTMLTextAreaElement>("Write a post").value).toBe("Hot take: ");
  });

  it("tells R when a post didn't go through", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      mockFetch({ "GET /api/people": [], "POST /api/posts": new Response(JSON.stringify({ status: 503 }), { status: 503 }) }).fn,
    );
    renderWith(<Composer me={ME} />);
    const box = screen.getByLabelText<HTMLTextAreaElement>("Write a post");
    type(box, "anyone up?");
    await act(async () => {
      fireEvent.keyDown(box, { key: "Enter", ctrlKey: true });
    });
    await waitFor(() => expect(live.get().toasts.map((t) => t.text)).toContain("The world is waking up. Try posting again in a few seconds."));
  });
});
