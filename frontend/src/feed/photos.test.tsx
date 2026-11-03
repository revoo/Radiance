import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ME, mockFetch, post, renderWith } from "../test/render.tsx";
import { Avatar } from "../ui/kit.tsx";
import { Composer } from "./Composer.tsx";
import { fitWithin, uploadPhoto } from "./photos.ts";
import { PostCard } from "./PostCard.tsx";

const MEDIA = { id: "m1", url: "/api/media/m1", thumbUrl: "/api/media/m1?size=thumb", description: "a dog on a couch", status: "READY", width: 1600, height: 1200 };

/** jsdom has no canvas or image decoding: stand in for the browser's, reporting what was asked. */
function fakeImaging() {
  const drawn: number[][] = [];
  vi.stubGlobal("createImageBitmap", vi.fn(async () => ({ width: 4000, height: 3000, close: vi.fn() })));
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(function (this: HTMLCanvasElement) {
    drawn.push([this.width, this.height]);
    return { drawImage: vi.fn() } as never;
  } as never);
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((cb) => cb(new Blob(["jpeg"], { type: "image/jpeg" })));
  URL.createObjectURL = vi.fn(() => "blob:preview");
  URL.revokeObjectURL = vi.fn();
  return drawn;
}

const ROUTES = {
  "GET /api/people": [],
  "POST /api/media/uploads": { mediaId: "m1", putUrl: "/api/media/m1/upload?size=full", thumbPutUrl: "/api/media/m1/upload?size=thumb" },
  "PUT /api/media/m1/upload": undefined,
  "POST /api/media/m1/complete": MEDIA,
  "POST /api/posts": { ...post({ id: "p9", body: "" }), media: MEDIA },
};

const photoFile = () => new File(["x"], "dog.jpg", { type: "image/jpeg" });

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=tok; path=/";
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("fitWithin", () => {
  it.each([
    [4000, 3000, 1600, 1600, 1200],
    [3000, 4000, 1600, 1200, 1600],
    [800, 600, 1600, 800, 600],
    [1600, 1600, 1600, 1600, 1600],
    [4000, 1, 400, 400, 0],
  ])("%ix%i inside %i is %ix%i", (w, h, max, ew, eh) => {
    expect(fitWithin(w, h, max)).toEqual({ width: ew, height: eh });
  });
});

describe("uploadPhoto", () => {
  it("makes both sizes and sends each, with the CSRF token, then completes", async () => {
    const drawn = fakeImaging();
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", m.fn);
    const headers: Record<string, string>[] = [];
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      if (init?.method === "PUT") headers.push(init.headers as Record<string, string>);
      return m.fn(url, init);
    });
    await expect(uploadPhoto(photoFile())).resolves.toEqual(MEDIA);
    expect(drawn).toEqual(expect.arrayContaining([[1600, 1200], [400, 300]]));
    expect(m.calls.map((c) => `${c.method} ${c.url}`)).toEqual(
      expect.arrayContaining(["PUT /api/media/m1/upload?size=full", "PUT /api/media/m1/upload?size=thumb", "POST /api/media/m1/complete"]),
    );
    expect(headers).toHaveLength(2);
    expect(headers[0]!["X-XSRF-TOKEN"]).toBe("tok");
    expect(JSON.parse(m.calls.find((c) => c.url === "/api/media/uploads")!.body!)).toMatchObject({ mime: "image/jpeg", width: 1600, height: 1200 });
  });

  it("refuses what isn't a photo, or can't be read, without calling the server", async () => {
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", m.fn);
    await expect(uploadPhoto(new File(["x"], "virus.exe", { type: "application/octet-stream" }))).rejects.toThrow("isn't a photo");
    vi.stubGlobal("createImageBitmap", vi.fn(async () => { throw new Error("nope"); }));
    await expect(uploadPhoto(photoFile())).rejects.toThrow("Couldn't read that photo");
    expect(m.calls).toHaveLength(0);
  });
});

