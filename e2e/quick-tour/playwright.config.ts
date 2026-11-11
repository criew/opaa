import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the quick-tour target: the local-auth stack with the quick tour
 * switched on (e2e/docker-compose.quick-tour.yml).
 *
 * Its own config with its own testDir, like the local-auth target: every other stack switches the
 * tour off, because its hint is a modal dialog over the empty chat, so these specs must never be
 * picked up by a bare `playwright test`.
 *
 * workers: 1, no retry: the scenarios share one installation and its bootstrap administrator, and
 * the marker "hint done" they set cannot be taken back - a retry would start from another state.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 90_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  // A subfolder, so this run does not clear the traces of the local-auth run before it in CI.
  outputDir: "../test-results/quick-tour",
  reporter: process.env.CI
    ? [["list"], ["html", { open: "never", outputFolder: "../playwright-report-quick-tour" }]]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13009",
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
