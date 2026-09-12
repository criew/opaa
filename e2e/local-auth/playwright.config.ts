import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the local-auth target (ADR-0033, #1543).
 *
 * Its own config with its own testDir, for the same reason the demo-smoke run has one: these specs
 * need a stack the regular suite does not have (operating mode "oidc" without Keycloak, plus the
 * mail catcher), so they must never be picked up by a bare `playwright test` as run by the "e2e"
 * target and CI's e2e.yml. scripts/run-e2e.mjs --target local-auth brings that stack up, passes
 * E2E_BASE_URL/E2E_MAILPIT_BASE_URL and the bootstrap administrator's credentials, and tears it
 * down again.
 *
 * workers: 1 and fullyParallel: false are not a convention here but a requirement: the scenarios
 * share one installation whose *global* state they change (the switch of the local account
 * management, the SMTP settings, the self-registration domains), and the rate limits of the
 * sign-in endpoints are keyed per client address, which is the same for all of them.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // No retry, unlike the regular suite (e2e/playwright.config.ts): a retried scenario would replay
  // its account creation and its sign-ins against an installation the first attempt already
  // changed - a failed "enable the management" step leaves it enabled, and the retry then asserts
  // against a different starting state than the one it was written for. A flake here has to be
  // read in the report, not papered over.
  retries: 0,
  workers: 1,
  // Bericht, Traces und Screenshots landen unter e2e/, nicht unter e2e/local-auth/: Von dort lädt der
  // Workflow sie hoch (.github/workflows/local-auth-e2e.yml), wie bei den beiden anderen Zielen.
  outputDir: "../test-results",
  reporter: process.env.CI
    ? [
        ["list"],
        ["html", { open: "never", outputFolder: "../playwright-report" }],
        ["json", { outputFile: "../test-results/local-auth-results.json" }],
      ]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13001",
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
