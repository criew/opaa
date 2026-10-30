import { PDFDocument, StandardFonts } from 'pdf-lib'
import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { askQuestion, startFreshChat } from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries } from '../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../fixtures/spaces'
import type { Download, Locator, Page } from '@playwright/test'

/**
 * „Dokument öffnen“ and „Herunterladen“ behind the evidence drawer, through the real stack: the
 * PDF viewer on the cited page with the passage marked, the Textfassung of a format the browser
 * cannot show with the cited passage and its outline, and the original as a download. A person
 * without read access gets neither the original nor the Textfassung over the API.
 *
 * One library with a small three-page PDF and a small HTML document, both built in memory, is
 * created over the API as dev-admin and assigned to dev-admin's default space; the question
 * references it by @, so both documents are cited (ai-stub gives every chunk the same embedding).
 */

const ADMIN = 'dev-admin'
const OUTSIDER = 'dev-outsider'
const PDF_PAGES = 3

interface SeededDocument {
  id: string
  fileName: string
}

async function buildPdf(): Promise<Buffer> {
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  for (let pageNumber = 1; pageNumber <= PDF_PAGES; pageNumber++) {
    const page = pdf.addPage([595, 842])
    for (let line = 0; line < 30; line++) {
      page.drawText(
        `Seite ${pageNumber}, Absatz ${line + 1}: Die Dienstanweisung regelt die Bearbeitung.`,
        { x: 50, y: 790 - line * 24, size: 11, font },
      )
    }
  }
  return Buffer.from(await pdf.save())
}

function buildHtml(): Buffer {
  const sections = ['Geltungsbereich', 'Zuständigkeit', 'Fristen']
    .map(
      (title, index) =>
        `<h2>${title}</h2>\n` +
        Array.from(
          { length: 12 },
          (_, line) =>
            `<p>Abschnitt ${index + 1}, Satz ${line + 1}: Diese Regel zum Thema ${title} gilt für alle Stellen der Verwaltung.</p>`,
        ).join('\n'),
    )
    .join('\n')
  return Buffer.from(
    `<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Merkblatt</title></head>` +
      `<body><h1>Merkblatt Fundstellen</h1>\n${sections}</body></html>`,
    'utf-8',
  )
}

async function seedLibrary(
  suffix: string,
  createdIds: string[],
): Promise<{ name: string; pdf: SeededDocument; html: SeededDocument }> {
  const name = `E2E-Fundstellen-${suffix}`
  const id = await createKnowledgeLibraryViaApi(ADMIN, { name })
  createdIds.push(id)
  const files = [
    { name: `fundstellen-${suffix}.pdf`, mimeType: 'application/pdf', buffer: await buildPdf() },
    { name: `fundstellen-${suffix}.html`, mimeType: 'text/html', buffer: buildHtml() },
  ]
  const api = await apiAs(ADMIN)
  try {
    for (const file of files) {
      const uploaded = await api.post(`/api/v1/libraries/${id}/documents`, {
        multipart: { file },
      })
      expect(uploaded.ok(), await uploaded.text()).toBe(true)
    }
    let documents: Array<{ id: string; fileName: string; status: string }> = []
    await expect
      .poll(
        async () => {
          const response = await api.get(`/api/v1/libraries/${id}/documents?size=10`)
          documents = ((await response.json()) as { items: typeof documents }).items
          return documents.map((document) => document.status).join(',')
        },
        { timeout: 30_000 },
      )
      .toBe('INDEXED,INDEXED')
    await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', id)
    const byName = (fileName: string): SeededDocument => {
      const document = documents.find((candidate) => candidate.fileName === fileName)
      expect(document, `${fileName} fehlt in der Bibliothek`).toBeDefined()
      return { id: document!.id, fileName }
    }
    return { name, pdf: byName(files[0].name), html: byName(files[1].name) }
  } finally {
    await api.dispose()
  }
}

/** Asks one question scoped to `libraryName` and opens the drawer of its answer. */
async function openEvidenceOfAnswer(page: Page, libraryName: string): Promise<Locator> {
  await startFreshChat(page)
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
  await askQuestion(page, `Was regelt die Dienstanweisung? (${Date.now()})`)
  const showEvidence = page.getByRole('button', { name: 'Belege anzeigen' })
  await expect(showEvidence).toBeVisible({ timeout: 15_000 })
  await showEvidence.click()
  const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
  await expect(drawer).toBeVisible()
  return drawer
}

function evidenceRow(drawer: Locator, fileName: string): Locator {
  return drawer.getByTestId('evidence-doc').filter({ hasText: fileName })
}

async function expectDownloadOf(download: Download, fileName: string): Promise<void> {
  expect(download.suggestedFilename()).toBe(fileName)
  expect(await download.failure()).toBeNull()
}

