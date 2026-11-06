import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration of the target "ocr": the acceptance run of text recognition against
 * the real opaa-ocr (e2e/docker-compose.ocr.yml), recorded as video. Its own testDir, so the spec
 * never runs in the regular suite, which has the docling-serve stand-in instead.
 * scripts/run-e2e.mjs --target ocr brings the stack up; .github/workflows/ocr-acceptance.yml runs
 * it in CI and keeps the video.
 */
export default defineConfig({
  testDir: "./tests",
  // OCR of five scanned pages on the CPU of a CI runner takes minutes.
  timeout: 1_500_000,
  expect: {
    timeout: 20_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  outputDir: "../test-results-ocr",
  reporter: process.env.CI
    ? [
        ["list"],
        ["github"],
        ["html", { open: "never", outputFolder: "../playwright-report-ocr" }],
      ]
    : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:13008",
    trace: "retain-on-failure",
    screenshot: "on",
    // Without these a click on an element that never appears waits for the whole test budget.
    actionTimeout: 60_000,
    navigationTimeout: 60_000,
    // A calm pace a viewer can follow; the pauses between the steps are in the spec.
    launchOptions: { slowMo: 200 },
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 900 },
        video: { mode: "on", size: { width: 1440, height: 900 } },
      },
    },
  ],
});
