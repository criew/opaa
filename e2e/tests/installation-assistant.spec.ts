import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { expectStepEdgesFlush } from '../fixtures/installation'
import { clearMailbox, mailCount, waitForMail } from '../fixtures/mailpit'

/**
 * The set-up assistant against a stack in installation mode (ADR-0060), the target "installation"
 * of scripts/run-e2e.mjs: operating mode oidc without answer file, the token preset through
 * OPAA_INSTALLATION_TOKEN and passed here as E2E_INSTALLATION_TOKEN, ai-stub:8089 on
 * OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST, mailpit:1025 on OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST,
 * oidc-stub as identity provider, ocr-stub as text recognition service and clamd-stub as virus
 * scanner (e2e/docker-compose.installation.yml). Every scenario of this file needs a fresh
 * database, as completing ends the installation mode for good: the runner starts one stack per tag
 * (@mail-skipped, @mail-filled). The @mail-skipped scenario keeps local accounts only, skips every
 * optional step, signs in with the first account under the OPAA standard, meets the notices of the
 * chat and sets the first chat and embedding model in the model administration (ADR-0061,
 * Entscheidung 8); the @mail-filled one adds oidc-stub as provider, fills and checks every optional
 * step and finds each switched on after the completion: the provider on the sign-in page, rerank,
 * transcription and text recognition in the model administration, the scanner under Virenprüfung,
 * product name and accent colour on the sign-in page and in the interface.
 * .github/scripts/check_installation_e2e_coverage.sh fails while a step is missing here.
 */
const INSTALLATION_TOKEN = process.env.E2E_INSTALLATION_TOKEN ?? ''
const CHAT_MODEL_URL = 'http://ai-stub:8089'
/** ai-stub's rerank endpoint; with the transcription prefix the only addresses that list a model. */
const RERANK_URL = 'http://ai-stub:8089/reranker'
const RERANK_MODEL = 'stub-reranker'
/** The stub lists its speech model only below this path. */
const TRANSCRIPTION_URL = 'http://ai-stub:8089/transcription'
const TRANSCRIPTION_MODEL = 'ai-stub-whisper'
/** ocr-stub under its service name: the backend checks it, never the browser. */
const TEXT_RECOGNITION_URL = 'http://ocr-stub:5001'
const OCR_API_KEY = process.env.E2E_OCR_API_KEY ?? ''
/** clamd-stub under its service name, on the clamd port the assistant offers. */
const CLAMD_HOST = 'clamd-stub'
/**
 * oidc-stub: the browser reads it under the issuer, the backend only the JWK set under the service
 * name (e2e/docker-compose.installation.yml).
 */
const OIDC_ISSUER = 'http://localhost:18095'
const OIDC_JWK_SET = 'http://oidc-stub:8095/jwks'
const OIDC_PROVIDER_NAME = 'Landes-Login'
const ADMIN_EMAIL = 'it-betrieb@stadt.example'
const ADMIN_PASSWORD = 'E2e-Installation-Passwort-2026'
const TEST_RECIPIENT = 'it-betrieb@stadt.example'
const FIRST_CHAT_MODEL = 'Chat-Modell nach der Installation'
const OPAA_PRODUCT_NAME = 'OPAA'
const PRODUCT_NAME = 'Landesamt-Assistent'
/** White text reaches 4.5:1 on it, so filled actions carry the colour unchanged. */
const ACCENT = '#7A3E9D'
const ACCENT_RGB = 'rgb(122, 62, 157)'

