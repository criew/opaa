import type { Locator, Page } from '@playwright/test'
import { expect, test } from '../../fixtures/auth'
import { indexedDocumentStates } from '../../fixtures/chat'
import { apiAs } from '../../fixtures/externalAccess'
import { cleanupLibraries } from '../../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../../fixtures/spaces'
import {
  askAboutScan,
  expectFramedPassage,
  expectRecognizedEvidence,
  framesOnPage,
  expectFramedStatus,
  type PageBox,
  SCAN_FILE_NAME,
  SCAN_PATH,
  SCAN_QUESTIONS,
  setTextRecognition,
  setUpTextRecognitionInUi,
  textRecognitionService,
} from '../../fixtures/textRecognition'

/**
 * Acceptance run of text recognition against the real opaa-ocr (docling-serve with
 * RapidOCR), recorded as one video: the role is set up and tested, the scanned statute is uploaded
 * and read, then three questions - a row of the fee table, a paragraph of § 13 and a passage on a
 * page stored sideways with /Rotate 90 - each open the original with the passage framed and the
 * recognised text beside it. The frame is checked against the place the passage was typeset at,
 * with the tolerance the real recognition needs. Calm pace and short pauses, so the video can be
 * followed; a pointer marks every click.
 */

const PAUSE_MS = 1_800
const TYPING_DELAY_MS = 35

/** A line in the job log, so a CI run shows what happened without its artifacts. */
function log(message: string): void {
  console.log(`[abnahme ${new Date().toISOString()}] ${message}`)
}

async function documentState(libraryId: string): Promise<string> {
  const api = await apiAs('dev-admin')
  try {
    const response = await api.get(`/api/v1/libraries/${libraryId}/documents?size=5`)
    const items = ((await response.json()) as {
      items: Array<{ status: string; errorMessage?: string | null }>
    }).items
    return items.map((item) => `${item.status}${item.errorMessage ? ` (${item.errorMessage})` : ''}`).join(', ') || 'kein Dokument'
  } finally {
    await api.dispose()
  }
}

/** Status line, current page and the frames of every rendered page, into the log. */
async function logViewer(viewer: Locator, expected: PageBox): Promise<void> {
  const status = await viewer.getByRole('status').allInnerTexts().catch(() => [])
  log(`Anzeige: Status ${JSON.stringify(status)}; erwartet ${JSON.stringify(expected)}`)
  for (let pageNumber = 1; pageNumber <= 5; pageNumber++) {
    const rendered = await viewer.locator(`.page[data-page-number="${pageNumber}"]`).count()
    if (rendered === 0) continue
    const frames = await framesOnPage(viewer, pageNumber).catch(() => [])
    if (frames.length > 0) {
      const rounded = frames.map((frame) =>
        Object.fromEntries(
          Object.entries(frame).map(([key, value]) => [key, Math.round(value * 1000) / 1000]),
        ),
      )
      log(`Anzeige: Rahmen Seite ${pageNumber}: ${JSON.stringify(rounded)}`)
    }
  }
  const text = await viewer
    .getByTestId('recognized-text')
    .innerText({ timeout: 2_000 })
    .catch(() => '(keine Seitenleiste)')
  log(`Anzeige: Erkannter Text: ${text.replace(/\s+/g, ' ').slice(0, 300)}`)
}

async function pause(page: Page, factor = 1): Promise<void> {
  await page.waitForTimeout(PAUSE_MS * factor)
}

/** A visible pointer and a ring on every click, since the recording shows no cursor. */
async function showPointer(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const doc = (globalThis as unknown as { document: any }).document
    const install = () => {
      if (doc.getElementById('e2e-pointer')) return
      const dot = doc.createElement('div')
      dot.id = 'e2e-pointer'
      dot.style.cssText =
        'position:fixed;z-index:2147483647;width:18px;height:18px;margin:-9px 0 0 -9px;' +
        'border-radius:50%;background:rgba(220,38,38,.55);border:2px solid #fff;' +
        'box-shadow:0 0 0 1px rgba(0,0,0,.4);pointer-events:none;left:-40px;top:-40px;' +
        'transition:left .12s linear,top .12s linear'
      doc.body.appendChild(dot)
      doc.addEventListener(
        'mousemove',
        (event: { clientX: number; clientY: number }) => {
          dot.style.left = `${event.clientX}px`
          dot.style.top = `${event.clientY}px`
        },
        true,
      )
      doc.addEventListener(
        'mousedown',
        (event: { clientX: number; clientY: number }) => {
          const ring = doc.createElement('div')
          ring.style.cssText =
            `position:fixed;z-index:2147483646;left:${event.clientX - 22}px;` +
            `top:${event.clientY - 22}px;width:44px;height:44px;border-radius:50%;` +
            'border:3px solid rgba(220,38,38,.85);pointer-events:none;' +
            'transition:transform .5s ease-out,opacity .5s ease-out'
          doc.body.appendChild(ring)
          setTimeout(() => {
            ring.style.transform = 'scale(1.6)'
            ring.style.opacity = '0'
          }, 20)
          setTimeout(() => ring.remove(), 700)
        },
        true,
      )
    }
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', install)
    else install()
  })
}

