import { test as base, expect, type Page } from '@playwright/test'

/**
 * Test user credentials for the E2E stack (see ../e2e.env,
 * OPAA_AUTH_BASIC_USERNAME / OPAA_AUTH_BASIC_PASSWORD). Not real secrets.
 */
const E2E_USERNAME = process.env.E2E_USERNAME ?? 'e2e-user'
const E2E_PASSWORD = process.env.E2E_PASSWORD ?? 'e2e-password'

/**
 * Logs the E2E test user in through the real login form and returns once
 * the app has navigated away from `/login`.
 *
 * This is the single reusable building block every scenario should use to
 * reach the app as an authenticated user, instead of re-implementing the
 * login flow in each spec (see AC "Anmeldung ist als wiederverwendbarer
 * Baustein herausgezogen").
 */
async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('Benutzername').fill(E2E_USERNAME)
  await page.getByLabel('Passwort').fill(E2E_PASSWORD)
  await page.getByRole('button', { name: 'Anmelden' }).click()
  await expect(page).not.toHaveURL(/\/login(?:$|[/?#])/, { timeout: 15_000 })
}

export const test = base.extend<{ authenticatedPage: Page }>({
  authenticatedPage: async ({ page }, use) => {
    await login(page)
    await use(page)
  },
})

export { expect }
