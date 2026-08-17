import { test as base, expect, type Page } from '@playwright/test'

/**
 * Dev users available to the E2E stack. There are no credentials: the stack runs in dev auth
 * mode, where the frontend selects the user via `?devUser=` and the backend authenticates every
 * request as that user.
 *
 * `dev-admin` and `dev-user` are configured in the backend's `dev` profile defaults (see
 * backend/src/main/resources/application.yml, `opaa.auth.dev.users`) and exist in every `dev`
 * profile backend, not just this suite. `dev-admin` carries the email that matches
 * `opaa.auth.initial-admin-email`, so it is provisioned as SYSTEM_ADMIN on first request.
 * `dev-user` is a plain user.
 *
 * `dev-outsider` exists only for this suite - it is added on top via environment variables in
 * e2e/docker-compose.e2e.yml (see the comment there), not part of application.yml's defaults.
 * Scenarios that need an account with no relationship whatsoever to a piece of test data (the
 * negative case in test(e2e) #424: a share must not leak to someone it was never extended to) use
 * this instead of `dev-user`, so that account's own grants (or lack of them) from other scenarios
 * never accidentally decide the outcome.
 */
const ADMIN_USER = 'dev-admin'
const REGULAR_USER = 'dev-user'
const OUTSIDER_USER = 'dev-outsider'

/**
 * Opens the app as the given dev user and returns once the app shell has rendered. This is the
 * single reusable building block every scenario should use to reach the app authenticated,
 * instead of re-implementing it per spec.
 */
async function openAs(page: Page, devUser: string): Promise<void> {
  await page.goto(`/?devUser=${encodeURIComponent(devUser)}`)
  await expect(page).not.toHaveURL(/\/login(?:$|[/?#])/, { timeout: 15_000 })
}

export const test = base.extend<{
  authenticatedPage: Page
  regularUserPage: Page
  outsiderPage: Page
}>({
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

  /**
   * The app as a second, unrelated non-privileged user with no grants of its own from any other
   * fixture or scenario — see the module doc comment above for why this needs to be a distinct
   * account rather than reusing `regularUserPage`.
   */
  outsiderPage: async ({ browser }, use) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    await openAs(page, OUTSIDER_USER)
    await use(page)
    await context.close()
  },
})

export { expect }
