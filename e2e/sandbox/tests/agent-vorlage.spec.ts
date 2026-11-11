import { PDFDocument } from 'pdf-lib'
import { expect, test } from '../../fixtures/auth'
import { startFreshChat } from '../../fixtures/chat'
import { buildDocx, readZipTexts } from '../../fixtures/docx'
import { apiAs } from '../../fixtures/externalAccess'
import { assignToDefaultSpace } from '../../fixtures/spaces'
import {
  answers,
  askAgent,
  createAgentViaApi,
  createIndexedLibrary,
  deleteAgentViaApi,
  restoreAgentSettings,
  setAgentSettings,
  setTemplatesEnabled,
  setToolCapability,
  TOOLS_WITHOUT_SANDBOX,
  type AgentSettings,
} from '../../fixtures/agents'
import type { APIRequestContext, Locator, Page } from '@playwright/test'

/**
 * Szenario 9: ein Agent mit Vorlage liefert Word-Datei und PDF mit
 * befüllten Platzhaltern, Fassungsstempel und Entwurfsvermerk; ist die Vorlage gesperrt, bleibt die
 * Chatantwort ohne Datei. Gehört zum Ziel `sandbox`, weil `fill_template` die Sandbox braucht
 * (OpenSandbox, siehe e2e/README.md); ai-stub spielt das Modell und erfindet die Werte aus dem
 * Schema des Werkzeugs (Auslöser `E2E-AGENT-DATEI`).
 */

const ADMIN = 'dev-admin'
const SUFFIX = Date.now().toString(36)
const TEMPLATE_NAME = `E2E-Bescheid-${SUFFIX}`
const AGENT_NAME = `E2E-Vorlage-${SUFFIX}`

function generatedFiles(answer: Locator): Locator {
  return answer.getByRole('list', { name: 'Erzeugte Dateien' }).getByRole('button')
}

async function attachmentBytes(api: APIRequestContext, id: string): Promise<Buffer> {
  const response = await api.get(`/api/v1/chat-attachments/${id}/content`)
  expect(response.ok(), await response.text()).toBe(true)
  return response.body()
}

function chatIdOf(page: Page): string {
  const id = /\/chats\/([0-9a-f-]+)/.exec(page.url())?.[1]
  expect(id, page.url()).toBeTruthy()
  return id!
}