test.describe.serial('Fundstellen-Anzeige und Original', () => {
  const createdLibraryIds = cleanupLibraries()
  let library: Awaited<ReturnType<typeof seedLibrary>>

  test.beforeAll(async () => {
    test.setTimeout(90_000)
    library = await seedLibrary(`${Date.now()}`, createdLibraryIds)
  })

  test('1. PDF: Fundseite mit Markierung, Blättern, Original herunterladen', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000)
    const drawer = await openEvidenceOfAnswer(page, library.name)
    const row = evidenceRow(drawer, library.pdf.fileName)
    await expect(row).toHaveAttribute('data-cited', 'true')
    await row.getByRole('button', { name: 'Dokument öffnen' }).click()

    const viewer = page.getByRole('dialog', { name: library.pdf.fileName })
    await expect(viewer).toBeVisible()
    await expect(
      viewer.getByRole('status').filter({ hasText: /^Fundstelle auf Seite \d markiert\.$/ }),
    ).toBeAttached({
      timeout: 15_000,
    })
    const markedPage = Number(
      /Seite (\d)/.exec(
        (await viewer
          .getByRole('status')
          .filter({ hasText: /^Fundstelle auf Seite/ })
          .textContent()) ?? '',
      )?.[1],
    )
    await expect(viewer.getByText(`Seite ${markedPage} von ${PDF_PAGES}`)).toBeVisible()
    await expect(viewer.locator('.textLayer .passage').first()).toBeVisible()

    const pages = viewer.getByRole('region', { name: 'Seiten' })
    await pages.evaluate((element) => {
      const container = element as unknown as { scrollTop: number; scrollHeight: number }
      container.scrollTop = container.scrollHeight
    })
    await expect(viewer.getByText(`Seite ${PDF_PAGES} von ${PDF_PAGES}`)).toBeVisible()

    await expectNoSeriousA11yViolations(page, 'PDF-Anzeige an der Fundstelle (hell)')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expectNoSeriousA11yViolations(page, 'PDF-Anzeige an der Fundstelle (dunkel)')
    await page.emulateMedia({ colorScheme: 'light' })

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      viewer.getByRole('button', { name: 'Original herunterladen' }).click(),
    ])
    await expectDownloadOf(download, library.pdf.fileName)
    await page.keyboard.press('Escape')
    await expect(viewer).toBeHidden()
  })

  test('2. Textfassung: Fundstelle, Sprung über die Gliederung, Herunterladen im Belegfenster', async ({
    authenticatedPage: page,
  }) => {
    const drawer = await openEvidenceOfAnswer(page, library.name)
    const row = evidenceRow(drawer, library.html.fileName)
    await expect(row).toHaveAttribute('data-cited', 'true')

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      row.getByRole('button', { name: 'Herunterladen' }).click(),
    ])
    await expectDownloadOf(download, library.html.fileName)

    await row.getByRole('button', { name: 'Dokument öffnen' }).click()
    const textVersion = page.getByRole('dialog', { name: new RegExp(library.html.fileName) })
    await expect(textVersion).toBeVisible()
    const passage = textVersion.getByRole('region', { name: 'Fundstelle' })
    await expect(passage).toBeVisible()
    await expect(passage).toBeInViewport()

    const outline = textVersion.getByRole('navigation', { name: 'Gliederung' })
    await expect(outline.getByRole('button', { name: 'Fristen' })).toBeVisible()
    await outline.getByRole('button', { name: 'Fristen' }).click()
    await expect(textVersion.getByRole('heading', { name: 'Fristen' })).toBeInViewport()

    await expectNoSeriousA11yViolations(page, 'Textfassung an der Fundstelle (hell)')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expectNoSeriousA11yViolations(page, 'Textfassung an der Fundstelle (dunkel)')
  })

  test('3. Ohne Leserecht weder Original noch Textfassung, auch mit bekannter Kennung', async () => {
    const api = await apiAs(OUTSIDER)
    try {
      for (const document of [library.pdf, library.html]) {
        const content = await api.get(`/api/v1/documents/${document.id}/content`)
        expect(content.status(), `Original von ${document.fileName}`).toBe(404)
        const text = await api.get(`/api/v1/documents/${document.id}/text`)
        expect(text.status(), `Textfassung von ${document.fileName}`).toBe(404)
      }
    } finally {
      await api.dispose()
    }
    // The owner reaches both, so the 404 above is the permission check and not a wrong address.
    const owner = await apiAs(ADMIN)
    try {
      expect((await owner.get(`/api/v1/documents/${library.pdf.id}/content`)).status()).toBe(200)
      expect((await owner.get(`/api/v1/documents/${library.html.id}/text`)).status()).toBe(200)
    } finally {
      await owner.dispose()
    }
  })
})
