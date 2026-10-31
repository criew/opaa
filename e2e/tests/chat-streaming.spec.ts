import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import {
  askQuestion,
  clearSearchScope,
  expectCitedSource,
  isChatAnswer,
  startFreshChat,
} from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries } from '../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../fixtures/spaces'

/**
 * The chat answer as an event stream, through the frontend container's nginx: the provisional text
 * grows while ai-stub writes it, the final answer with its sources replaces it, and a cancel ends
 * the model call and keeps nothing of the turn. ai-stub streams slowly for the trigger word below.
 */

const ADMIN = 'dev-admin'
const SLOW = 'E2E-ANTWORT-LANGSAM'
const AI_STUB_BASE_URL = process.env.E2E_AI_STUB_BASE_URL ?? 'http://localhost:18089'
const PROVISIONAL_LABEL = 'Antwort wird geschrieben'

async function abandonedStreams(): Promise<number> {
  const response = await fetch(`${AI_STUB_BASE_URL}/control/streams`)
  return ((await response.json()) as { abandoned: number }).abandoned
}

test.describe('Antwort im Fluss', () => {
  test.describe.configure({ timeout: 90_000 })

  const createdLibraryIds = cleanupLibraries()
  let documentName = ''

  test.beforeAll(async () => {
    const suffix = `${Date.now()}`
    const id = await createKnowledgeLibraryViaApi(ADMIN, {
      name: `E2E-Strom-${suffix}`,
    })
    createdLibraryIds.push(id)
    documentName = `strom-${suffix}.txt`
    const api = await apiAs(ADMIN)
    try {
      const uploaded = await api.post(`/api/v1/libraries/${id}/documents`, {
        multipart: {
          file: {
            name: documentName,
            mimeType: 'text/plain',
            buffer: Buffer.from('Dienstanweisung: Anträge werden binnen zehn Tagen bearbeitet.'),
          },
        },
      })
      expect(uploaded.ok(), await uploaded.text()).toBe(true)
      await expect
        .poll(
          async () => {
            const response = await api.get(`/api/v1/libraries/${id}/documents?size=10`)
            const body = (await response.json()) as {
              items: Array<{ status: string }>
            }
            return body.items.map((item) => item.status).join(',')
          },
          { timeout: 30_000 },
        )
        .toBe('INDEXED')
      await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', id)
    } finally {
      await api.dispose()
    }
  })

  test('zeigt die Antwort vorläufig, während sie entsteht, und ersetzt sie durch die belegte', async ({
    page,
  }) => {
    await startFreshChat(page)
    const [answer] = await Promise.all([
      page.waitForResponse(isChatAnswer),
      askQuestion(page, `${SLOW} Wie schnell werden Anträge bearbeitet? (${Date.now()})`),
    ])
    expect(answer.headers()['content-type']).toContain('text/event-stream')

    // Visible long before the stream ends: nginx passes every chunk on at once.
    const provisional = page.getByTestId('provisional-answer')
    await expect(provisional).toContainText('Antwort auf Basis der bereitgestellten Dokumente.')
    await expect(page.getByRole('status').filter({ hasText: PROVISIONAL_LABEL })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Antwort abbrechen' })).toBeVisible()
    // Nothing to copy or export while the answer is not checked yet.
    await expect(provisional.getByRole('button')).toHaveCount(0)
    await expectNoSeriousA11yViolations(page, 'Chat mit vorläufiger Antwort')

    await expect(provisional).toHaveCount(0, { timeout: 60_000 })
    await expect(page.getByText(PROVISIONAL_LABEL)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Antwort abbrechen' })).toHaveCount(0)
    await expectCitedSource(page, documentName)
  })

  test('bricht die Antwort ab, beendet den Modellaufruf und behält nichts', async ({ page }) => {
    const question = `${SLOW} Was gilt für Anträge im Urlaub? (${Date.now()})`
    const abandonedBefore = await abandonedStreams()

    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, question)
    await expect(page.getByTestId('provisional-answer')).toBeVisible()
    await page.getByRole('button', { name: 'Antwort abbrechen' }).click()

    await expect(page.getByText('Antwort abgebrochen.')).toBeVisible()
    await expect(page.getByTestId('provisional-answer')).toHaveCount(0)
    await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toHaveValue(question)
    await expect.poll(abandonedStreams, { timeout: 15_000 }).toBeGreaterThan(abandonedBefore)

    // The turn was never kept: a reload of the draft shows no question.
    await page.reload()
    await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeVisible()
    await expect(page.getByText(question)).toHaveCount(0)
  })
})
