import { writeFile } from 'node:fs/promises'
import type { Page } from '@playwright/test'
import { PDFDocument, StandardFonts } from 'pdf-lib'
import { expect, openAs, test } from '../fixtures/auth'
import {
  askQuestion,
  clearSearchScope,
  createLibraryWithDocuments,
  expectCitedSource,
} from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { deleteLibraryCompletely, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import { createSpaceWithMember, deleteSpaceViaApi } from '../fixtures/privateLibraries'
import {
  COMPLETED_MESSAGE,
  DOWNLOAD_BUTTON,
  PERSONAL_ONLY_NOTICE,
  allText,
  captureDownload,
  expectExportStructure,
  exportedMessages,
  openMyData,
} from '../fixtures/selfDisclosure'

/**
 * Self-disclosure from the own settings in dev auth mode, where every request counts as a recent
 * sign-in. The password confirmation is covered by the local-auth target. Daily limit and switch
 * are installation-wide: each test sets what it needs over the admin API, afterAll restores them.
 */

const USER_A = 'dev-user'
const USER_B = 'dev-outsider'
const LOADER = 'dev-format-pipelines'
const B_NAME = 'Dev Outsider'
const B_ADDRESS = 'outsider@opaa.local'

const runId = Date.now().toString(36)
const SPACE_NAME = `E2E Selbstauskunft ${runId}`
const LIBRARY_NAME = `E2E-Selbstauskunft-${runId}`
const PDF_NAME = `selbstauskunft-fundstelle-${runId}.pdf`
const NOTE_NAME = `selbstauskunft-vermerk-${runId}.md`
/** Heading of the note: it becomes the stored location of the source, never part of the export. */
const MARKER = `Ausschnittsmarke ${runId}`
const BODY = `Wasserrohrbruch im Ostfluegel ${runId}`
const QUESTION_A = `Frage von A ${runId}: Was ist im Gebaeude passiert?`
const QUESTION_B = `Frage von B ${runId}: Welche Frist gilt?`

const SETTINGS = '/api/v1/admin/self-disclosure/settings'
const STATUS = '/api/v1/me/self-disclosure'

interface Settings {
  enabled: boolean
  dailyLimit: number
}

interface Status {
  enabled: boolean
  dailyLimit: number
  completedToday: number
  startsToday: number
  remainingExports: number
  blocked: string | null
  nextPossibleAt: string | null
}

let spaceId: string | undefined
let libraryId: string | undefined
let initialSettings: Settings | undefined
let chatIdA: string | undefined

async function readSettings(): Promise<Settings> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.get(SETTINGS)
    expect(response.status(), await response.text()).toBe(200)
    return (await response.json()) as Settings
  } finally {
    await api.dispose()
  }
}

async function updateSettings(update: Partial<Settings>): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.put(SETTINGS, { data: update })
    expect(response.status(), await response.text()).toBe(200)
  } finally {
    await api.dispose()
  }
}

async function statusOf(devUser: string): Promise<Status> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get(STATUS)
    expect(response.status(), await response.text()).toBe(200)
    return (await response.json()) as Status
  } finally {
    await api.dispose()
  }
}

/**
 * Switches the self-disclosure on and sets the daily limit to exactly one more complete download
 * than `devUser` has today - so the next download is possible and uses up the day.
 */
async function allowExactlyOneMore(devUser: string): Promise<Status> {
  const before = await statusOf(devUser)
  const limit = before.completedToday + 1
  expect(
    limit,
    `${devUser} hat heute schon zu viele Downloads für einen weiteren`,
  ).toBeLessThanOrEqual(10)
  await updateSettings({ enabled: true, dailyLimit: limit })
  const now = await statusOf(devUser)
  expect(now.blocked).toBeNull()
  return now
}

/** How many notifications „Selbstauskunft gestartet“ `devUser` holds. */
async function startedNotifications(devUser: string): Promise<number> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get('/api/v1/notifications', { params: { limit: 100 } })
    expect(response.status(), await response.text()).toBe(200)
    const items = (await response.json()) as Array<{ type: string }>
    return items.filter((item) => item.type === 'SELF_DISCLOSURE_STARTED').length
  } finally {
    await api.dispose()
  }
}

