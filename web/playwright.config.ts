import { defineConfig, devices } from "@playwright/test";
import { STORAGE_STATE } from "./e2e/storage-state";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.ts",
  // This isolated fixture has its own web server and Playwright config.
  testIgnore: "**/editor-caret.e2e.ts",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    baseURL: "http://localhost:3001",
    screenshot: "only-on-failure",
    trace: "on-first-retry",
    video: "retain-on-failure",
  },
  projects: [
    // Everything is behind a session now, so logging in is a dependency of every spec.
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: STORAGE_STATE },
      dependencies: ["setup"],
    },
  ],
});
