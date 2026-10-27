import { expect, test, type Page } from '@playwright/test'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { expectStepEdgesFlush } from '../fixtures/installation'

/**
 * The set-up assistant against a stack in installation mode (ADR-0060), the target "installation"
 * of scripts/run-e2e.mjs: operating mode oidc without answer file, the token preset through
 * OPAA_INSTALLATION_TOKEN and passed here as E2E_INSTALLATION_TOKEN, ai-stub:8089 on
 * OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST. Every scenario of this file needs a fresh database, as
 * completing ends the installation mode for good.
 */
const INSTALLATION_TOKEN = process.env.E2E_INSTALLATION_TOKEN ?? ''
const CHAT_MODEL_URL = 'http://ai-stub:8089'
const ADMIN_PASSWORD = 'E2e-Installation-Passwort-2026'

if (INSTALLATION_TOKEN === '') {
  throw new Error('E2E_INSTALLATION_TOKEN is not set: run the target "installation" of run-e2e.mjs')
}

async function enterToken(page: Page, token: string) {
  await page.getByLabel('Token', { exact: true }).fill(token)
  await page.getByRole('button', { name: 'Token prüfen' }).click()
}

async function submitStep(page: Page) {
  await page.getByRole('button', { name: 'Prüfen und weiter' }).click()
}

function stepHeading(page: Page, name: string) {
  return page.getByRole('heading', { level: 2, name })
}

test.describe('Einrichtungs-Assistent', () => {
  test('führt vom Token bis zum Abschluss und setzt nach Neuladen fort', async ({ page }) => {
    // Any address leads to the assistant while the installation is pending.
    await page.goto('/chat')
    await expect(page).toHaveURL(/\/installation$/)
    await expect(stepHeading(page, 'Installations-Token')).toBeVisible()
    await expectNoSeriousA11yViolations(page, 'Einrichtung: Token')
    await expectStepEdgesFlush(page)

    await enterToken(page, 'falsches-token-falsches-token-00')
    await expect(page.getByLabel('Token', { exact: true })).toHaveAttribute('aria-invalid', 'true')
    await expectStepEdgesFlush(page)

    await enterToken(page, INSTALLATION_TOKEN)
    await expect(stepHeading(page, 'Erstes Konto')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Pflichtschritte' })).toBeVisible()
    await expectStepEdgesFlush(page)
    await page.getByLabel('E-Mail-Adresse').fill('it-betrieb@stadt.example')
    await page.getByLabel('Anzeigename').fill('Systemverwaltung')
    await page.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
    await page.getByLabel('Passwort wiederholen', { exact: true }).fill(ADMIN_PASSWORD)
    await submitStep(page)

    await expect(stepHeading(page, 'Anmeldeverfahren')).toBeVisible()
    await expectNoSeriousA11yViolations(page, 'Einrichtung: Anmeldeverfahren')
    await expectStepEdgesFlush(page)
    // The provider's fields share the edge too; the scenario then continues with local accounts.
    await page.getByRole('radio', { name: /OIDC-Anbieter/ }).check()
    await expect(page.getByLabel('Issuer-URI')).toBeVisible()
    await expectStepEdgesFlush(page)
    await page.getByRole('radio', { name: /Nur lokale Konten/ }).check()

    // Reload: the token is asked again, the stored state brings the assistant back here.
    await page.reload()
    await enterToken(page, INSTALLATION_TOKEN)
    await expect(stepHeading(page, 'Anmeldeverfahren')).toBeVisible()
    await submitStep(page)

    await expect(stepHeading(page, 'Chat-Modell')).toBeVisible()
    await expectStepEdgesFlush(page)
    await page.getByRole('radio', { name: /Eigene Adresse/ }).click()
    await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
    await page.getByLabel('Basis-Adresse').press('Tab')
    // The stub lists no models: the backend's discovery says so and the identifier is typed.
    await expect(page.getByText(/bietet keine Modellliste an/)).toBeVisible()
    await expectNoSeriousA11yViolations(page, 'Einrichtung: Chat-Modell ohne Modellliste')
    await expectStepEdgesFlush(page)
    await page.getByLabel('Modell-Kennung').fill('ai-stub')
    await submitStep(page)
    await expect(stepHeading(page, 'Chat-Modell')).toBeHidden()

    // The probe embedding against the stub decides the vector size the completion creates.
    await expect(stepHeading(page, 'Embedding-Modell')).toBeVisible()
    await expectStepEdgesFlush(page)
    await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
    await page.getByLabel('Modell-Kennung').fill('ai-stub-embedding')
    await submitStep(page)
    await expect(stepHeading(page, 'Embedding-Modell')).toBeHidden()

    // The remaining steps are those the backend lists; optional ones are skipped.
    const skip = page.getByRole('button', { name: 'Überspringen' })
    while (!(await stepHeading(page, 'Abschluss').isVisible())) {
      await expectStepEdgesFlush(page)
      await skip.click()
    }
    await expectNoSeriousA11yViolations(page, 'Einrichtung: Abschluss')
    await expectStepEdgesFlush(page)
    // At 320 px the forward action wraps below the back button and keeps the right edge.
    const viewport = page.viewportSize()
    await page.setViewportSize({ width: 320, height: 800 })
    await expectStepEdgesFlush(page)
    if (viewport) await page.setViewportSize(viewport)
    await page.getByRole('button', { name: 'Installation abschließen' }).click()
    await expect(stepHeading(page, 'Installation abgeschlossen')).toBeVisible()

    await page.getByRole('link', { name: 'Zur Anmeldung' }).click()
    await expect(page).toHaveURL(/\/login/)
  })
})
