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

// display_name values from e2e/docker-compose.e2e.yml's OPAA_AUTH_DEV_USERS_* - what
// GET /api/v1/auth/me actually returns for each subject, used below to prove which identity a
// devUser navigation really landed on.
const DEV_USER_DISPLAY_NAMES: Record<string, string> = {
  [ADMIN_USER]: 'Dev Admin',
  [REGULAR_USER]: 'Dev User',
  [OUTSIDER_USER]: 'Dev Outsider',
}

/**
 * Opens the app as the given dev user and returns once the app shell has rendered - and, unlike a
 * plain navigation, only once GET /api/v1/auth/me has actually confirmed the expected identity.
 *
 * This exists because of a CI finding (PR #554 follow-up, reported against an unrelated PR's run):
 * `outsiderPage` was observed to end up authenticated as "Dev Admin" instead of "Dev Outsider" -
 * `io.opaa.auth.DevAuthFilter` falls back to the configured *default* dev user whenever the
 * `X-OPAA-Dev-User` header is absent, so a request that goes out before the frontend's dev-auth
 * bootstrap (authStore#initialize -> devAuth#resolveDevUser, see frontend/src/services/devAuth.ts)
 * has captured `?devUser=` from the URL and written it to sessionStorage silently authenticates as
 * that default instead of failing loudly. A static read of the current frontend code did not turn
 * up a concrete path for that (ProtectedRoute keeps the app shell - and with it the index route's
 * `<Navigate to="/chat" replace/>`, which would otherwise strip the query string - unrendered
 * until authStore's `isLoading` flips false, i.e. until well after resolveDevUser() has already
 * run); this hardening is a safety net regardless of whether that gap gets tracked down, not a fix
 * for it.
 *
 * A single retry (fresh navigation, not just a reload - a plain reload could hit the same race
 * again with the query string already gone from the address bar) covers a one-off race; a second,
 * still-wrong identity throws instead of silently letting a scenario run under the wrong account.
 */
async function openAs(page: Page, devUser: string, attempt = 1): Promise<void> {
  const expectedDisplayName = DEV_USER_DISPLAY_NAMES[devUser]
  const [meResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().endsWith('/api/v1/auth/me'),
    ),
    page.goto(`/?devUser=${encodeURIComponent(devUser)}`),
  ])
  await expect(page).not.toHaveURL(/\/login(?:$|[/?#])/, { timeout: 15_000 })

  const me = (await meResponse.json()) as { displayName: string | null }
  if (me.displayName === expectedDisplayName) return

  if (attempt >= 2) {
    throw new Error(
      `Dev-Auth-Identität stimmt nicht: erwartet "${expectedDisplayName}" (?devUser=${devUser}), ` +
        `aber GET /api/v1/auth/me lieferte "${me.displayName}". Vermutlich lief die Anfrage vor ` +
        'der devUser-Übernahme aus der URL (siehe openAs-Dokumentation in fixtures/auth.ts).',
    )
  }
  await openAs(page, devUser, attempt + 1)
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
