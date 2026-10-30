import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./client.ts";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

describe("api", () => {
  it("sends the CSRF token from the cookie on writes, not on reads", async () => {
    document.cookie = "XSRF-TOKEN=tok%2Fen; path=/";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await api("/api/feed");
    await api("/api/notifications/read", { method: "POST" });
    const [, read] = fetchMock.mock.calls[0]!;
    const [, write] = fetchMock.mock.calls[1]!;
    expect((read!.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBeUndefined();
    expect((write!.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("tok/en");
    expect(write!.credentials).toBe("same-origin");
  });

  it("posts forms url-encoded and JSON as JSON", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(new Response("{}", { status: 200 })));
    await api("/api/auth/login", { form: { username: "r", password: "p w" } });
    await api("/api/posts", { json: { text: "hi" } });
    expect(String(fetchMock.mock.calls[0]![1]!.body)).toBe("username=r&password=p+w");
    expect(fetchMock.mock.calls[1]![1]!.body).toBe('{"text":"hi"}');
    expect((fetchMock.mock.calls[1]![1]!.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("turns a problem detail into an ApiError with its status and detail", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ status: 429, detail: "Too many failed logins." }), { status: 429 }),
    );
    const err = await api("/api/auth/login", { form: {} }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 429, detail: "Too many failed logins." });
  });
});
