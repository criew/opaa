import { expect, test } from '../fixtures/auth'
import { askQuestion, clearSearchScope, startFreshChat } from '../fixtures/chat'
import { DEV_USER_HEADER } from '../fixtures/libraries'
import type { Page } from '@playwright/test'

/**
 * Kennzeichnung KI-erzeugter Antworten (Art. 50 KI-VO, Epic #3010): Hinweis im Chat, Kennzeichen an
 * der Antwort, Vermerk beim Kopierknopf und der änderbare Hinweistext im Branding.
 *
 * Der Hinweistext ist eine Einstellung der ganzen Installation. Szenarien, die ihn ändern,
 * stellen den Standard in jedem Fall wieder her (finally), damit kein anderes Szenario einen
 * fremden Text sieht; die Suite läuft mit einem Worker nacheinander.
 */

const NOTICE_NAME = 'Hinweis zur KI-Nutzung'
const ANSWER_MARK_NAME = 'Diese Antwort wurde von einer KI erzeugt'
const STANDARD_NOTICE =
  'Antworten werden von einer KI erzeugt und können Fehler enthalten. ' +
  'Prüfen Sie wichtige Angaben anhand der Belege.'

/** Back to the OPAA standard through the API, whatever state a failed attempt left behind. */
async function resetNotice(page: Page): Promise<void> {
  const response = await page.request.put('/api/v1/system/branding', {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
    data: {},
  })
  expect(response.ok()).toBe(true)
}

async function readClipboard(page: Page): Promise<string> {
  return (await page.evaluate('navigator.clipboard.readText()')) as string
}

async function allowClipboard(page: Page): Promise<void> {
  await page
    .context()
    .grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(page.url()).origin })
}

/** Asks one question without a search scope, so the deterministic KI stub answers it. */
async function askOneQuestion(page: Page, question: string): Promise<void> {
  await startFreshChat(page)
  await clearSearchScope(page)
  await askQuestion(page, question)
  await expect(page.getByTestId('message-list').getByText(question)).toBeVisible()
}

test.describe('Kennzeichnung KI-erzeugter Antworten (#3010)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await resetNotice(page)
  })

  test('Leerer Chat: Hinweis vor der ersten Frage, auffindbar über Rolle und Namen', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)

    // two places: the prominent notice in the empty chat and the permanent line under the input
    const notices = page.getByRole('note', { name: NOTICE_NAME })
    await expect(notices).toHaveCount(2)
    await expect(notices.first()).toContainText(STANDARD_NOTICE)
    await expect(notices.last()).toContainText(STANDARD_NOTICE)
  })

  test('Antwort trägt das Kennzeichen „KI-erzeugt“ mit zugänglichem Namen', async ({
    authenticatedPage: page,
  }, testInfo) => {
    await askOneQuestion(page, `Was ist eine Kennzeichnung? ${Date.now()}-${testInfo.retry}`)

    const list = page.getByTestId('message-list')
    const mark = list.getByRole('note', { name: ANSWER_MARK_NAME })
    await expect(mark).toBeVisible()
    await expect(mark).toHaveText('KI-erzeugt')
    // the notice line under the input is still there with a conversation going
    await expect(page.getByRole('note', { name: NOTICE_NAME })).toHaveCount(1)
  })

  test('Kopierknopf: Zwischenablage endet mit Hinweistext, Produktname und Datum, ohne Modellname', async ({
    authenticatedPage: page,
  }, testInfo) => {
    await askOneQuestion(page, `Wie wird kopiert? ${Date.now()}-${testInfo.retry}`)
    await allowClipboard(page)

    await page
      .getByTestId('message-list')
      .getByRole('button', { name: 'Antwort kopieren' })
      .click()
    await expect(page.getByRole('button', { name: 'Antwort kopiert' })).toBeVisible()

    const copied = await readClipboard(page)
    const lines = copied.trim().split('\n')
    expect(lines.at(-1)).toMatch(
      new RegExp(`^${escapeRegExp(STANDARD_NOTICE)} – OPAA, \\d{2}\\.\\d{2}\\.\\d{4}$`),
    )
    // the note stands in a paragraph of its own, under the answer
    expect(lines.at(-2)).toBe('')
    // no model name: the note names product and date only
    expect(lines.at(-1)).not.toMatch(/stub|gpt|llama|mistral|claude/i)
  })

  test('Branding: geänderter Hinweistext wirkt im Chat und im Vermerk, Zurücksetzen stellt den Standard her', async ({
    authenticatedPage: page,
  }, testInfo) => {
    const customNotice = `Diese Antworten stammen von einer KI und sind zu prüfen (${Date.now()}-${testInfo.retry}).`
    try {
      await page.goto('/admin/branding')
      const field = page.getByLabel(NOTICE_NAME)
      await expect(field).toHaveValue(STANDARD_NOTICE)
      await field.fill(customNotice)
      await page.getByRole('button', { name: 'Speichern' }).click()
      await expect(page.getByText(/gespeichert und ist sofort wirksam/i)).toBeVisible()

      await askOneQuestion(page, `Wirkt der Text? ${Date.now()}-${testInfo.retry}`)
      await expect(
        page.getByRole('note', { name: NOTICE_NAME }),
      ).toContainText(customNotice)
      await allowClipboard(page)
      await page
        .getByTestId('message-list')
        .getByRole('button', { name: 'Antwort kopieren' })
        .click()
      await expect(page.getByRole('button', { name: 'Antwort kopiert' })).toBeVisible()
      const copied = await readClipboard(page)
      expect(copied.trim().split('\n').at(-1)).toContain(customNotice)

      // back to the standard through the form's own reset
      await page.goto('/admin/branding')
      await expect(page.getByLabel(NOTICE_NAME)).toHaveValue(customNotice)
      await page.getByRole('button', { name: 'Hinweis auf Standard zurücksetzen' }).click()
      await expect(page.getByLabel(NOTICE_NAME)).toHaveValue(STANDARD_NOTICE)
      await page.getByRole('button', { name: 'Speichern' }).click()
      await expect(page.getByText(/gespeichert und ist sofort wirksam/i)).toBeVisible()

      await startFreshChat(page)
      await expect(page.getByRole('note', { name: NOTICE_NAME }).first()).toContainText(
        STANDARD_NOTICE,
      )
    } finally {
      await resetNotice(page)
    }
  })
})

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
