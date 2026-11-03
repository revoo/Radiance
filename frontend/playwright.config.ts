import { rmSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig } from "@playwright/test";

// a throwaway world: the smoke test makes its own, whatever's in the developer's database
const data = resolve(import.meta.dirname, "test-results/e2e-world");
// this file is loaded again in each test worker, by which time the server has the database open
if (!process.env.TEST_WORKER_INDEX) rmSync(data, { recursive: true, force: true });
const root = resolve(import.meta.dirname, "..");
const gradle = process.platform === "win32" ? `"${resolve(root, "gradlew.bat")}"` : "./gradlew";

export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? "github" : "list",
  outputDir: "test-results/playwright",
  use: { baseURL: "http://localhost:5173", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { browserName: "chromium", channel: process.env.PW_CHANNEL } }],
  webServer: [
    {
      command: `${gradle} :backend:bootRun --console=plain`,
      cwd: root,
      url: "http://localhost:8080/actuator/health",
      timeout: 240_000,
      reuseExistingServer: false,
      env: {
        SPRING_PROFILES_ACTIVE: "offline",
        RADIANCE_PERSISTENCE_DB_PATH: resolve(data, "radiance.db"),
        RADIANCE_GENESIS_SEED_FILE: resolve(data, "seed.json"),
      },
    },
    { command: "npm run dev", url: "http://localhost:5173", timeout: 60_000, reuseExistingServer: false },
  ],
});
