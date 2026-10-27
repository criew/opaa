import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { expectStepEdgesFlush } from '../fixtures/installation'

/**
 * The set-up assistant against a stack in installation mode (ADR-0060), the target "installation"
 * of scripts/run-e2e.mjs: operating mode oidc without answer file, the token preset through
 * OPAA_INSTALLATION_TOKEN and passed here as E2E_INSTALLATION_TOKEN, ai-stub:8089 on
 * OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST. Every scenario of this file needs a fresh database, as
 * completing ends the installation mode for good. The scenario skips both optional model steps,
 * signs in with the first account, meets the notices of the chat and sets the first chat and
 * embedding model in the model administration (ADR-0061, Entscheidung 8).
 */
const INSTALLATION_TOKEN = process.env.E2E_INSTALLATION_TOKEN ?? ''
const CHAT_MODEL_URL = 'http://ai-stub:8089'
const ADMIN_EMAIL = 'it-betrieb@stadt.example'
const ADMIN_PASSWORD = 'E2e-Installation-Passwort-2026'
const FIRST_CHAT_MODEL = 'Chat-Modell nach der Installation'

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

/** The danger role per scheme (frontend/src/shared/theme/tokens.ts) as computed by the browser. */
const DANGER = { light: 'rgb(206, 44, 49)', dark: 'rgb(255, 99, 105)' } as const

/**
 * A field in error keeps the danger outline while it holds the focus, in both schemes: focus
 * widens the outline to 2px instead of repainting it in the accent. Hover keeps it too.
 */
async function expectFocusedErrorOutline(field: Locator) {
  await expect(field).toBeFocused()
  await expect(field).toHaveAttribute('aria-invalid', 'true')
  const outline = field
    .locator('xpath=ancestor::*[contains(@class,"MuiOutlinedInput-root")][1]')
    .locator('.MuiOutlinedInput-notchedOutline')
  for (const scheme of ['light', 'dark'] as const) {
    await field.page().emulateMedia({ colorScheme: scheme })
    await expect(outline).toHaveCSS('border-top-color', DANGER[scheme])
    await expect(outline).toHaveCSS('border-top-width', '2px')
  }
  await field.page().emulateMedia({ colorScheme: 'light' })
  // Without focus, hovering keeps the danger outline as well.
  await field.blur()
  await field.hover()
  await expect(field).not.toBeFocused()
  await expect(outline).toHaveCSS('border-top-color', DANGER.light)
  await field.page().emulateMedia({ colorScheme: null })
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
    await expectFocusedErrorOutline(page.getByLabel('Token', { exact: true }))
    await expectStepEdgesFlush(page)

    await enterToken(page, INSTALLATION_TOKEN)
    await expect(stepHeading(page, 'Erstes Konto')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Pflichtschritte' })).toBeVisible()
    await expectStepEdgesFlush(page)
    await page.getByLabel('E-Mail-Adresse').fill(ADMIN_EMAIL)
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
    // Both model steps are optional: an installation whose model endpoint is not released yet
    // skips them, and the entered address is discarded unsaved.
    await expect(page.getByText('Optional · später unter Modelle')).toBeVisible()
    const skip = page.getByRole('button', { name: 'Überspringen' })
    await skip.click()
    await expect(stepHeading(page, 'Embedding-Modell')).toBeVisible()
    await expectStepEdgesFlush(page)
    await skip.click()

    // The remaining steps are those the backend lists; optional ones are skipped.
    while (!(await stepHeading(page, 'Abschluss').isVisible())) {
      await expectStepEdgesFlush(page)
      await skip.click()
    }
    const summary = page.getByRole('list', { name: 'Stand der Schritte' })
    await expect(summary.getByText('Wird übersprungen')).not.toHaveCount(0)
    await expect(page.getByText(/Übersprungen: .*Chat-Modell, Embedding-Modell/)).toBeVisible()
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

    // "Nur lokale Konten" leaves the local accounts switched on: the regular sign-in page carries
    // the password form, and the first account signs in there.
    const signIn = page.getByRole('form', { name: 'Konto dieser Installation' })
    await expect(signIn).toBeVisible()
    await signIn.getByLabel('E-Mail-Adresse').fill(ADMIN_EMAIL)
    await signIn.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
    await signIn.getByRole('button', { name: 'Anmelden' }).click()
    await expect(page).toHaveURL(/\/chats\/new/, { timeout: 20_000 })

    // Without models the chat says so, and leads the system administration to them.
    await expect(page.getByText(/Noch kein Chat-Modell eingerichtet/)).toBeVisible()
    await expect(page.getByText(/Noch kein Embedding-Modell eingerichtet/)).toBeVisible()
    await expectNoSeriousA11yViolations(page, 'Chat ohne Modelle')
    await page.getByRole('link', { name: 'Chat-Modell einrichten' }).click()
    await expect(page).toHaveURL(/\/admin\/models\/chat$/)
    await expect(page.getByText(/noch kein Chat-Modell aktiv/)).toBeVisible()

    // The first chat model, created and activated without a restart.
    await page.getByRole('button', { name: 'Neues Modell' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Anzeigename', { exact: false }).fill(FIRST_CHAT_MODEL)
    await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(CHAT_MODEL_URL)
    await dialog.getByLabel('Modell-Kennung', { exact: false }).fill('ai-stub')
    await dialog.getByRole('button', { name: 'Anlegen' }).click()
    await expect(dialog).toBeHidden()
    const row = page.getByRole('row', { name: new RegExp(FIRST_CHAT_MODEL) })
    await row.getByRole('button', { name: `Aktionen für „${FIRST_CHAT_MODEL}“` }).click()
    await page
      .getByRole('menu', { name: `Aktionen für „${FIRST_CHAT_MODEL}“` })
      .getByRole('menuitem', { name: 'Aktiv setzen' })
      .click()
    await expect(row.getByText('Aktiv', { exact: true })).toBeVisible()

    // The first embedding model: probe, then the vector table of its size, no re-embedding.
    await page.goto('/admin/models/embedding')
    await expect(page.getByText(/noch kein Embedding-Modell eingerichtet/)).toBeVisible()
    await page.getByLabel('Basis-Adresse', { exact: false }).fill(CHAT_MODEL_URL)
    await page.getByLabel('Modell-Kennung', { exact: false }).fill('ai-stub-embedding')
    await page.getByRole('button', { name: 'Probeeinbettung' }).click()
    await page.getByRole('button', { name: 'Embedding-Modell festlegen' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Festlegen' }).click()
    await expect(page.getByRole('table', { name: 'Einbettungsmodell' })).toContainText(
      'ai-stub-embedding',
    )

    // Both models set, the chat shows no notice any more once it has read the availability.
    await Promise.all([
      page.waitForResponse((response) => response.url().endsWith('/api/v1/search/availability')),
      page.goto('/chat'),
    ])
    await expect(page.getByRole('heading', { level: 1, name: 'Neuer Chat' })).toBeVisible()
    await expect(page.getByText(/Noch kein (Chat|Embedding)-Modell eingerichtet/)).toHaveCount(0)
  })
})