describe("posting a photo from the composer", () => {
  it("uploads on pick, shows a preview, and posts it, even with no words", async () => {
    fakeImaging();
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", m.fn);
    const { container } = renderWith(<Composer me={ME} />);
    const input = container.querySelector<HTMLInputElement>('input[type="file"]')!;
    fireEvent.change(input, { target: { files: [photoFile()] } });
    expect(await screen.findByAltText("Your photo")).toHaveAttribute("src", "blob:preview");
    const button = await screen.findByRole("button", { name: "Post" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(() => expect(m.calls.some((c) => c.method === "POST" && c.url === "/api/posts")).toBe(true));
    expect(JSON.parse(m.calls.find((c) => c.url === "/api/posts")!.body!)).toEqual({ text: "", mediaIds: ["m1"] });
    await waitFor(() => expect(screen.queryByAltText("Your photo")).toBeNull());
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:preview");
  });

  it("can't post while the photo is still uploading", async () => {
    fakeImaging();
    let finish: (v: Response) => void = () => {};
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) =>
      url.endsWith("/complete") ? new Promise<Response>((r) => { finish = r; }) : m.fn(url, init));
    const { container } = renderWith(<Composer me={ME} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [photoFile()] } });
    const button = await screen.findByRole("button", { name: "Post" });
    expect(button).toBeDisabled();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
      finish(new Response(JSON.stringify(MEDIA), { status: 200 }));
    });
    await waitFor(() => expect(button).toBeEnabled());
  });

  it("says so when an upload fails, and lets you carry on without it", async () => {
    fakeImaging();
    vi.stubGlobal("fetch", mockFetch({ "GET /api/people": [], "POST /api/media/uploads": new Response(JSON.stringify({ detail: "That photo is too large" }), { status: 400 }) }).fn);
    const { container } = renderWith(<Composer me={ME} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [photoFile()] } });
    expect(await screen.findByRole("alert")).toHaveTextContent("That photo is too large");
    expect(screen.queryByAltText("Your photo")).toBeNull();
  });

  it("lets you take the photo back off", async () => {
    fakeImaging();
    const m = mockFetch(ROUTES);
    vi.stubGlobal("fetch", m.fn);
    const { container } = renderWith(<Composer me={ME} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [photoFile()] } });
    await screen.findByAltText("Your photo");
    fireEvent.click(screen.getByRole("button", { name: "Remove photo" }));
    expect(screen.queryByAltText("Your photo")).toBeNull();
  });
});

describe("a photo in a post", () => {
  it("shows with its description as alt text, and opens bigger", () => {
    renderWith(<PostCard post={post({ kind: "PHOTO", media: MEDIA })} me={ME} />);
    const image = screen.getByAltText("a dog on a couch");
    expect(image).toHaveAttribute("src", "/api/media/m1");
    fireEvent.click(screen.getByRole("button", { name: /See this photo bigger/ }));
    expect(screen.getByRole("dialog", { name: "Photo" })).toBeInTheDocument();
  });

  it("is simply absent when there's no photo yet, or it won't load", () => {
    renderWith(<PostCard post={post({ kind: "PHOTO", media: null })} me={ME} />);
    expect(screen.queryByRole("button", { name: /See this photo/ })).toBeNull();
    renderWith(<PostCard post={post({ id: "x", kind: "PHOTO", media: MEDIA })} me={ME} />);
    fireEvent.error(screen.getByAltText("a dog on a couch"));
    expect(screen.queryByAltText("a dog on a couch")).toBeNull();
  });

  it("drops the empty caption area on a photo with no words", () => {
    const { container } = renderWith(<PostCard post={post({ kind: "PHOTO", body: "", media: MEDIA })} me={ME} />);
    expect(container.querySelector('[data-testid="post-text"]')).toBeNull();
  });
});

describe("Avatar", () => {
  const who = { id: "a", name: "Sarah Kim", handle: "sarah.kim" };

  it("shows the picture when there is one", () => {
    const { container } = renderWith(<Avatar actor={{ ...who, avatarUrl: "/api/media/a?size=thumb" }} />);
    expect(container.querySelector("img")).toHaveAttribute("src", "/api/media/a?size=thumb");
  });

  it("shows initials with no picture, or when the picture fails", () => {
    const { container } = renderWith(<Avatar actor={who} />);
    expect(screen.getByText("SK")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    const second = renderWith(<Avatar actor={{ ...who, id: "b", avatarUrl: "/broken.jpg" }} />);
    fireEvent.error(second.container.querySelector("img")!);
    expect(second.container.querySelector("img")).toBeNull();
  });
});
