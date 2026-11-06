import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Locator, Page } from '@playwright/test'
import { expect } from './auth'
import { askQuestion, startFreshChat } from './chat'
import { apiAs } from './externalAccess'

/**
 * Building blocks of the text recognition scenarios (ADR-0069): the scanned statute, the model role
 * over the API, a question that cites one passage of the scan, and where the viewer draws its frame.
 * Used by tests/text-recognition.spec.ts against the docling-serve stand-in and by
 * ocr/tests/abnahme.spec.ts against the real opaa-ocr.
 */

const FIXTURE_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'text-recognition',
)

/** The image-only PDF, five pages, page 5 stored sideways with /Rotate 90 (make_scan.py). */
export const SCAN_PATH = join(FIXTURE_DIR, 'satzung-scan.pdf')
export const SCAN_FILE_NAME = 'satzung-scan.pdf'
export const SCAN_PAGES = 5

/** A frame or an expected place, as fractions of the shown page, origin top left. */
export interface PageBox {
  page: number
  x: number
  y: number
  width: number
  height: number
}

interface ScanLayout {
  zweitausfertigung: PageBox
  zweitausfertigungAmount: string
  freistellungsbescheid: PageBox
  kassenzeichen: PageBox
  rotatedPage: number
}

/** Where the passages lie on the original, from the typesetting, not from any recognition. */
export const SCAN_LAYOUT = JSON.parse(
  readFileSync(join(FIXTURE_DIR, 'satzung-scan.layout.json'), 'utf8'),
) as ScanLayout

/** The questions of the scenarios: the quoted term makes ai-stub cite only passages with it. */
export const SCAN_QUESTIONS = [
  {
    label: 'Zeile der Gebührentabelle',
    question: 'Wie hoch ist die Gebühr für eine „Zweitausfertigung“ der Baugenehmigung?',
    expected: SCAN_LAYOUT.zweitausfertigung,
    recognized: [/Zweitausfertigung/, /44,00/],
  },
  {
    label: 'Absatz eines Paragrafen',
    question: 'Welchen Nachweis verlangt § 13 – reicht der „Freistellungsbescheid“ des Finanzamts?',
    expected: SCAN_LAYOUT.freistellungsbescheid,
    recognized: [/Freistellungsbescheid/, /Gemeinnützigkeit/],
  },
  {
    label: 'Seite mit /Rotate 90',
    question: 'Was muss bei der Zahlung an die Stadtkasse als „Kassenzeichen“ angegeben werden?',
    expected: SCAN_LAYOUT.kassenzeichen,
    recognized: [/Kassenzeichen/],
  },
] as const

export interface TextRecognitionService {
  baseUrl: string
  apiKey: string
}

/**
 * The service of this run: the stand-in of the e2e stack, or opaa-ocr in the target "ocr"; the
 * key is random per run (scripts/run-e2e.mjs).
 */
export function textRecognitionService(): TextRecognitionService {
  return {
    baseUrl: process.env.E2E_OCR_BASE_URL ?? 'http://ocr-stub:5001',
    apiKey: process.env.E2E_OCR_API_KEY ?? '',
  }
}

/** Sets the text recognition role as system administration (docling-serve). */
export async function setTextRecognition(
  enabled: boolean,
  service: TextRecognitionService = textRecognitionService(),
): Promise<void> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.put('/api/v1/admin/models/text-recognition', {
      data: {
        enabled,
        connectionType: 'DOCLING_SERVE',
        baseUrl: service.baseUrl,
        apiKey: service.apiKey,
      },
    })
    expect(response.ok(), `PUT text-recognition: ${response.status()}`).toBe(true)
    expect(((await response.json()) as { enabled: boolean }).enabled).toBe(enabled)
  } finally {
    await api.dispose()
  }
}

/** The connection test of the role, with the shipped test page; its outcome. */
export async function testTextRecognition(
  service: TextRecognitionService = textRecognitionService(),
): Promise<{ success: boolean; outcome: string; message: string }> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.post('/api/v1/admin/models/text-recognition/test', {
      data: { connectionType: 'DOCLING_SERVE', baseUrl: service.baseUrl, apiKey: service.apiKey },
      timeout: 600_000,
    })
    expect(response.ok(), `POST text-recognition/test: ${response.status()}`).toBe(true)
    return (await response.json()) as { success: boolean; outcome: string; message: string }
  } finally {
    await api.dispose()
  }
}

/**
 * Asks `question` scoped to `libraryName` in a fresh chat and opens the drawer of the answer.
 * `typingDelay` > 0 types key by key, for a recording a viewer can follow.
 */
export async function askAboutScan(
  page: Page,
  libraryName: string,
  question: string,
  typingDelay = 0,
): Promise<Locator> {
  await startFreshChat(page)
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  if (typingDelay > 0) {
    await input.pressSequentially(`@${libraryName}`, { delay: typingDelay })
  } else {
    await input.fill(`@${libraryName}`)
  }
  await page.getByRole('option', { name: libraryName }).click()
  if (typingDelay > 0) {
    await input.pressSequentially(question, { delay: typingDelay })
    await page.getByRole('button', { name: 'Senden' }).click()
  } else {
    await askQuestion(page, question)
  }
  const showEvidence = page.getByRole('button', { name: 'Belege anzeigen' })
  await expect(showEvidence).toBeVisible({ timeout: 60_000 })
  await showEvidence.click()
  const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
  await expect(drawer).toBeVisible()
  return drawer
}