test.describe.serial('Abnahme Texterkennung mit opaa-ocr', () => {
  const createdLibraryIds = cleanupLibraries()
  // Without blanks: the @ mention of the chat input ends its query at a blank.
  const libraryName = `Bauamt-Satzungen-Scan-${Date.now() % 100_000}`
  let libraryId = ''

  test.afterAll(async () => {
    await setTextRecognition(false)
  })

  test('Texterkennung einrichten, Scan hochladen, fragen und Fundstellen prüfen', async ({
    authenticatedPage: page,
  }, testInfo) => {
    await showPointer(page)

    // 1. Einrichten: the shipped service, connection test with the shipped test page, switched on.
    log('Einrichten: Modelle → Texterkennung')
    await setUpTextRecognitionInUi(page, textRecognitionService(), TYPING_DELAY_MS)
    log('Einrichten: Verbindungstest in Ordnung, eingeschaltet')
    await pause(page)
    await testInfo.attach('szenario-1-einrichten', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    })

    // 2. Hochladen und finden: the scan goes into an upload library and is read by opaa-ocr.
    libraryId = await createKnowledgeLibraryViaApi('dev-admin', { name: libraryName })
    createdLibraryIds.push(libraryId)
    await assignToDefaultSpace('dev-admin', 'KNOWLEDGE_LIBRARY', libraryId)
    await page.goto(`/libraries/${libraryId}`)
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    await pause(page)
    await page.getByLabel('Dateien auswählen').setInputFiles(SCAN_PATH)
    await expect(page.getByText(SCAN_FILE_NAME, { exact: true })).toBeVisible()
    await pause(page)
    // Polled over the API as well, so the log shows how long the recognition takes.
    const started = Date.now()
    await expect
      .poll(
        async () => {
          const state = await documentState(libraryId)
          log(`Indexierung nach ${Math.round((Date.now() - started) / 1000)} s: ${state}`)
          return state
        },
        { timeout: 1_200_000, intervals: [15_000] },
      )
      .toMatch(/^(INDEXED|FAILED)/)
    await expect(indexedDocumentStates(page)).toHaveCount(1, { timeout: 30_000 })
    await pause(page)

    // 3. and 4. Fragen, Beleg, Fundstelle - for each of the three places of the scan. A place that
    // fails does not end the recording: the others still run, and the test fails at the end.
    const failures: string[] = []
    for (const scenario of SCAN_QUESTIONS) {
      await test.step(scenario.label, async () => {
        let viewer: Locator | null = null
        try {
          log(`${scenario.label}: Frage stellen`)
          const drawer = await askAboutScan(page, libraryName, scenario.question, TYPING_DELAY_MS)
          log(`${scenario.label}: Belegfenster offen`)
          const row = await expectRecognizedEvidence(drawer)
          log(`${scenario.label}: Beleg ${await row.innerText()}`)
          await pause(page)
          await row.getByRole('button', { name: 'Dokument öffnen' }).click()
          viewer = page.getByRole('dialog', { name: SCAN_FILE_NAME })
          await expectFramedStatus(viewer, scenario.expected.page)
          await logViewer(viewer, scenario.expected)
          await expectFramedPassage(viewer, scenario.expected, scenario.recognized)
          log(`${scenario.label}: Rahmen an der erwarteten Stelle`)
          await pause(page, 2)
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error)
          log(`${scenario.label}: FEHLER ${message.split('\n')[0]}`)
          if (viewer) await logViewer(viewer, scenario.expected).catch(() => {})
          failures.push(`${scenario.label}: ${message}`)
          await pause(page, 2)
        } finally {
          await testInfo.attach(`fundstelle-seite-${scenario.expected.page}`, {
            body: await page.screenshot(),
            contentType: 'image/png',
          })
        }
      })
    }
    expect(failures, failures.join('\n\n')).toEqual([])
  })
})
