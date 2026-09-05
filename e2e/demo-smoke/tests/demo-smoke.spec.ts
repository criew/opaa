import { expect, test, type Page } from '@playwright/test'
import { askQuestion, expectAnyCitedSource, gotoLibraries, startFreshChat } from '../../fixtures/chat'

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
// demo/seed/profiles.py's DEMO_PASSWORD) - shared by every account in both realms.
const DEMO_PASSWORD = 'RheinfurtDemo!2026'
const DEMO_ADMIN_USERNAME = 'demo-admin'

// The second identity provider of the demo stack (keycloak/realm-partner-export.json, ADR-0025):
// the same origin, another realm, another public client - and a maria.weber with the same
// e-mail address as the one in realm "opaa". Added through the Anbieterverwaltung during the
// test, never seeded, so the run proves "a provider added in the UI works without a restart".
const PARTNER_PROVIDER_NAME = 'Partnerportal'
const PARTNER_ISSUER = 'http://localhost:8180/realms/partner'
const PARTNER_CLIENT_ID = 'opaa-partner'
// The backend fetches the keys inside the Compose network - the same split as the seeded
// provider's OPAA_OIDC_JWK_SET_URI (e2e/demo-smoke.env).
const PARTNER_JWK_SET_URI = 'http://keycloak:8180/realms/partner/protocol/openid-connect/certs'

interface KeycloakLogin {
  /** The sign-in page's button, "Anmelden bei <Anzeigename>" (frontend/src/pages/LoginPage.tsx). */
  providerName: string
  realm: string
  username: string
}

/**
 * Logs in through the real Keycloak authorization-code flow of the given provider, the one thing
 * this suite's regular "dev" auth mode never exercises (e2e/README.md, "Warum der dev-Auth-Modus?")
 * - that gap is exactly what this test closes. Resolves with the account the backend answered
 * /api/v1/auth/me with, so a scenario can compare identities.
 */
async function loginViaKeycloak(
  page: Page,
  { providerName, realm, username }: KeycloakLogin,
): Promise<{ id: string; email: string | null; displayName: string | null }> {
  await page.goto('/login')
  await page.getByRole('button', { name: `Anmelden bei ${providerName}` }).click()
  // Keycloak's own hosted login page, a different origin from the frontend - the ids below
  // ("username"/"password"/"kc-login") are Keycloak's default theme, stable across locales and
  // Keycloak versions (see keycloak/realm-export.json for the realms this points at).
  await page.waitForURL(new RegExp(`/realms/${realm}/protocol/openid-connect/auth`), {
    timeout: 30_000,
  })
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(DEMO_PASSWORD)
  const [meResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().endsWith('/api/v1/auth/me') &&
        response.status() === 200,
    ),
    page.locator('#kc-login').click(),
  ])
  // Back on the frontend's own origin, past /auth/callback (AuthCallbackPage), landed on the
  // chat page ProtectedRoute redirects an authenticated session to.
  await page.waitForURL(/\/chat/, { timeout: 30_000 })
  return (await meResponse.json()) as { id: string; email: string | null; displayName: string | null }
}