/** The scan's row in the drawer: cited and marked as read by text recognition. */
export async function expectRecognizedEvidence(drawer: Locator): Promise<Locator> {
  const row = drawer.getByTestId('evidence-doc').filter({ hasText: SCAN_FILE_NAME })
  await expect(row).toHaveAttribute('data-cited', 'true')
  await expect(row.getByTestId('recognized-text-notice')).toContainText(
    'Maschinell erkannt, maßgeblich ist das Original',
  )
  return row
}

/** Opens the scan from its row and waits until the viewer reports the frame of `page`. */
export async function openFramedScan(page: Page, row: Locator, expectedPage: number): Promise<Locator> {
  await row.getByRole('button', { name: 'Dokument öffnen' }).click()
  const viewer = page.getByRole('dialog', { name: SCAN_FILE_NAME })
  await expect(viewer).toBeVisible()
  await expect(
    viewer.getByText(`Fundstelle auf Seite ${expectedPage} umrahmt.`, { exact: false }),
  ).toBeVisible({ timeout: 30_000 })
  return viewer
}

/** The frames drawn on one page of the viewer, as fractions of the page as shown. */
export async function framesOnPage(viewer: Locator, pageNumber: number): Promise<PageBox[]> {
  const pageDiv = viewer.locator(`.page[data-page-number="${pageNumber}"]`)
  const pageBox = await pageDiv.boundingBox()
  expect(pageBox, `Seite ${pageNumber} ist nicht gerendert`).not.toBeNull()
  const frames: PageBox[] = []
  for (const frame of await pageDiv.locator('.recognized-region').all()) {
    const box = await frame.boundingBox()
    if (!box) continue
    frames.push({
      page: pageNumber,
      x: (box.x - pageBox!.x) / pageBox!.width,
      y: (box.y - pageBox!.y) / pageBox!.height,
      width: box.width / pageBox!.width,
      height: box.height / pageBox!.height,
    })
  }
  return frames
}

function overlap(a0: number, a1: number, b0: number, b1: number): number {
  return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0))
}

/**
 * The frames sit at the expected place: one of them covers at least half the height of the
 * expected box and overlaps it horizontally, and together they stay a passage, not the page -
 * their union holds the expected box's centre within `tolerance` and is less than half a page high.
 */
export function expectFramesAt(frames: PageBox[], expected: PageBox, tolerance = 0.02): void {
  const describe = JSON.stringify({ expected, frames })
  expect(frames.length, `keine Rahmen auf Seite ${expected.page}: ${describe}`).toBeGreaterThan(0)
  const hit = frames.some(
    (frame) =>
      overlap(frame.y, frame.y + frame.height, expected.y, expected.y + expected.height) >=
        expected.height * 0.5 &&
      overlap(frame.x, frame.x + frame.width, expected.x, expected.x + expected.width) > 0,
  )
  expect(hit, `kein Rahmen über der erwarteten Stelle: ${describe}`).toBe(true)
  const top = Math.min(...frames.map((frame) => frame.y))
  const bottom = Math.max(...frames.map((frame) => frame.y + frame.height))
  const left = Math.min(...frames.map((frame) => frame.x))
  const right = Math.max(...frames.map((frame) => frame.x + frame.width))
  const centreX = expected.x + expected.width / 2
  const centreY = expected.y + expected.height / 2
  expect(
    centreX >= left - tolerance &&
      centreX <= right + tolerance &&
      centreY >= top - tolerance &&
      centreY <= bottom + tolerance,
    `Rahmen verfehlen die Mitte der erwarteten Stelle: ${describe}`,
  ).toBe(true)
  expect(bottom - top, `Rahmen umfassen fast die ganze Seite: ${describe}`).toBeLessThan(0.5)
}

/**
 * The frame of a cited passage, checked where the reader sees it: on the right page, in view, at
 * the place of the passage on the original, and the side panel "Erkannter Text" shows its text.
 */
export async function expectFramedPassage(
  viewer: Locator,
  expected: PageBox,
  recognized: readonly RegExp[],
): Promise<void> {
  await expect(viewer.getByText(`von ${SCAN_PAGES}`)).toBeVisible()
  const frame = viewer.locator(`.page[data-page-number="${expected.page}"] .recognized-region`)
  await expect(frame.first()).toBeInViewport()
  expectFramesAt(await framesOnPage(viewer, expected.page), expected)
  const panel = viewer.getByRole('complementary', { name: 'Erkannter Text' })
  await expect(panel).toBeVisible()
  for (const text of recognized) {
    await expect(panel.getByTestId('recognized-text')).toContainText(text)
  }
}
