import { expect, test, type Page } from '@playwright/test'
import { askQuestion, expectAnyCitedSource, startFreshChat } from '../../fixtures/chat'

/**
 * The one smoke test against the "demo" Compose profile (Issue #232, Epic #708). Not part of the
 * regular suite under e2e/tests/ - see e2e/demo-smoke/playwright.config.ts and
 * scripts/run-e2e.mjs's own module doc for why, and demo/README.md/docs/features/demo-instance.md
 * for the "demo" profile and its seed itself.
 *
 * By design there is exactly one scenario here, per the issue's own acceptance criteria: this
 * proves the chain Compose -> Seed -> Keycloak login -> connectors -> search works end to end,
 * nothing about the Rheinfurt corpus's actual content - assertions on demo content belong to
 * docs/market/demo-drehbuch.md's manually verified drehbuch, not to a test that runs in CI against a
 * corpus that is explicitly allowed to keep evolving (docs/features/demo-instance.md, "Grund ist
 * Kopplung").
 */

const DEMO_USERNAME = 'maria.weber'
// Documented demo value, not a secret (demo/README.md, "Nutzerkonten";
// demo/seed/profiles.py's DEMO_PASSWORD) - shared by every account in the realm.
const DEMO_PASSWORD = 'RheinfurtDemo!2026'

/**
 * Logs Maria Weber in through the real Keycloak authorization-code flow, the one thing this
 * suite's regular "dev" auth mode never exercises (e2e/README.md, "Warum der dev-Auth-Modus?") -
 * that gap is exactly what this test closes.
 */
async function loginViaKeycloak(page: Page): Promise<void> {
  await page.goto('/')
  await page
    .getByRole('button', { name: 'Anmelden über den Verzeichnisdienst' })
    .click()
  // Keycloak's own hosted login page, a different origin from the frontend - the ids below
  // ("username"/"password"/"kc-login") are Keycloak's default theme, stable across locales and
  // Keycloak versions (see keycloak/realm-export.json for the realm this points at).
  await page.waitForURL(/\/realms\/opaa\/protocol\/openid-connect\/auth/, { timeout: 30_000 })
  await page.locator('#username').fill(DEMO_USERNAME)
  await page.locator('#password').fill(DEMO_PASSWORD)
  await page.locator('#kc-login').click()
  // Back on the frontend's own origin, past /auth/callback (AuthCallbackPage), landed on the
  // chat page ProtectedRoute redirects an authenticated session to.
  await page.waitForURL(/\/chat/, { timeout: 30_000 })
}

test.describe('Demo-Smoke (#232)', () => {
  test('Demo-Nutzerin meldet sich über Keycloak an und erhält eine belegte Antwort', async ({
    page,
  }) => {
    await loginViaKeycloak(page)

    // #230: the demo/source notice in the footer (frontend/src/layouts/DemoNotice.tsx), shown
    // only when the frontend container's OPAA_DEMO_MODE flag is on (e2e/demo-smoke.env) - a real
    // demo deployment always sets it, so this run's own stack must match that, not just the
    // belegte-Antwort scenario below.
    await expect(
      page.getByText(
        'Demo-Instanz mit synthetischen Inhalten der fiktiven Stadt Rheinfurt',
        { exact: false },
      ),
    ).toBeVisible()

    await startFreshChat(page)
    // "Gebührenfrage" from docs/market/demo-drehbuch.md, question 1 (#713) - guaranteed to have an
    // answer in the corpus (demo/generator, #711) and readable by every fach account, Maria included
    // (docs/features/demo-instance.md, "Nutzer, Spaces und Berechtigungen"). The concrete wording
    // is symbolic with ai-stub, though: every input gets the same embedding vector (see
    // e2e/README.md, "KI-Stub statt echtem Modell"), so which chunks reach this answer is decided
    // by the permission filter, never by relevance to this specific question - this test asserts
    // no coupling to the corpus's actual content, only that the chain up to a cited answer works.
    await askQuestion(page, 'Was kostet ein Personalausweis für eine 22-Jährige?')

    // Per the issue's own acceptance criteria: behaviour and presence of a citation, never the
    // LLM's exact wording and never a document count that would drift with the next corpus run.
    await expectAnyCitedSource(page)
  })
})