async function buildPdf(): Promise<Buffer> {
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  const page = pdf.addPage([595, 842])
  page.drawText(BODY, { x: 40, y: 780, size: 12, font })
  return Buffer.from(await pdf.save())
}

/** Every location the stored sources of the chat name, as the chat API returns them. */
async function storedLocations(devUser: string, chatId: string): Promise<string[]> {
  const api = await apiAs(devUser)
  try {
    const response = await api.get(`/api/v1/chats/${chatId}`)
    expect(response.status(), await response.text()).toBe(200)
    const chat = (await response.json()) as {
      messages: Array<{ sources?: Array<{ chunkLocations?: Array<{ location?: string }> }> }>
    }
    return chat.messages.flatMap((message) =>
      (message.sources ?? []).flatMap((source) =>
        (source.chunkLocations ?? []).flatMap((chunk) => (chunk.location ? [chunk.location] : [])),
      ),
    )
  } finally {
    await api.dispose()
  }
}

/** Asks one question in the open chat, waits for its answer and returns the chat id. */
async function askAndWait(page: Page, question: string): Promise<string> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' && candidate.url().endsWith('/api/v1/query'),
    ),
    askQuestion(page, question),
  ])
  expect(response.status()).toBe(200)
  await expect(page.getByText('Denkt nach …')).toHaveCount(0)
  return ((await response.json()) as { chatId: string }).chatId
}

async function openSpaceChat(page: Page): Promise<void> {
  await page.goto(`/spaces/${spaceId}/chats/new`)
  await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeVisible()
}

