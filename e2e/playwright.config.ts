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
export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // Ein Retry auch lokal, nicht nur in der CI (#815): Auf einem ausgelasteten Entwickler-Host
  // (parallele Agent-Sessions, knapper Speicher neben Docker-Stack und Chromium) reißen einzelne,
  // sonst sekundenschnelle Schritte die 10-s/30-s-Budgets - über drei volle Läufe wanderte der
  // Timeout durch drei verschiedene Szenarien, während dasselbe Szenario isoliert und in der CI
  // stets grün war. Der Retry ändert keine einzige Zusicherung: eine echte Regression fällt auch
  // im zweiten Versuch, und Playwright weist wiederholte Läufe im Report als "flaky" aus, statt
  // sie zu verstecken.
  retries: 1,
  workers: 1,
  reporter: process.env.CI
    ? [["list"], ["html", { open: "never" }]]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
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
