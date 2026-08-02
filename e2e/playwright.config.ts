import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration for the OPAA browser E2E suite.
 *
 * The suite always targets an already running stack (see scripts/run-e2e.mjs,
 * which starts the stack via Docker Compose before invoking `playwright test`
 * and tears it down afterwards). There is no Playwright `webServer` entry
 * here on purpose: composing the full stack (Postgres + backend + frontend)
 * is out of scope for what Playwright's webServer can express.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
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
