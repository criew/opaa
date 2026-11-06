import { readFileSync } from 'node:fs'
import type { APIRequestContext, Page } from '@playwright/test'
import { expect, test } from '../fixtures/auth'
import { indexedDocumentStates } from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries, uploadDocument } from '../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../fixtures/spaces'
import {
  askAboutScan,
  expectFramedPassage,
  expectRecognizedEvidence,
  openFramedScan,
  SCAN_FILE_NAME,
  SCAN_PATH,
  SCAN_QUESTIONS,
  setTextRecognition,
  testTextRecognition,
} from '../fixtures/textRecognition'

/**
 * Text recognition of scanned PDFs (ADR-0069, #3606) through the whole stack, with ocr-stub as
 * docling-serve: it replays the DoclingDocument a real opaa-ocr run made of the scanned statute
 * (demo/seed/e2e-data/text-recognition), checks key and options, and answers anything else with one
 * line. The frame of each cited passage is compared with the place the passage was typeset at -
 * a table row, a paragraph and a passage on a page with /Rotate 90.
 *
 * The role is installation-wide state: off before the first scenario, off again after the last.
 * The scan uploaded while it is off is rejected and later picked up by the background catch-up,
 * which runs every five minutes - the last scenario waits for it.
 */

const STUB_BASE_URL = process.env.E2E_OCR_STUB_BASE_URL ?? 'http://localhost:18094'

interface DocumentRow {
  id: string
  fileName: string
  status: string
  errorMessage?: string | null
}

async function documentsOf(api: APIRequestContext, libraryId: string): Promise<DocumentRow[]> {
  const response = await api.get(`/api/v1/libraries/${libraryId}/documents?size=20`)
  expect(response.ok()).toBe(true)
  return ((await response.json()) as { items: DocumentRow[] }).items
}

async function stubConversions(): Promise<{ scan: number; other: number; refused: number }> {
  const response = await fetch(`${STUB_BASE_URL}/control/conversions`)
  return (await response.json()) as { scan: number; other: number; refused: number }
}

async function uploadScanInUi(page: Page, libraryId: string): Promise<void> {
  await page.goto(`/libraries/${libraryId}`)
  await page.getByLabel('Dateien auswählen').setInputFiles(SCAN_PATH)
  await expect(page.getByText(SCAN_FILE_NAME, { exact: true })).toBeVisible()
  // A conversion of the stand-in takes milliseconds; a background catch-up holding the one slot
  // of this instance (max-concurrent 1) can leave the upload to the next pass.
  await expect(indexedDocumentStates(page)).toHaveCount(1, { timeout: 360_000 })
}

test.describe.serial('Texterkennung gescannter PDF-Dokumente', () => {
  const createdLibraryIds = cleanupLibraries()
  const suffix = `${Date.now()}`
  const libraryName = `E2E-Scan-${suffix}`
  let libraryId = ''
  let lateLibraryId = ''

  test.beforeAll(async () => {
    await setTextRecognition(false)
  })

  test.afterAll(async () => {
    await setTextRecognition(false)
  })

  test('5. Ausgeschaltet: ein Scan wird als „kein extrahierbarer Text“ abgewiesen', async () => {
    lateLibraryId = await createKnowledgeLibraryViaApi('dev-admin', { name: `E2E-Scan-spaet-${suffix}` })
    createdLibraryIds.push(lateLibraryId)
    const api = await apiAs('dev-admin')
    try {
      await uploadDocument(api, lateLibraryId, {
        name: SCAN_FILE_NAME,
        mimeType: 'application/pdf',
        buffer: readFileSync(SCAN_PATH),
      })
      let documents: DocumentRow[] = []
      await expect
        .poll(async () => (documents = await documentsOf(api, lateLibraryId)).map((d) => d.status).join(','), {
          timeout: 30_000,
        })
        .toBe('FAILED')
      expect(documents[0].errorMessage).toContain('Enthält keinen extrahierbaren Text')
    } finally {
      await api.dispose()
    }
    expect((await stubConversions()).scan).toBe(0)
  })

  test('1. Einrichten: Verbindungstest erfolgreich, Rolle eingeschaltet', async ({
    authenticatedPage: page,
  }) => {
    const result = await testTextRecognition()
    expect(result, result.message).toMatchObject({ success: true, outcome: 'READY' })
    await setTextRecognition(true)
    // The scan rejected while the role was off now waits for the catch-up.
    await page.goto(`/libraries/${lateLibraryId}`)
    await expect(page.getByTestId('text-recognition-notice')).toContainText(
      '1 Scan wartet auf die Texterkennung',
    )
  })

  test('2. Hochladen: der Scan wird per Texterkennung indiziert', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(420_000)
    libraryId = await createKnowledgeLibraryViaApi('dev-admin', { name: libraryName })
    createdLibraryIds.push(libraryId)
    await assignToDefaultSpace('dev-admin', 'KNOWLEDGE_LIBRARY', libraryId)
    await uploadScanInUi(page, libraryId)
    const conversions = await stubConversions()
    expect(conversions.scan).toBeGreaterThanOrEqual(1)
    expect(conversions.refused).toBe(0)
  })

  for (const [index, scenario] of SCAN_QUESTIONS.entries()) {
    test(`3./4. Frage und Fundstelle (${index + 1}): ${scenario.label}`, async ({
      authenticatedPage: page,
    }) => {
      test.setTimeout(90_000)
      const drawer = await askAboutScan(page, libraryName, scenario.question)
      const row = await expectRecognizedEvidence(drawer)
      const viewer = await openFramedScan(page, row, scenario.expected.page)
      await expectFramedPassage(viewer, scenario.expected, scenario.recognized)
    })
  }

  test('6. Nachholen: der vorher abgewiesene Scan wird nach dem Einschalten indiziert', async () => {
    // The catch-up runs every five minutes after the start of the backend (TextRecognitionBacklog).
    test.setTimeout(480_000)
    const api = await apiAs('dev-admin')
    try {
      await expect
        .poll(async () => (await documentsOf(api, lateLibraryId)).map((d) => d.status).join(','), {
          timeout: 420_000,
          intervals: [5_000],
        })
        .toBe('INDEXED')
    } finally {
      await api.dispose()
    }
  })
})
