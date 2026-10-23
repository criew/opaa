import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration of the offline target (scripts/offline.mjs). Its own testDir, so a bare
 * `playwright test` of the regular suite never picks up a scenario that needs the isolated stack.
 * One serial journey against one installation: no retry, as a retry would replay it against the
 * state the first attempt left behind.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 120_000,
  expect: {
    timeout: 20_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  outputDir: "../test-results",
  reporter: process.env.CI
    ? [
        ["list"],
        ["github"],
        ["html", { open: "never", outputFolder: "../playwright-report" }],
      ]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13003",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
