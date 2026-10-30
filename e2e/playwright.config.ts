import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration for the OPAA browser E2E suite.
 *
 * The suite always targets an already running stack (see scripts/run-e2e.mjs,
 * which starts the stack via Docker Compose before invoking `playwright test`
 * and tears it down afterwards). There is no Playwright `webServer` entry
 * here on purpose: composing the full stack (Postgres + backend + frontend)
 * is out of scope for what Playwright's webServer can express.
 *
 * fullyParallel is deliberately false: every spec shares one stack/database
 * (see e2e/README.md "Serialisierungs-Konvention"), so specs that mutate
 * shared state (indexing jobs, rate limits, ...) must not run concurrently.
 * Individual specs may still opt into parallel `test()` calls internally if
 * they are self-contained.
 */
const isFirefox = process.env.E2E_BROWSER === "firefox";

export default defineConfig({
  testDir: "./tests",
  // Needs a stack in installation mode: the target "installation" runs it with its own config.
  testIgnore: ["**/installation-assistant.spec.ts"],
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // Ein Retry auch lokal, nicht nur in der CI: Auf einem ausgelasteten Entwickler-Host
  // (parallele Agent-Sessions, knapper Speicher neben Docker-Stack und Chromium) reißen einzelne,
  // sonst sekundenschnelle Schritte die 10-s/30-s-Budgets - über drei volle Läufe wanderte der
  // Timeout durch drei verschiedene Szenarien, während dasselbe Szenario isoliert und in der CI
  // stets grün war. Der Retry ändert keine einzige Zusicherung: eine echte Regression fällt auch
  // im zweiten Versuch, und Playwright weist wiederholte Läufe im Report als "flaky" aus, statt
  // sie zu verstecken.
  retries: 1,
  workers: 1,
  // The JSON report is what the CI alarm issue reads to list the failed scenarios by name; the
  // github reporter puts every failure, with the step it hung in, on the check run as an annotation.
  // The Firefox smoke run writes beside the Chromium run of the same CI job instead of over it.
  outputDir: isFirefox ? "test-results-firefox" : "test-results",
  reporter: process.env.CI
    ? [
        ["list"],
        ["github"],
        [
          "html",
          {
            open: "never",
            outputFolder: isFirefox
              ? "playwright-report-firefox"
              : "playwright-report",
          },
        ],
        [
          "json",
          {
            outputFile: isFirefox
              ? "test-results-firefox/results.json"
              : "test-results/results.json",
          },
        ],
      ]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  // E2E_BROWSER=firefox swaps the full Chromium suite for the cross-engine smoke run: only the
  // scenarios tagged @browser-smoke, in Playwright's Firefox (Gecko).
  projects: isFirefox
    ? [
        {
          name: "firefox",
          use: { ...devices["Desktop Firefox"] },
          grep: /@browser-smoke/,
        },
      ]
    : [
        {
          name: "chromium",
          use: { ...devices["Desktop Chrome"] },
        },
      ],
});