/** The RP-initiated logout at the provider of the session (ADR-0025, Entscheidung 5). */
async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Profil und Einstellungen' }).click()
  await page.getByRole('menuitem', { name: 'Abmelden' }).click()
  // Keycloak ends its session and sends the browser back to the origin, where the app - without
  // a session now - shows the sign-in page again.
  await page.waitForURL(/\/login(?:$|[/?#])/, { timeout: 30_000 })
}

test.describe('Demo-Smoke (#232)', () => {
  test('Demo-Nutzerin meldet sich über Keycloak an und erhält eine belegte Antwort', async ({
    page,
  }) => {
    await loginViaKeycloak(page, { providerName: 'Verzeichnisdienst', realm: 'opaa', username: DEMO_USERNAME })

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

  /**
   * ADR-0025 / #1334: two providers, no shared account. The administrator adds the partner realm
   * as a second provider through the Anbieterverwaltung - no restart - and the sign-in page then
   * offers both. maria.weber exists in both realms with the same e-mail address; signing in
   * through each yields a different account: the partner account is brand new and sees none of
   * the demo libraries the seeded Maria can read.
   */
  test('Zweiter Anbieter über die Verwaltungsoberfläche: gleiche E-Mail, zwei Konten', async ({
    page,
  }) => {
    test.setTimeout(180_000)
    const admin = await loginViaKeycloak(page, {
      providerName: 'Verzeichnisdienst',
      realm: 'opaa',
      username: DEMO_ADMIN_USERNAME,
    })
    expect(admin.email).toBe('admin@stadt-rheinfurt.example')

    await page.goto('/admin/identity-providers')
    await expect(page.getByRole('heading', { name: 'Identitätsanbieter' })).toBeVisible()
    // the seeded provider from e2e/demo-smoke.env's OPAA_OIDC_* bootstrap values (ADR-0025,
    // Entscheidung 3) is listed as the default
    await expect(page.getByRole('article', { name: 'Verzeichnisdienst' })).toBeVisible()

    const partnerCard = page.getByRole('article', { name: PARTNER_PROVIDER_NAME })
    // Repeat-safe: a Playwright retry runs against the same, still-running stack - the provider
    // a previous attempt created is still there, and the issuer is unique per provider.
    if ((await partnerCard.count()) === 0) {
      await page.getByRole('button', { name: 'Neuer Anbieter' }).click()
      const dialog = page.getByRole('dialog')
      // anchored: "Anzeigename-Claim" is a second textbox of the same dialog
      await dialog.getByRole('textbox', { name: /^Anzeigename\s*\*?$/ }).fill(PARTNER_PROVIDER_NAME)
      await dialog.getByRole('textbox', { name: /^Issuer-URI/ }).fill(PARTNER_ISSUER)
      await dialog.getByRole('textbox', { name: /^Client-ID/ }).fill(PARTNER_CLIENT_ID)
      await dialog.getByRole('textbox', { name: /^JWK-Set-Adresse/ }).fill(PARTNER_JWK_SET_URI)
      await dialog.getByRole('button', { name: 'Verbindung testen' }).click()
      await expect(dialog.getByText(/Anbieter erreichbar/)).toBeVisible({ timeout: 30_000 })
      await dialog.getByRole('button', { name: 'Anlegen' }).click()
      await expect(page.getByRole('dialog')).toBeHidden()
    }
    await expect(partnerCard).toBeVisible()
    // its decoder was built after the commit, without a restart - exact: "Nicht erreichbar"
    // contains the same word
    await expect(partnerCard.getByText('Erreichbar', { exact: true })).toBeVisible({
      timeout: 30_000,
    })
    await expect(partnerCard.getByText('Nicht erreichbar', { exact: true })).toHaveCount(0)

    await logout(page)
    await expect(page.getByRole('button', { name: 'Anmelden bei Verzeichnisdienst' })).toBeVisible()
    await expect(page.getByRole('button', { name: `Anmelden bei ${PARTNER_PROVIDER_NAME}` })).toBeVisible()

    const mariaAtPartner = await loginViaKeycloak(page, {
      providerName: PARTNER_PROVIDER_NAME,
      realm: 'partner',
      username: DEMO_USERNAME,
    })
    expect(mariaAtPartner.email).toBe('maria.weber@stadt-rheinfurt.example')
    // a fresh account: none of the seeded demo libraries is readable for it - wait for the
    // rendered empty state first, an absence check alone would pass before the list rendered
    await gotoLibraries(page)
    await expect(page.getByText('Es sind noch keine Bibliotheken vorhanden.')).toBeVisible()
    await expect(page.getByText('Leistungen Meldewesen & Ausweise', { exact: true })).toHaveCount(0)
    await logout(page)

    const mariaAtVerzeichnisdienst = await loginViaKeycloak(page, {
      providerName: 'Verzeichnisdienst',
      realm: 'opaa',
      username: DEMO_USERNAME,
    })
    expect(mariaAtVerzeichnisdienst.email).toBe('maria.weber@stadt-rheinfurt.example')
    expect(mariaAtVerzeichnisdienst.id).not.toBe(mariaAtPartner.id)
    await gotoLibraries(page)
    await expect(page.getByText('Leistungen Meldewesen & Ausweise', { exact: true })).toBeVisible()
  })
})
