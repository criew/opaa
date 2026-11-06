import type { Page } from '@playwright/test'
import { expect, test } from '../../fixtures/auth'
import { indexedDocumentStates } from '../../fixtures/chat'
import { cleanupLibraries } from '../../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../../fixtures/spaces'
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
} from '../../fixtures/textRecognition'

/**
 * Acceptance run of text recognition (#3606) against the real opaa-ocr (docling-serve with
 * RapidOCR), recorded as one video: the role is set up and tested, the scanned statute is uploaded
 * and read, then three questions - a row of the fee table, a paragraph of § 13 and a passage on a
 * page stored sideways with /Rotate 90 - each open the original with the passage framed and the
 * recognised text beside it. The frame is checked against the place the passage was typeset at,
 * with the tolerance the real recognition needs. Calm pace and short pauses, so the video can be
 * followed; a pointer marks every click.
 */

const PAUSE_MS = 1_800
const TYPING_DELAY_MS = 35

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
  const libraryName = `Satzungen Bauamt (Scans) ${Date.now() % 100_000}`
  let libraryId = ''

  test.afterAll(async () => {
    await setTextRecognition(false)
  })

  test('Texterkennung einrichten, Scan hochladen, fragen und Fundstellen prüfen', async ({
    authenticatedPage: page,
  }, testInfo) => {
    await showPointer(page)

    // 1. Einrichten: the shipped service, connection test with the shipped test page, switched on.
    const result = await testTextRecognition()
    expect(result, result.message).toMatchObject({ success: true, outcome: 'READY' })
    await setTextRecognition(true)
    await page.goto('/admin/search/overview')
    await expect(
      page.getByText('Die Texterkennung ist eingeschaltet, die Verbindung zum Dienst ist bereit.'),
    ).toBeVisible()
    await page
      .getByText('Die Texterkennung ist eingeschaltet, die Verbindung zum Dienst ist bereit.')
      .scrollIntoViewIfNeeded()
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
    await expect(indexedDocumentStates(page)).toHaveCount(1, { timeout: 1_200_000 })
    await pause(page)

    // 3. and 4. Fragen, Beleg, Fundstelle - for each of the three places of the scan. A place that
    // fails does not end the recording: the others still run, and the test fails at the end.
    const failures: string[] = []
    for (const scenario of SCAN_QUESTIONS) {
      await test.step(scenario.label, async () => {
        try {
        const drawer = await askAboutScan(page, libraryName, scenario.question, TYPING_DELAY_MS)
        const row = await expectRecognizedEvidence(drawer)
        await pause(page)
        const viewer = await openFramedScan(page, row, scenario.expected.page)
        await expectFramedPassage(viewer, scenario.expected, scenario.recognized)
        await pause(page, 2)
        } catch (error) {
          failures.push(`${scenario.label}: ${error instanceof Error ? error.message : error}`)
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
