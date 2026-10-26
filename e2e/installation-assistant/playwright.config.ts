import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration of the "installation" target (ADR-0060): the set-up assistant's spec of
 * the regular suite against a stack in installation mode (scripts/run-e2e.mjs --target
 * installation), which passes E2E_BASE_URL and E2E_INSTALLATION_TOKEN.
 *
 * No retries: completing the installation ends the installation mode for good, so a second attempt
 * would meet a different state than the one the scenario was written for.
 */
export default defineConfig({
  testDir: "../tests",
  testMatch: "installation-assistant.spec.ts",
  timeout: 60_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  outputDir: "../test-results/installation",
  reporter: process.env.CI
    ? [
        ["list"],
        ["html", { open: "never", outputFolder: "../playwright-report-installation" }],
        ["json", { outputFile: "../test-results/installation-results.json" }],
      ]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13005",
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