if (INSTALLATION_TOKEN === '' || OCR_API_KEY === '') {
  throw new Error(
    'E2E_INSTALLATION_TOKEN or E2E_OCR_API_KEY is not set: run the target "installation" of run-e2e.mjs',
  )
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

/** The list of the steps and their states on the summary. */
function stepStates(page: Page) {
  return page.getByRole('list', { name: 'Stand der Schritte' })
}

/** The row of one step on the summary: its name as a link to the step, and its state. */
function stepState(page: Page, name: string) {
  return stepStates(page)
    .getByRole('listitem')
    .filter({ has: page.getByRole('button', { name, exact: true }) })
}

/** The footer of a step: „Zurück“, the quiet „Überspringen“ and the primary action, on the right. */
async function expectFooterOrder(page: Page) {
  const boxes = []
  for (const name of ['Zurück', 'Überspringen', 'Prüfen und weiter']) {
    const box = await page
      .locator('main form')
      .getByRole('button', { name, exact: true })
      .boundingBox()
    expect(box, name).not.toBeNull()
    boxes.push(box!)
  }
  expect(boxes[0].x).toBeLessThan(boxes[1].x)
  expect(boxes[1].x).toBeLessThan(boxes[2].x)
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

/**
 * Both mandatory steps, local accounts and oidc-stub as provider, and chat and embedding model
 * checked, up to the rerank step.
 */
async function completeStepsUpToRerank(page: Page) {
  await page.goto('/installation')
  await enterToken(page, INSTALLATION_TOKEN)
  await expect(stepHeading(page, 'Erstes Konto')).toBeVisible()
  await page.getByLabel('E-Mail-Adresse').fill('it-betrieb@stadt.example')
  await page.getByLabel('Anzeigename').fill('Systemverwaltung')
  await page.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
  await page.getByLabel('Passwort wiederholen', { exact: true }).fill(ADMIN_PASSWORD)
  await submitStep(page)
  await expect(stepHeading(page, 'Identitätsanbieter')).toBeVisible()
  // The provider is checked by its JWK set; the backend cannot reach the issuer's loopback address
  // and says so instead of failing.
  await page.getByRole('radio', { name: /Lokale Konten und ein Identitätsanbieter/ }).check()
  await page.getByLabel('Issuer-URI').fill(OIDC_ISSUER)
  await page.getByLabel('Client-ID').fill('opaa')
  await page.getByLabel('JWK-Set-Adresse').fill(OIDC_JWK_SET)
  await page.getByLabel('Name auf der Anmeldeseite').fill(OIDC_PROVIDER_NAME)
  await expectNoSeriousA11yViolations(page, 'Einrichtung: Identitätsanbieter mit Anbieter')
  await submitStep(page)
  await expect(stepHeading(page, 'Chat-Modell')).toBeVisible()
  await page.getByRole('radio', { name: /Eigene Adresse/ }).click()
  await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
  await page.getByLabel('Modell-Kennung').fill('ai-stub')
  await submitStep(page)
  await expect(stepHeading(page, 'Einbettung')).toBeVisible()
  await page.getByLabel('Basis-Adresse').fill(CHAT_MODEL_URL)
  await page.getByLabel('Modell-Kennung').fill('ai-stub-embedding')
  await submitStep(page)
  await expect(stepHeading(page, 'Reranking')).toBeVisible()
}

test.describe('Einrichtungs-Assistent', () => {
  test(
    'führt vom Token bis zum Abschluss, setzt nach Neuladen fort, überspringt Modelle, Virenprüfung, E-Mail und Branding und legt die Modelle danach fest',
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

      await expect(stepHeading(page, 'Identitätsanbieter')).toBeVisible()
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Identitätsanbieter')
      await expectStepEdgesFlush(page)
      // The provider's fields share the edge too; the scenario then continues with local accounts.
      await page.getByRole('radio', { name: /Lokale Konten und ein Identitätsanbieter/ }).check()
      await expect(page.getByLabel('Issuer-URI')).toBeVisible()
      await expectStepEdgesFlush(page)
      await page.getByRole('radio', { name: /Nur lokale Konten/ }).check()

      // Reload: the token is asked again, the stored state brings the assistant back here.
      await page.reload()
      await enterToken(page, INSTALLATION_TOKEN)
      await expect(stepHeading(page, 'Identitätsanbieter')).toBeVisible()
      await submitStep(page)

      await expect(stepHeading(page, 'Chat-Modell')).toBeVisible()
      await expectStepEdgesFlush(page)
      await expectFooterOrder(page)
      // „Modelle abrufen“ is never locked: without an address it leads to the field.
      await page.getByRole('button', { name: 'Modelle abrufen' }).click()
      await expect(page.getByLabel('Basis-Adresse')).toHaveAttribute('aria-invalid', 'true')
      await expect(page.getByLabel('Basis-Adresse')).toBeFocused()
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
      await expect(stepHeading(page, 'Einbettung')).toBeVisible()
      await expectStepEdgesFlush(page)
      await skipStep(page)

      // The rerank step offers no provider and starts at the address; skipped, reranking stays off.
      await expect(stepHeading(page, 'Reranking')).toBeVisible()
      await expect(page.getByText('Optional · später unter Modelle')).toBeVisible()
      await expect(page.getByRole('radiogroup', { name: 'Anbieter' })).toHaveCount(0)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Reranking')
      await expectStepEdgesFlushAlsoNarrow(page)
      await skipStep(page)

      // Speech input is on in this stack, so the transcription model step is offered and skipped;
      // speech input stays off.
      await expect(stepHeading(page, 'Spracheingabe')).toBeVisible()
      await expect(page.getByText('Optional · später unter Modelle')).toBeVisible()
      // Live transcription takes effect with the step's action: a checkbox, no switch.
      await expect(
        page.getByRole('checkbox', { name: 'Live-Transkript einschalten' }),
      ).not.toBeChecked()
      await expect(page.locator('main form').getByRole('switch')).toHaveCount(0)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Spracheingabe')
      await expectStepEdgesFlushAlsoNarrow(page)
      await skipStep(page)

      // The operation releases text recognition in this stack, so its step is offered and
      // skipped; text recognition stays off.
      await expect(stepHeading(page, 'Texterkennung')).toBeVisible()
      await expect(page.getByText('Optional · später unter Modelle')).toBeVisible()
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Texterkennung')
      await expectStepEdgesFlushAlsoNarrow(page)
      await skipStep(page)

      // The malware scan is skipped and stays off. The assistant takes
      // clamd's default port only and says where another one is entered.
      await expect(stepHeading(page, 'Virenprüfung')).toBeVisible()
      await expect(page.getByText('Optional · später unter Virenprüfung')).toBeVisible()
      await expect(page.getByLabel('Host des Virenscanners')).toHaveAccessibleDescription(
        /Port 3310; einen anderen tragen Sie später unter „Administration → Virenprüfung“ ein/,
      )
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Virenprüfung')
      await expectStepEdgesFlushAlsoNarrow(page)
      await skipStep(page)

      // The optional mail step says so and is skipped; sending stays off.
      await expect(stepHeading(page, 'E-Mail')).toBeVisible()
      await expect(page.getByText('Optional · später unter E-Mail')).toBeVisible()
      await expect(page.getByRole('list', { name: 'Optionale Schritte' })).toContainText(
        'Nicht eingerichtet',
      )
      await expectNoSeriousA11yViolations(page, 'Einrichtung: E-Mail')
      await expectStepEdgesFlushAlsoNarrow(page)
      // Below 900 px the step list shows only position and name and opens on a tap.
      const wide = page.viewportSize()
      await page.setViewportSize({ width: 360, height: 800 })
      const stepList = page.getByRole('navigation', { name: 'Schritte der Einrichtung' })
      const position = stepList.getByRole('button', { name: /^Schritt \d+ von \d+ · E-Mail$/ })
      await expect(position).toHaveAttribute('aria-expanded', 'false')
      await expect(stepList.getByRole('list', { name: 'Optionale Schritte' })).toBeHidden()
      await position.click()
      await expect(stepList.getByRole('list', { name: 'Optionale Schritte' })).toBeVisible()
      await position.click()
      if (wide) await page.setViewportSize(wide)
      await skipStep(page)

      // The optional branding step is skipped as well: the OPAA standard stays.
      await expect(stepHeading(page, 'Branding')).toBeVisible()
      await expect(page.getByText('Optional · später unter Allgemein & Branding')).toBeVisible()
      await expect(
        page.getByText(/Logo und Hintergrundbild lädt die Systemverwaltung später/),
      ).toBeVisible()
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Branding')
      await expectStepEdgesFlushAlsoNarrow(page)
      await page.getByRole('button', { name: 'Überspringen' }).click()

      // The skip goes through the API; the summary follows once it is recorded.
      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      // Every optional step stays not set up.
      for (const skipped of [
        'Chat-Modell',
        'Einbettung',
        'Reranking',
        'Spracheingabe',
        'Texterkennung',
        'Virenprüfung',
        'E-Mail',
        'Branding',
      ]) {
        await expect(stepState(page, skipped)).toContainText('Nicht eingerichtet')
      }
      await expect(stepStates(page).getByText('Nicht eingerichtet')).toHaveCount(8)
      // Each row leads to its step, and the step list back to the summary.
      await stepState(page, 'E-Mail').getByRole('button', { name: 'E-Mail', exact: true }).click()
      await expect(stepHeading(page, 'E-Mail')).toBeVisible()
      await page
        .getByRole('navigation', { name: 'Schritte der Einrichtung' })
        .getByRole('button', { name: /Abschluss/ })
        .click()
      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
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
      // The skipped branding leaves the OPAA standard on the sign-in page and in the interface.
      await expect(page.getByText(OPAA_PRODUCT_NAME, { exact: true }).first()).toBeVisible()
      await signIn.getByLabel('E-Mail-Adresse').fill(ADMIN_EMAIL)
      await signIn.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
      await signIn.getByRole('button', { name: 'Anmelden' }).click()
      await expect(page).toHaveURL(/\/chats\/new/, { timeout: 20_000 })
      await expect(page).toHaveTitle(new RegExp(`${OPAA_PRODUCT_NAME}$`))

      // Without models the chat says so, and leads the system administration to them.
      await expect(page.getByText(/Noch kein Chat-Modell eingerichtet/)).toBeVisible()
      await expect(page.getByText(/Noch kein Embedding-Modell eingerichtet/)).toBeVisible()
      await expectNoSeriousA11yViolations(page, 'Chat ohne Modelle')
      await page.getByRole('link', { name: 'Chat-Modell einrichten' }).click()
      await expect(page).toHaveURL(/\/admin\/models\/chat$/)
      await expect(page.getByText(/noch kein Chat-Modell aktiv/)).toBeVisible()

      // The first chat model, created and activated without a restart.
      // The empty state and the toolbar both offer it; the toolbar's is the one of the tab.
      await page
        .getByRole('tabpanel', { name: 'Chat-Modell' })
        .getByRole('button', { name: 'Neues Modell' })
        .click()
      const dialog = page.getByRole('dialog')
      await dialog.getByLabel('Anzeigename', { exact: false }).fill(FIRST_CHAT_MODEL)
      await dialog.getByLabel('Basis-Adresse', { exact: false }).fill(CHAT_MODEL_URL)
      await dialog.getByLabel('Modell-Kennung', { exact: false }).fill('ai-stub')
      await dialog.getByRole('button', { name: 'Anlegen' }).click()
      await expect(dialog).toBeHidden()
      const row = page.getByRole('row', { name: new RegExp(FIRST_CHAT_MODEL) })
      await row.getByRole('button', { name: `Weitere Aktionen für „${FIRST_CHAT_MODEL}“` }).click()
      await page
        .getByRole('menu', { name: `Weitere Aktionen für „${FIRST_CHAT_MODEL}“` })
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
    'richtet Identitätsanbieter, Reranking, Spracheingabe, Texterkennung, Virenprüfung, E-Mail mit Prüfung und Testnachricht und das Branding ein',
    {
      tag: '@mail-filled',
    },
    async ({ page }) => {
      await clearMailbox()
      await completeStepsUpToRerank(page)

      // The rerank model from the list of its endpoint, checked with the probe of the
      // administration; the empty step names its fields first.
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)
      await expect(page.getByLabel('Basis-Adresse')).toHaveAttribute('aria-invalid', 'true')
      await page.getByLabel('Basis-Adresse').fill(RERANK_URL)
      await page.getByLabel('Basis-Adresse').press('Tab')
      await expect(page.getByText('1 Modell gefunden.', { exact: false })).toBeVisible()
      await page.getByRole('combobox', { name: 'Modell-Kennung' }).click()
      await page
        .getByRole('group', { name: 'Für das Reranking geeignet' })
        .getByRole('option', { name: new RegExp(RERANK_MODEL) })
        .click()
      await expect(page.getByRole('combobox', { name: 'Modell-Kennung' })).toHaveValue(RERANK_MODEL)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Reranking ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)

      // The transcription model, chosen from the list the endpoint offers and checked with the
      // probe of the model administration.
      await expect(stepHeading(page, 'Spracheingabe')).toBeVisible()
      await expect(page.getByText('Optional · später unter Modelle')).toBeVisible()
      await page.getByRole('radio', { name: /Eigene Adresse/ }).click()
      await page.getByLabel('Basis-Adresse').fill(TRANSCRIPTION_URL)
      await page.getByLabel('Basis-Adresse').press('Tab')
      await expect(page.getByText(/1 Modell gefunden\./)).toBeVisible()
      await page.getByRole('combobox', { name: 'Modell-Kennung' }).click()
      await page.getByRole('option', { name: new RegExp(TRANSCRIPTION_MODEL) }).click()
      await expect(page.getByLabel('Sprache (optional)')).toHaveValue('de')
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Spracheingabe ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)

      // Text recognition with an own docling-serve endpoint, ocr-stub, checked in its stages with
      // the test page; a wrong key fails at the stage "Anmeldung".
      await expect(stepHeading(page, 'Texterkennung')).toBeVisible()
      await page.getByRole('radio', { name: /Eigener Endpunkt/ }).click()
      await page.getByRole('radio', { name: /^docling-serve/ }).click()
      await page.getByLabel('Basis-Adresse').fill(TEXT_RECOGNITION_URL)
      await page.getByRole('textbox', { name: 'API-Schlüssel (optional)' }).fill('falscher-schluessel')
      await submitStep(page)
      await expect(page.getByText(/Gescheitert an der Stufe „Anmeldung“/)).toBeVisible()
      // The field keeps what was typed; the right key replaces the wrong one.
      await page.getByRole('textbox', { name: 'API-Schlüssel (optional)' }).fill(OCR_API_KEY)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Texterkennung ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)

      // The virus scanner: clamd-stub answers the version request of the check.
      await expect(stepHeading(page, 'Virenprüfung')).toBeVisible()
      await page.getByLabel('Host des Virenscanners').fill(CLAMD_HOST)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Virenprüfung ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)

      await expect(stepHeading(page, 'E-Mail')).toBeVisible()
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
      await expectNoSeriousA11yViolations(page, 'Einrichtung: E-Mail ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)

      // The check alone sends nothing; the field of the recipient says so before it runs, the
      // summary in its outcome.
      await expect(page.getByLabel('Testnachricht an (optional)')).toHaveAccessibleDescription(
        /Ohne Adresse prüft der Schritt nur die Verbindung und sendet nichts/,
      )
      await submitStep(page)
      await expect(stepHeading(page, 'Branding')).toBeVisible()
      expect(await mailCount()).toBe(0)
      // The outcome of the check stands in the summary, reached over the step list.
      const steps = page.getByRole('navigation', { name: 'Schritte der Einrichtung' })
      await steps.getByRole('button', { name: /Abschluss/ }).click()
      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      await expect(stepStates(page)).toContainText(
        'Die Verbindung zum Mailserver ist gelungen. Es wurde keine Testnachricht gesendet.',
      )

      // Checked again with an address, the test message goes there and nowhere else.
      await stepState(page, 'E-Mail').getByRole('button', { name: 'E-Mail', exact: true }).click()
      await expect(stepHeading(page, 'E-Mail')).toBeVisible()
      await page.getByLabel('Testnachricht an (optional)').fill(TEST_RECIPIENT)
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)
      const mail = await waitForMail(TEST_RECIPIENT)
      expect(mail.recipients).toEqual([TEST_RECIPIENT])
      expect(await mailCount()).toBe(1)

      // The branding of the house, with the fields and the preview of the administration.
      await expect(stepHeading(page, 'Branding')).toBeVisible()
      await expectStepEdgesFlushAlsoNarrow(page)
      await page.getByLabel('Produktname (optional)').fill(PRODUCT_NAME)
      await page.getByLabel('Primärfarbe (optional)').fill(ACCENT)
      const previews = page.locator('main figure')
      await expect(previews).toHaveCount(2)
      await expect(previews.nth(0)).toContainText(PRODUCT_NAME)
      await expect(previews.nth(1)).toContainText(PRODUCT_NAME)
      await expectNoSeriousA11yViolations(page, 'Einrichtung: Branding ausgefüllt')
      await expectStepEdgesFlushAlsoNarrow(page)
      await submitStep(page)

      await expect(stepHeading(page, 'Abschluss')).toBeVisible()
      await expect(stepStates(page)).toContainText(
        'Die Verbindung zum Mailserver ist gelungen. Die Testnachricht wurde dem Mailserver übergeben.',
      )
      await expect(stepStates(page)).not.toContainText(TEST_RECIPIENT)
      for (const checked of [
        'Identitätsanbieter',
        'Reranking',
        'Spracheingabe',
        'Texterkennung',
        'Virenprüfung',
        'E-Mail',
        'Branding',
      ]) {
        await expect(stepState(page, checked)).toContainText('Geprüft')
      }
      await page.getByRole('button', { name: 'Installation abschließen' }).click()
      await expect(stepHeading(page, 'Installation abgeschlossen')).toBeVisible()

      // Product name and accent colour on the sign-in page and in the interface. The page first
      // tries a silent sign-in at the provider; oidc-stub answers login_required and the page comes
      // back with the provider above the local accounts.
      await page.getByRole('link', { name: 'Zur Anmeldung' }).click()
      await expect(page).toHaveURL(/\/login/)
      const providers = page.getByRole('group', { name: 'Identitätsanbieter' })
      await expect(providers).toContainText(OIDC_PROVIDER_NAME)
      const signIn = page.getByRole('form', { name: 'Konto dieser Installation' })
      await expect(page.getByText(PRODUCT_NAME, { exact: true }).first()).toBeVisible()
      // The provider carries the page's one primary button, in the accent; the local form's
      // „Anmelden“ stays secondary.
      await expect(providers.getByRole('button').first()).toHaveCSS('background-color', ACCENT_RGB)
      const signInButton = signIn.getByRole('button', { name: 'Anmelden' })
      await expect(signInButton).toHaveClass(/MuiButton-outlined/)
      await signIn.getByLabel('E-Mail-Adresse').fill(ADMIN_EMAIL)
      await signIn.getByLabel('Passwort', { exact: true }).fill(ADMIN_PASSWORD)
      await signInButton.click()
      await expect(page).toHaveURL(/\/chats\/new/, { timeout: 20_000 })
      await expect(page).toHaveTitle(new RegExp(`${PRODUCT_NAME}$`))
      await page.goto('/admin/branding')
      await expect(page.getByLabel('Produktname')).toHaveValue(PRODUCT_NAME)
      await expect(page.getByRole('button', { name: 'Speichern' })).toHaveCSS(
        'background-color',
        ACCENT_RGB,
      )

      // The rerank model of the assistant stands switched on in the model administration.
      await page.goto('/admin/models/rerank')
      await expect(page.getByRole('checkbox', { name: 'Reranking einschalten' })).toBeChecked()
      await expect(page.getByLabel('Basis-Adresse')).toHaveValue(RERANK_URL)
      await expect(page.getByLabel('Modell-Kennung')).toHaveValue(RERANK_MODEL)

      // The completion switched speech input on with the model of the assistant.
      await page.goto('/admin/models/transcription')
      await expect(page.getByRole('checkbox', { name: 'Spracheingabe einschalten' })).toBeChecked()
      await expect(page.getByLabel('Basis-Adresse')).toHaveValue(TRANSCRIPTION_URL)
      await expect(page.getByLabel('Modell-Kennung')).toHaveValue(TRANSCRIPTION_MODEL)

      // Text recognition and virus scan, switched on with the values of the assistant.
      await page.goto('/admin/models/text-recognition')
      await expect(page.getByRole('checkbox', { name: 'Texterkennung einschalten' })).toBeChecked()
      await expect(page.getByLabel('Basis-Adresse')).toHaveValue(TEXT_RECOGNITION_URL)
      await page.goto('/admin/malware-scan')
      await expect(page.getByRole('checkbox', { name: 'Jede eingehende Datei prüfen' })).toBeChecked()
      await expect(page.getByLabel('Host des Virenscanners')).toHaveValue(CLAMD_HOST)
    },
  )
})
