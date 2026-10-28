import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { expectStepEdgesFlush } from '../fixtures/installation'
import { clearMailbox, mailCount, waitForMail } from '../fixtures/mailpit'

/**
 * The set-up assistant against a stack in installation mode (ADR-0060), the target "installation"
 * of scripts/run-e2e.mjs: operating mode oidc without answer file, the token preset through
 * OPAA_INSTALLATION_TOKEN and passed here as E2E_INSTALLATION_TOKEN, ai-stub:8089 on
 * OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST, mailpit:1025 on OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST.
 * Every scenario of this file needs a fresh database, as completing ends the installation mode for
 * good: the runner starts one stack per tag (@mail-skipped, @mail-filled). The @mail-skipped scenario
 * also skips both optional model steps, signs in with the first account, meets the notices of the
 * chat and sets the first chat and embedding model in the model administration (ADR-0061,
 * Entscheidung 8); the @mail-filled one checks both models in the assistant.
 */
const INSTALLATION_TOKEN = process.env.E2E_INSTALLATION_TOKEN ?? ''
const CHAT_MODEL_URL = 'http://ai-stub:8089'
const ADMIN_EMAIL = 'it-betrieb@stadt.example'
const ADMIN_PASSWORD = 'E2e-Installation-Passwort-2026'
const TEST_RECIPIENT = 'it-betrieb@stadt.example'
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

/**
 * Skips the open optional step and waits until the assistant shows the next one: skipping goes
 * through the API, so the view changes only after its answer.
 */
async function skipStep(page: Page) {
  const progress = page.getByText(/^Schritt \d+ von \d+$/)
  const before = (await progress.textContent()) ?? ''
  await page.getByRole('button', { name: 'Überspringen' }).click()
  await expect(progress).not.toHaveText(before)
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

/** The edge rule at the width of the page and at 320 px, where the actions wrap. */
async function expectStepEdgesFlushAlsoNarrow(page: Page) {
  await expectStepEdgesFlush(page)
  const viewport = page.viewportSize()
  await page.setViewportSize({ width: 320, height: 800 })
  await expectStepEdgesFlush(page)
  if (viewport) await page.setViewportSize(viewport)
}

/** Both mandatory steps with local accounts only and both model steps checked, up to the mail step. */
async function completeStepsUpToMail(page: Page) {
  await page.goto('/installation')
  await enterToken(page, INSTALLATION_TOKEN)
  await expect(stepHeading(page, 'Erstes Konto')).toBeVisible()
  await page.getByLabel('E-Mail-Adresse').fill('it-betrieb@stadt.example')
  await page.getByLabel('Anzeigename').fill('Systemverwaltung')
  await page.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
  await page.getByLabel('Passwort wiederholen', { exact: true }).fill(ADMIN_PASSWORD)
  await submitStep(page)
  await expect(stepHeading(page, 'Anmeldeverfahren')).toBeVisible()
  await submitStep(page)
  await expect(stepHeading(page, 'Chat-Modell')).toBeVisible()
  await page.getByRole('radio', { name: /Eigene Adresse/ }).click()
  await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
  await page.getByLabel('Modell-Kennung').fill('ai-stub')
  await submitStep(page)
  await expect(stepHeading(page, 'Embedding-Modell')).toBeVisible()
  await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
  await page.getByLabel('Modell-Kennung').fill('ai-stub-embedding')
  await submitStep(page)
}

test.describe('Einrichtungs-Assistent', () => {
  test(
    'führt vom Token bis zum Abschluss, setzt nach Neuladen fort, überspringt Modelle und E-Mail-Versand und legt die Modelle danach fest',
    {
      tag: '@mail-skipped',
    },
    async ({ page }) => {
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
      await skipStep(page)
      await expect(stepHeading(page, 'Embedding-Modell')).toBeVisible()
      await expectStepEdgesFlush(page)
      await skipStep(page)

      // The optional mail step says so and is skipped; sending stays off.
      await expect(stepHeading(page, 'E-Mail-Versand')).toBeVisible()
      await expect(page.getByText('Optional · später unter E-Mail')).toBeVisible()
      await expect(page.getByRole('list', { name: 'Optionale Schritte' })).toContainText(
        'Nicht eingerichtet',
      )
      await expectNoSeriousA11yViolations(page, 'Einrichtung: E-Mail-Versand')
      await expectStepEdgesFlushAlsoNarrow(page)
      await page.getByRole('button', { name: 'Überspringen' }).click()

      // The skip goes through the API; the summary follows once it is recorded.
      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      await expect(page.getByText(/Der E-Mail-Versand bleibt aus/)).toBeVisible()
      const summary = page.getByRole('list', { name: 'Stand der Schritte' })
      await expect(summary.getByText('Nicht eingerichtet')).toHaveCount(3)
      await expect(page.getByText(/Übersprungen: Chat-Modell, Embedding-Modell\./)).toBeVisible()
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Abschluss')
      // At 320 px the forward action wraps below the back button and keeps the right edge.
      await expectStepEdgesFlushAlsoNarrow(page)
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
    },
  )

  test(
    'richtet den E-Mail-Versand mit Prüfung und Testnachricht ein',
    {
      tag: '@mail-filled',
    },
    async ({ page }) => {
      await clearMailbox()
      await completeStepsUpToMail(page)

      await expect(stepHeading(page, 'E-Mail-Versand')).toBeVisible()
      await expectStepEdgesFlushAlsoNarrow(page)
      // Without server, port and sender the step names each field, as the administration does.
      await submitStep(page)
      await expect(page.getByLabel('Server', { exact: true })).toHaveAccessibleDescription(
        'Ohne SMTP-Server kann der Versand nicht eingeschaltet werden',
      )
      await expectStepEdgesFlushAlsoNarrow(page)

      // mailpit of this stack, reached by the backend over the Compose network.
      await page.getByLabel('Server', { exact: true }).fill('mailpit')
      await page.getByLabel('Port', { exact: true }).fill('1025')
      await page.getByRole('radio', { name: /Keine/ }).check()
      await page.getByLabel('Absenderadresse', { exact: true }).fill('opaa@stadt.example')
      await page.getByLabel('Absendername (optional)').fill('OPAA')
      await expectNoSeriousA11yViolations(page, 'Einrichtung: E-Mail-Versand ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)

      // The check alone sends nothing.
      await submitStep(page)
      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      expect(await mailCount()).toBe(0)

      // Checked again with an address, the test message goes there and nowhere else.
      await page
        .getByRole('navigation', { name: 'Schritte der Einrichtung' })
        .getByRole('button', { name: /E-Mail-Versand/ })
        .click()
      await expect(stepHeading(page, 'E-Mail-Versand')).toBeVisible()
      await page.getByLabel('Testnachricht an (optional)').fill(TEST_RECIPIENT)
      await submitStep(page)
      const mail = await waitForMail(TEST_RECIPIENT)
      expect(mail.recipients).toEqual([TEST_RECIPIENT])
      expect(await mailCount()).toBe(1)

      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      await expect(
        page.getByText(/E-Mail-Versand wird mit dem geprüften Mailserver eingeschaltet/),
      ).toBeVisible()
      await page.getByRole('button', { name: 'Installation abschließen' }).click()
      await expect(stepHeading(page, 'Installation abgeschlossen')).toBeVisible()
    },
  )
})
