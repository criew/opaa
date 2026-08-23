import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration for the demo-profile smoke test (Issue #232).
 *
 * Deliberately its own config with its own testDir, not a project added to
 * e2e/playwright.config.ts: that keeps this spec out of a bare
 * `pnpm exec playwright test` (no path argument), which both `pnpm test` and CI's
 * e2e.yml run against the "e2e" target's testDir './tests'. Only
 * `pnpm run test:demo-smoke` (-> scripts/run-e2e.mjs --target demo) ever
 * points at this config.
 *
 * Same reasoning as e2e/playwright.config.ts for the rest: no `webServer`
 * entry (the stack is a full Docker Compose stack started by run-e2e.mjs,
 * out of scope for what Playwright's own webServer can express), and
 * `workers: 1` even though there is exactly one test - consistency with the
 * regular suite's own serialisation convention costs nothing here.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: {
    timeout: 20_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
