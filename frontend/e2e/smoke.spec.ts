import { expect, request, test } from "@playwright/test";

// the built-in login of the offline profile
const USER = "r";
const PASSWORD = "radiance";

/** Makes a world and a player through the API, the way a fresh install would: no UI for genesis in this test. */
test.beforeAll(async () => {
  test.setTimeout(180_000);
  const api = await request.newContext({ baseURL: "http://localhost:8080" });
  await api.get("/api/me");
  const token = async () => (await api.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const login = await api.post("/api/auth/login", { form: { username: USER, password: PASSWORD }, headers: { "X-XSRF-TOKEN": await token() } });
  expect(login.ok()).toBeTruthy();
  const headers = async () => ({ "X-XSRF-TOKEN": await token() });
  expect((await api.post("/api/director/genesis/preview?seed=7&skeletonOnly=true", { headers: await headers() })).ok()).toBeTruthy();
  expect((await api.post("/api/director/genesis/commit", { headers: await headers() })).ok()).toBeTruthy();
  const profile = await api.put("/api/profile", {
    headers: await headers(),
    data: { name: "Sam Rivera", bio: "Engineer, runner.", employer: "Acme", livesIn: "Logan Square", circles: ["softball"], interests: ["tech", "fitness"], backstory: "LOCAL" },
  });
  expect(profile.ok()).toBeTruthy();
  await api.dispose();
});

// SPEC §8: log in → the feed is already there → post → a reaction within ~10 s, then a comment
test("log in, the feed is there, post, and people answer", async ({ page }) => {
  const problems: string[] = [];
  page.on("pageerror", (e) => problems.push(e.message));

  await page.goto("/");
  await page.getByLabel(/user/i).fill(USER);
  await page.getByLabel(/password/i).fill(PASSWORD);
  await page.getByRole("button", { name: /log in|sign in/i }).click();

  await expect(page.getByTestId("post-text").first()).toBeVisible({ timeout: 15_000 });

  const words = `Testing, testing ${Date.now()}`;
  await page.getByPlaceholder(/on your mind/i).fill(words);
  await page.getByRole("button", { name: "Post", exact: true }).click();
  const card = page.locator("article", { hasText: words });
  await expect(card).toBeVisible({ timeout: 5_000 });

  await expect(card.getByTestId("reaction-summary")).toBeVisible({ timeout: 15_000 });
  await expect(card.getByRole("button", { name: /\d+ comment/ })).toBeVisible({ timeout: 90_000 });

  expect(problems).toEqual([]);
});