test.describe('Selbstauskunft aus den Einstellungen', () => {
  test.describe.configure({ timeout: 90_000 })

  test.beforeAll(async ({ browser }, testInfo) => {
    test.setTimeout(240_000)
    initialSettings = await readSettings()
    spaceId = await createSpaceWithMember(SPACE_NAME, USER_A, USER_B)

    const pdfPath = testInfo.outputPath(PDF_NAME)
    await writeFile(pdfPath, await buildPdf())
    const notePath = testInfo.outputPath(NOTE_NAME)
    await writeFile(
      notePath,
      `# ${MARKER}

${BODY}
`,
    )

    const contextA = await browser.newContext()
    const pageA = await contextA.newPage()
    await openAs(pageA, USER_A)
    await createLibraryWithDocuments(pageA, LIBRARY_NAME, [
      { path: pdfPath, name: PDF_NAME },
      { path: notePath, name: NOTE_NAME },
    ])
    libraryId = libraryIdFromCurrentUrl(pageA)
    const api = await apiAs(USER_A)
    try {
      const assigned = await api.post(`/api/v1/spaces/${spaceId}/assets`, {
        data: { assetType: 'KNOWLEDGE_LIBRARY', assetId: libraryId },
      })
      expect([200, 201], await assigned.text()).toContain(assigned.status())
    } finally {
      await api.dispose()
    }
    // the space holds only this library, so the answer cites both of its documents
    await openSpaceChat(pageA)
    chatIdA = await askAndWait(pageA, QUESTION_A)
    await expectCitedSource(pageA, PDF_NAME)
    await expectCitedSource(pageA, NOTE_NAME)
    await contextA.close()

    const contextB = await browser.newContext()
    const pageB = await contextB.newPage()
    await openAs(pageB, USER_B)
    await openSpaceChat(pageB)
    await clearSearchScope(pageB)
    await askAndWait(pageB, QUESTION_B)
    await contextB.close()
  })

  test.afterAll(async ({ request }) => {
    try {
      if (initialSettings) {
        await updateSettings({
          enabled: initialSettings.enabled,
          dailyLimit: initialSettings.dailyLimit,
        })
      }
    } finally {
      try {
        if (libraryId) await deleteLibraryCompletely(request, libraryId)
      } finally {
        if (spaceId) await deleteSpaceViaApi(USER_A, spaceId)
      }
    }
  })

  test('Szenarien 1, 3 und 4: das ZIP enthält den eigenen Chat mit Fundstelle ohne Ausschnitt, nichts von B', async ({
    regularUserPage: page,
  }) => {
    // the stored sources do carry the heading text, so its absence below is the exporter's doing
    const locations = await storedLocations(USER_A, chatIdA!)
    expect(locations).toContain('S. 1')
    expect(
      locations.some((location) => location.includes(MARKER)),
      locations.join(' | '),
    ).toBe(true)

    await allowExactlyOneMore(USER_A)
    await openMyData(page)
    await expect(page.getByText(PERSONAL_ONLY_NOTICE)).toBeVisible()

    const download = await captureDownload(page, () =>
      page.getByRole('button', { name: DOWNLOAD_BUTTON }).click(),
    )
    await expect(page.getByText(COMPLETED_MESSAGE)).toBeVisible({ timeout: 20_000 })

    expectExportStructure(download)
    const messages = exportedMessages(download.entries)

    const question = messages.find((message) => message.content === QUESTION_A)
    expect(question, 'Frage von A fehlt in chats.json').toBeDefined()
    expect(question!.role).toBe('Frage')

    const answer = messages.find(
      (message) => message.chat === question!.chat && message.role === 'Antwort',
    )
    expect(answer, 'Antwort auf die Frage von A fehlt in chats.json').toBeDefined()
    expect(answer!.sources).toContain(`${PDF_NAME} · Bibliothek: ${LIBRARY_NAME} · S. 1 · zitiert`)
    expect(answer!.sources).toContain(`${NOTE_NAME} · Bibliothek: ${LIBRARY_NAME} · zitiert`)
    const everything = allText(download.entries)
    expect(everything).not.toContain(MARKER)
    expect(everything).not.toContain('Wasserrohrbruch')

    expect(everything).not.toContain(`Frage von B ${runId}`)
    expect(everything).not.toContain(B_NAME)
    expect(everything).not.toContain(B_ADDRESS)
  })

  test('Szenario 5: ein abgebrochener Download wird gemeldet, der nächste am selben Tag gelingt', async ({
    formatPipelinesPage: page,
  }) => {
    const before = await allowExactlyOneMore(LOADER)
    await openMyData(page)

    // the connection of the form submission breaks before an answer arrives
    await page.route('**/api/v1/me/self-disclosure/download', (route) =>
      route.abort('connectionreset'),
    )
    await page.getByRole('button', { name: DOWNLOAD_BUTTON }).click()
    await expect(
      page.getByText('Der Download ließ sich nicht starten. Bitte versuchen Sie es erneut.'),
    ).toBeVisible({ timeout: 20_000 })
    const afterAbort = await statusOf(LOADER)
    expect(afterAbort.completedToday).toBe(before.completedToday)
    expect(afterAbort.blocked).toBeNull()
    await expect(page.getByRole('button', { name: DOWNLOAD_BUTTON })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    )

    await page.unroute('**/api/v1/me/self-disclosure/download')
    const download = await captureDownload(page, () =>
      page.getByRole('button', { name: DOWNLOAD_BUTTON }).click(),
    )
    expect(download.entries.has('manifest.json')).toBe(true)
    await expect(page.getByText(COMPLETED_MESSAGE)).toBeVisible({ timeout: 20_000 })
    expect((await statusOf(LOADER)).completedToday).toBe(before.completedToday + 1)
  })

  test('Szenario 6: nach dem vollständigen Download ist ein weiterer am selben Tag abgewiesen', async ({
    formatPipelinesPage: page,
  }) => {
    await allowExactlyOneMore(LOADER)
    await openMyData(page)
    await captureDownload(page, () => page.getByRole('button', { name: DOWNLOAD_BUTTON }).click())
    await expect(
      page.getByText(
        /^Ihre Daten wurden vollständig heruntergeladen\. Der nächste Download ist ab \d{2}\.\d{2}\.\d{4}, \d{2}:\d{2} Uhr möglich\.$/,
      ),
    ).toBeVisible({ timeout: 20_000 })

    // the next visit names the reason and the moment, the button no longer starts anything
    await openMyData(page)
    await expect(
      page.getByText(
        /^Für heute ist kein weiterer Download möglich\. Der nächste ist ab \d{2}\.\d{2}\.\d{4}, \d{2}:\d{2} Uhr möglich\.$/,
      ),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: DOWNLOAD_BUTTON })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    const status = await statusOf(LOADER)
    expect(status.blocked).toBe('DAILY_LIMIT')
    expect(status.nextPossibleAt).not.toBeNull()

    // the server refuses the ticket as well, whatever the page offers
    const api = await apiAs(LOADER)
    try {
      const refused = await api.post(`${STATUS}/tickets`)
      expect(refused.status()).toBe(429)
      expect(((await refused.json()) as { code: string }).code).toBe('SELF_DISCLOSURE_DAILY_LIMIT')
    } finally {
      await api.dispose()
    }
  })

  test('Szenario 7: nach dem Export hat die Person eine Benachrichtigung zur Selbstauskunft', async ({
    formatPipelinesPage: page,
  }) => {
    await allowExactlyOneMore(LOADER)
    const before = await startedNotifications(LOADER)
    await openMyData(page)
    await captureDownload(page, () => page.getByRole('button', { name: DOWNLOAD_BUTTON }).click())
    await expect(page.getByText(COMPLETED_MESSAGE)).toBeVisible({ timeout: 20_000 })

    expect(await startedNotifications(LOADER)).toBe(before + 1)

    await page.reload()
    await page.getByRole('button', { name: /^Benachrichtigungen,/ }).click()
    const menu = page.getByRole('menu')
    await expect(
      menu.getByRole('menuitem', { name: /Selbstauskunft gestartet/ }).first(),
    ).toBeVisible()
    await expect(
      menu
        .getByText(/wurde eine Selbstauskunft Ihrer Daten gestartet\. Falls Sie das nicht waren/)
        .first(),
    ).toBeVisible()
  })

  test('Szenario 8: abgeschaltet zeigt „Meine Daten“ den Hinweis statt des Knopfs', async ({
    authenticatedPage: admin,
    regularUserPage: page,
  }) => {
    await updateSettings({ enabled: true })
    try {
      await admin.goto('/admin/privacy')
      const section = admin.getByRole('region', { name: 'Selbstauskunft' })
      const toggle = section.getByRole('switch', { name: 'Selbstauskunft anbieten' })
      await expect(toggle).toBeChecked({ timeout: 20_000 })
      await toggle.click()
      await section.getByRole('button', { name: 'Speichern' }).click()
      const confirm = admin.getByRole('dialog').filter({ hasText: 'Selbstauskunft abschalten?' })
      await confirm.getByRole('button', { name: 'Abschalten' }).click()
      await expect(admin.getByText('Die Selbstauskunft ist abgeschaltet.')).toBeVisible()

      await openMyData(page)
      await expect(
        page.getByText(/^Die Selbstauskunft ist in dieser Installation derzeit abgeschaltet\./),
      ).toBeVisible()
      await expect(page.getByRole('button', { name: DOWNLOAD_BUTTON })).toHaveCount(0)

      // switched on again in the administration, the button is back
      await admin.reload()
      await expect(toggle).not.toBeChecked({ timeout: 20_000 })
      await toggle.click()
      await section.getByRole('button', { name: 'Speichern' }).click()
      await expect(
        admin.getByText('Die Einstellungen der Selbstauskunft sind gespeichert.'),
      ).toBeVisible()
      await openMyData(page)
      await expect(page.getByRole('button', { name: DOWNLOAD_BUTTON })).toBeVisible()
    } finally {
      await updateSettings({ enabled: true })
    }
  })
})
