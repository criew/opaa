import { defineConfig } from "@playwright/test";

/**
 * Playwright configuration of the two-instance target (ADR-0053; scripts/run-e2e.mjs --target
 * multi-instance for Compose, deploy/helm/ci/multi-instance-test.sh for kind).
 *
 * The scenarios talk to the API of both instances and drive the platform (restart, rolling
 * update, load); none of them opens a browser. They share one installation and are ordered by file
 * name: the orchestrator runs 01 to 04 against a cold start of the current version and 05 against
 * a stack it started with the previous one. One worker, no retry: a repeated scenario would meet the
 * state its first attempt left behind.
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 15 * 60_000,
  expect: {
    timeout: 30_000,
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
        ["json", { outputFile: "../test-results/multi-instance-results.json" }],
      ]
    : [["list"]],
});
