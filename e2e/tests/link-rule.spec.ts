import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Page, TestInfo } from '@playwright/test'
import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  clearSearchScope,
  createLibraryWithDocument,
  expectCitedSource,
  startFreshChat,
} from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import { assignLibraryToDefaultSpace } from '../fixtures/spaces'

// A document with a visible instruction to append a link carrying content (Epic #3008); the
// ai-stub follows it on the trigger word E2E-BEFOLGE-DOKUMENT, as a model without defence would.
const PREPARED_DOCUMENT_NAME = 'praepariertes-merkblatt.txt'
const PREPARED_DOCUMENT_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
  PREPARED_DOCUMENT_NAME,
)

// The fixed answer parts of ai-stub/server.mjs, "Fixed answer parts for the link rule".
const ALLOWED_ORIGIN = 'https://intranet.e2e.example'
const ALLOWED_LINK = `${ALLOWED_ORIGIN}/seite`
const FOREIGN_LINK = 'https://fremd.e2e.example/abruf'
const IMAGE_ADDRESS = 'https://bild.e2e.example/diagramm.png'
const EXFILTRATION_LINK = 'https://abfluss.e2e.example/abruf?d=ZUSAMMENFASSUNG'

const runId = Date.now()

function uniqueId(testInfo: TestInfo): string {
  return `${runId}-${testInfo.retry}`
}

/** The list of allowed link targets right now, as the system administration sees it. */
async function allowedOrigins(): Promise<string[]> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.get('/api/v1/link-targets')
    expect(response.status(), await response.text()).toBe(200)
    return ((await response.json()) as { allowedOrigins: string[] }).allowedOrigins
  } finally {
    await api.dispose()
  }
}

async function replaceAllowedOrigins(origins: string[]): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.put('/api/v1/system/link-targets', {
      data: { allowedOrigins: origins },
    })
    expect(response.status(), await response.text()).toBe(200)
  } finally {
    await api.dispose()
  }
}

/** The answer text of the chat, where the Markdown of the newest answer is rendered. */
function answers(page: Page) {
  return page.getByRole('main')
}

/**
 * Covers test(e2e) #3051 (Epic #3008): links in an answer are clickable only to the own origin and
 * to the allowed link targets, images are never loaded, the administration of the targets is the
 * system administration's, and a link a prepared document asks for stays text. The ai-stub's
 * trigger words fix every answer, so each scenario is deterministic.
 */
test.describe.serial('Linkregel und präparierte Dokumente in Antworten (#3051)', () => {
  const createdLibraryIds = cleanupLibraries()
  let originsBefore: string[] = []

  test.beforeAll(async () => {
    originsBefore = await allowedOrigins()
  })

  test.afterAll(async () => {
    await replaceAllowedOrigins(originsBefore)
  })

  test('1. Ein erlaubtes Linkziel ist klickbar, ein anderes erscheint als Text mit Adresse', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    await page.goto('/admin/connections/settings')
    await expect(page.getByText('Erlaubte Linkziele').first()).toBeVisible()
    await page.getByLabel('Neues Linkziel').fill(ALLOWED_ORIGIN)
    await page.getByRole('button', { name: 'Hinzufügen' }).click()
    await expect(
      page.getByRole('button', { name: `Linkziel ${ALLOWED_ORIGIN} entfernen` }),
    ).toBeVisible()

    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, `Welche Links gibt es? E2E-ANTWORT-LINKS (${uniqueId(testInfo)})`)

    const allowed = answers(page).getByRole('link', { name: 'Intranet' })
    await expect(allowed).toBeVisible({ timeout: 15_000 })
    await expect(allowed).toHaveAttribute('href', ALLOWED_LINK)
    await expect(answers(page).getByTestId('blocked-link')).toHaveText(
      `Fremdseite (${FOREIGN_LINK})`,
    )
    await expect(answers(page).locator(`a[href="${FOREIGN_LINK}"]`)).toHaveCount(0)
  })

  test('2. Ein Bild in der Antwort wird nicht geladen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const imageRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().startsWith('https://bild.e2e.example/')) imageRequests.push(request.url())
    })

    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, `Zeig das Diagramm. E2E-ANTWORT-BILD (${uniqueId(testInfo)})`)

    await expect(answers(page).getByTestId('blocked-image')).toHaveText(
      `[Bild: Diagramm] (${IMAGE_ADDRESS})`,
      { timeout: 15_000 },
    )
    await expect(answers(page).locator(`img[src="${IMAGE_ADDRESS}"]`)).toHaveCount(0)
    // Evaluated once the page has settled after the answer, not in the moment it appears.
    await page.waitForLoadState('networkidle')
    expect(imageRequests).toEqual([])
  })

  test('3. Ohne Administrationsrolle ist die Verwaltung der Linkziele nicht erreichbar', async ({
    regularUserPage: page,
  }) => {
    await page.goto('/admin/connections/settings')

    // A positive signal first: absent sections alone would also pass on a page still loading.
    await expect(
      page.getByText('Für Ihr Konto ist diese Seite nicht freigegeben.', { exact: false }),
    ).toBeVisible()
    await expect(page.getByLabel('Neues Linkziel')).toHaveCount(0)
    await expect(page.getByRole('list', { name: 'Erlaubte Linkziele' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Linkziel .* entfernen$/ })).toHaveCount(0)

    // The interface hiding the section is no proof of the role check; the API refuses as well.
    const api = await apiAs('dev-user')
    try {
      const response = await api.put('/api/v1/system/link-targets', {
        data: { allowedOrigins: ['https://fremd.e2e.example'] },
      })
      expect(response.status(), await response.text()).toBe(403)
    } finally {
      await api.dispose()
    }
  })

  test('4. Ein Link, den ein präpariertes Dokument verlangt, bleibt Text; der Beleg zeigt das Dokument', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const libraryName = `E2E-Linkregel-${uniqueId(testInfo)}`
    await createLibraryWithDocument(page, libraryName, PREPARED_DOCUMENT_PATH, PREPARED_DOCUMENT_NAME)
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))
    await assignLibraryToDefaultSpace('dev-admin', libraryName)

    await startFreshChat(page)
    const input = page.getByPlaceholder('Nachricht eingeben …')
    await input.fill(`@${libraryName}`)
    await page.getByRole('option', { name: libraryName }).click()
    await askQuestion(
      page,
      `Wann ist der Antrag zu stellen? E2E-BEFOLGE-DOKUMENT (${uniqueId(testInfo)})`,
    )

    await expect(answers(page).getByTestId('blocked-link')).toHaveText(
      `Antragsformular (${EXFILTRATION_LINK})`,
      { timeout: 15_000 },
    )
    await expect(answers(page).locator('a[href^="https://abfluss.e2e.example"]')).toHaveCount(0)
    await expectCitedSource(page, PREPARED_DOCUMENT_NAME)
  })
})