test.describe.serial('Agent mit Vorlage: Datei nach Vorlage', () => {
  test.describe.configure({ timeout: 240_000 })

  let agentsBefore: AgentSettings | null = null
  let templatesBefore: boolean | null = null
  let capabilityBefore: Awaited<ReturnType<typeof setToolCapability>> | null = null
  let libraryId = ''
  let templateId = ''
  let agentId = ''

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(true, [...TOOLS_WITHOUT_SANDBOX, 'fill_template'])
    templatesBefore = await setTemplatesEnabled(true)
    capabilityBefore = await setToolCapability('CAPABLE')
    libraryId = await createIndexedLibrary(ADMIN, `E2E-Vorlage-Wissen-${SUFFIX}`, [
      {
        name: `wohngeld-${SUFFIX}.txt`,
        text: 'Dienstanweisung Wohngeld: Anträge auf Wohngeld werden binnen zehn Tagen bearbeitet.',
      },
    ])
    await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', libraryId)

    const api = await apiAs(ADMIN)
    try {
      const created = await api.post('/api/v1/document-templates', {
        multipart: {
          file: {
            name: 'bescheid.docx',
            mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            buffer: buildDocx(['Aktenzeichen: {{ aktenzeichen }}', 'Betreff: {{ betreff }}']),
          },
          name: TEMPLATE_NAME,
          responsibleFunction: 'Referat E2E - Organisation',
        },
      })
      expect(created.status(), await created.text()).toBe(201)
      templateId = ((await created.json()) as { id: string }).id
    } finally {
      await api.dispose()
    }
    agentId = await createAgentViaApi(ADMIN, {
      name: AGENT_NAME,
      libraryIds: [libraryId],
      toolNames: ['search_knowledge', 'fill_template'],
      templateIds: [templateId],
    })
    await assignToDefaultSpace(ADMIN, 'AGENT', agentId)
  })

  test.afterAll(async () => {
    if (agentId) await deleteAgentViaApi(ADMIN, agentId)
    const api = await apiAs(ADMIN)
    try {
      if (templateId) await api.delete(`/api/v1/document-templates/${templateId}`)
      if (libraryId) {
        const documents = (await (
          await api.get(`/api/v1/libraries/${libraryId}/documents?size=100`)
        ).json()) as { items: Array<{ id: string }> }
        for (const document of documents.items) {
          await api.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`)
        }
        await api.delete(`/api/v1/libraries/${libraryId}`)
      }
    } finally {
      await api.dispose()
    }
    await restoreAgentSettings(agentsBefore)
    if (templatesBefore !== null) await setTemplatesEnabled(templatesBefore)
    if (capabilityBefore && capabilityBefore !== 'CAPABLE') {
      await setToolCapability(capabilityBefore)
    }
  })

  test('9. Datei nach Vorlage: Word-Datei und PDF, befüllt, mit Stempel und Entwurfsvermerk', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    await askAgent(
      page,
      AGENT_NAME,
      `E2E-AGENT-DATEI Erstelle den Bescheid zu „Wohngeld“. (${SUFFIX})`,
    )
    const answer = answers(page).first()
    await expect(generatedFiles(answer)).toHaveCount(2, { timeout: 120_000 })
    await expect(generatedFiles(answer).first()).toHaveAccessibleName(/^Datei .+ herunterladen$/)
    await answer.getByRole('button', { name: /Schritte?$/ }).click()
    await expect(answer.getByRole('listitem').filter({ hasText: /^Vorlage/ })).toBeVisible()

    // The stamp of the answer names the template with its revision.
    const stamp = answer.getByTestId('agent-stamp')
    await stamp.getByRole('button', { name: /^Fassung \d+$/ }).click()
    await expect(stamp).toContainText(TEMPLATE_NAME)
    await stamp.getByRole('button', { name: /^Fassung \d+$/ }).click()

    const api = await apiAs(ADMIN)
    try {
      const chat = (await (await api.get(`/api/v1/chats/${chatIdOf(page)}`)).json()) as {
        messages: Array<{
          role: string
          attachments?: Array<{ id: string; fileName: string }>
        }>
      }
      const files = chat.messages
        .filter((message) => message.role === 'ASSISTANT')
        .flatMap((message) => message.attachments ?? [])
      const docx = files.find((file) => file.fileName.endsWith('.docx'))
      const pdf = files.find((file) => file.fileName.endsWith('.pdf'))
      expect(docx, JSON.stringify(files)).toBeDefined()
      expect(pdf, JSON.stringify(files)).toBeDefined()

      const parts = readZipTexts(await attachmentBytes(api, docx!.id))
      const body = parts.get('word/document.xml') ?? ''
      expect(body).toContain('Wohngeld (wert aktenzeichen)')
      expect(body).toContain('Wohngeld (wert betreff)')
      expect(body).not.toContain('{{')
      const footers = [...parts.entries()]
        .filter(([name]) => /^word\/footer\d*\.xml$/.test(name))
        .map(([, text]) => text)
        .join('')
      expect(footers).toContain('Entwurf, nicht geprüft')
      expect(footers).toContain(AGENT_NAME)

      const pdfDocument = await PDFDocument.load(await attachmentBytes(api, pdf!.id), {
        updateMetadata: false,
      })
      expect(pdfDocument.getPageCount()).toBeGreaterThan(0)
    } finally {
      await api.dispose()
    }
  })

  test('9b. Gesperrte Vorlage: keine Datei, die Antwort bleibt im Chat', async ({
    authenticatedPage: page,
  }) => {
    const api = await apiAs(ADMIN)
    try {
      const locked = await api.put(`/api/v1/document-templates/${templateId}/lock`, {
        data: { reason: 'Vorlage wird überarbeitet' },
      })
      expect(locked.ok(), await locked.text()).toBe(true)

      await startFreshChat(page)
      await askAgent(
        page,
        AGENT_NAME,
        `E2E-AGENT-DATEI Erstelle den Bescheid zu „Wohngeld“. (${SUFFIX}-gesperrt)`,
      )
      const answer = answers(page).first()
      await expect(answer).toContainText('Ergebnis des Agenten.', { timeout: 120_000 })
      await expect(answer).toContainText('Es steht keine Vorlage zur Verfügung')
      await expect(answer.getByRole('list', { name: 'Erzeugte Dateien' })).toHaveCount(0)
    } finally {
      await api.delete(`/api/v1/document-templates/${templateId}/lock`)
      await api.dispose()
    }
  })
})
