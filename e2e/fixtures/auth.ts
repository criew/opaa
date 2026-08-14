import { test as base, expect, type Page } from '@playwright/test'

/**
 * Dev users configured in the backend's `dev` profile (see
 * backend/src/main/resources/application.yml, `opaa.auth.dev.users`). There are no credentials:
 * the E2E stack runs in dev auth mode, where the frontend selects the user via `?devUser=` and
 * the backend authenticates every request as that user.
 *
 * `dev-admin` carries the email that matches `opaa.auth.initial-admin-email`, so it is
 * provisioned as SYSTEM_ADMIN on first request. `dev-user` is a plain user — scenarios covering
 * permission boundaries need both.
 */
const ADMIN_USER = 'dev-admin'
const REGULAR_USER = 'dev-user'

/**
 * Opens the app as the given dev user and returns once the app shell has rendered. This is the
 * single reusable building block every scenario should use to reach the app authenticated,
 * instead of re-implementing it per spec.
 */
async function openAs(page: Page, devUser: string): Promise<void> {
  await page.goto(`/?devUser=${encodeURIComponent(devUser)}`)
  await expect(page).not.toHaveURL(/\/login(?:$|[/?#])/, { timeout: 15_000 })
}

export const test = base.extend<{ authenticatedPage: Page; regularUserPage: Page }>({
  /** The app as SYSTEM_ADMIN — the default for scenarios that need indexing or admin endpoints. */
  authenticatedPage: async ({ page }, use) => {
    await openAs(page, ADMIN_USER)
    await use(page)
  },

  /** The app as a non-privileged user, for scenarios covering permission boundaries. */
  regularUserPage: async ({ browser }, use) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    await openAs(page, REGULAR_USER)
    await use(page)
    await context.close()
  },
})

export { expect }
