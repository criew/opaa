import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the sandbox target: the agent spike with the tool run_code against
 * a stack with OpenSandbox (docker-compose.sandbox.yml). Its own testDir, so these specs never run
 * in the regular suite or in CI. scripts/run-e2e.mjs --target sandbox brings the stack up; against
 * an already running local installation, run `pnpm exec playwright test --config
 * sandbox/playwright.config.ts` with E2E_BASE_URL and E2E_BACKEND_BASE_URL.
 */
export default defineConfig({
  testDir: "./tests",
  // A real model needs several model calls and a sandbox start per turn.
  timeout: 240_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  outputDir: "../test-results",
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13007",
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
