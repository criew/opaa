import { PDFDocument } from 'pdf-lib'
import { expect, test } from '../../fixtures/auth'
import { askQuestion, startFreshChat } from '../../fixtures/chat'
import { apiAs } from '../../fixtures/externalAccess'
import { cleanupLibraries } from '../../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../../fixtures/spaces'
import type { APIRequestContext, Locator, Page } from '@playwright/test'

/**
 * The agent spike end to end: a question with the prefix "@test " searches the knowledge base,
 * writes a PDF with run_code in an OpenSandbox sandbox and hangs it at the answer; a follow-up
 * question corrects it; a reload shows both files again.
 *
 * Two variants. Without E2E_REAL_MODEL_BASE_URL the chat model is ai-stub, which plays a fixed
 * script (search, run_code with a reportlab script, closing answer) and titles the PDF with the
 * text in quotes of the question - every assertion is exact. With E2E_REAL_MODEL_BASE_URL,
 * E2E_REAL_MODEL_NAME and E2E_REAL_MODEL_API_KEY the spec activates that model for the run and
 * checks only what a real model reliably does: a valid PDF at each answer.
 */

const ADMIN = 'dev-admin'
const REAL_MODEL = process.env.E2E_REAL_MODEL_BASE_URL
  ? {
      baseUrl: process.env.E2E_REAL_MODEL_BASE_URL,
      name: process.env.E2E_REAL_MODEL_NAME ?? 'claude-haiku-4-5',
      apiKey: process.env.E2E_REAL_MODEL_API_KEY ?? '',
    }
  : null
const ANSWER_TIMEOUT = REAL_MODEL ? 180_000 : 60_000

interface ModelEntry {
  id: string
  active: boolean
}

function answers(page: Page): Locator {
  return page
    .getByTestId('message-list')
    .locator('[data-message-id]')
    .filter({ has: page.getByTestId('ai-answer-mark') })
}

function generatedFiles(answer: Locator): Locator {
  return answer.getByRole('list', { name: 'Erzeugte Dateien' }).getByRole('button')
}

async function pdfOf(api: APIRequestContext, attachmentId: string): Promise<PDFDocument> {
  const response = await api.get(`/api/v1/chat-attachments/${attachmentId}/content`)
  expect(response.ok(), await response.text()).toBe(true)
  const bytes = await response.body()
  expect(bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-')
  return PDFDocument.load(bytes, { updateMetadata: false })
}

test.describe('Agent mit Code-Sandbox (Spike)', () => {
  const createdLibraryIds = cleanupLibraries()
  let restoreModelId: string | null = null

  test.beforeAll(async () => {
    const suffix = Date.now().toString(36)
    const id = await createKnowledgeLibraryViaApi(ADMIN, {
      name: `E2E-Sandbox-${suffix}`,
    })
    createdLibraryIds.push(id)
    const api = await apiAs(ADMIN)
    try {
      const uploaded = await api.post(`/api/v1/libraries/${id}/documents`, {
        multipart: {
          file: {
            name: `fristen-${suffix}.txt`,
            mimeType: 'text/plain',
            buffer: Buffer.from(
              'Dienstanweisung Fristen: Anträge auf Wohngeld werden binnen zehn Tagen bearbeitet.\n' +
                'Widersprüche werden binnen vier Wochen beschieden.\n' +
                'Akteneinsicht wird binnen fünf Werktagen gewährt.',
              'utf-8',
            ),
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
          { timeout: 60_000 },
        )
        .toBe('INDEXED')
      await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', id)

      if (REAL_MODEL) {
        const models = (await (await api.get('/api/v1/admin/models')).json()) as ModelEntry[]
        restoreModelId = models.find((model) => model.active)?.id ?? null
        const created = await api.post('/api/v1/admin/models', {
          data: {
            displayName: `E2E-Sandbox-${suffix}`,
            baseUrl: REAL_MODEL.baseUrl,
            modelIdentifier: REAL_MODEL.name,
            apiKey: REAL_MODEL.apiKey,
            maxTokens: 4096,
          },
        })
        expect(created.ok(), await created.text()).toBe(true)
        const { id: modelId } = (await created.json()) as { id: string }
        const activated = await api.post(`/api/v1/admin/models/${modelId}/activate`)
        expect(activated.ok(), await activated.text()).toBe(true)
      }
    } finally {
      await api.dispose()
    }
  })

  test.afterAll(async () => {
    if (!restoreModelId) return
    const api = await apiAs(ADMIN)
    try {
      await api.post(`/api/v1/admin/models/${restoreModelId}/activate`)
    } finally {
      await api.dispose()
    }
  })

  test('sucht, erzeugt ein PDF, korrigiert es und zeigt beide nach dem Neuladen', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)

    await askQuestion(
      page,
      '@test Erstelle mir aus der Wissensbasis ein PDF „Fristenübersicht“ mit allen Bearbeitungsfristen.',
    )
    const first = answers(page).nth(0)
    await expect(generatedFiles(first)).toHaveCount(1, {
      timeout: ANSWER_TIMEOUT,
    })
    await expect(generatedFiles(first).first()).toHaveAccessibleName(
      /^Datei .+\.pdf herunterladen$/,
    )
    await expect(first).toContainText('Code-Läufe:')

    // The chip downloads the file through the authenticated client.
    const downloadPromise = page.waitForEvent('download')
    await generatedFiles(first).first().click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/\.pdf$/)

    await askQuestion(page, '@test Bitte korrigiere das PDF: Der Titel soll „Fristen 2026“ lauten.')
    const second = answers(page).nth(1)
    await expect(generatedFiles(second)).toHaveCount(1, {
      timeout: ANSWER_TIMEOUT,
    })

    const chatId = /\/chats\/([0-9a-f-]+)/.exec(page.url())?.[1]
    expect(chatId, page.url()).toBeTruthy()
    const api = await apiAs(ADMIN)
    try {
      const chat = (await (await api.get(`/api/v1/chats/${chatId}`)).json()) as {
        messages: Array<{
          role: string
          attachments?: Array<{ id: string; fileName: string }>
        }>
      }
      const files = chat.messages
        .filter((message) => message.role === 'ASSISTANT')
        .flatMap((message) => message.attachments ?? [])
      expect(files).toHaveLength(2)
      const [original, corrected] = await Promise.all(files.map((file) => pdfOf(api, file.id)))
      expect(original.getPageCount()).toBeGreaterThan(0)
      expect(corrected.getPageCount()).toBeGreaterThan(0)
      if (!REAL_MODEL) {
        expect(original.getTitle()).toBe('Fristenübersicht')
        expect(corrected.getTitle()).toBe('Fristen 2026')
      }
    } finally {
      await api.dispose()
    }

    await page.reload()
    await expect(generatedFiles(answers(page).nth(0))).toHaveCount(1)
    await expect(generatedFiles(answers(page).nth(1))).toHaveCount(1)
  })
})
