import { crc32, deflateSync } from 'node:zlib'
import { randomBytes } from 'node:crypto'
import { PDFDocument, StandardFonts } from 'pdf-lib'
import { expect, test } from '../fixtures/auth'
import type { APIRequestContext, Page } from '@playwright/test'

// Nacharbeiten-Serie aus Epic #458 (#514, #516, #517, #519, siehe Issue #547): vier
// nutzersichtbare Verhaltensweisen, die die Suite bislang nicht abdeckte. Eigene Datei statt
// Erweiterung von knowledge-libraries.spec.ts (#424) - dort geht es um die volle
// Upload/Freigabe/Suche-Kette einer einzelnen Bibliothek, hier um vier voneinander unabhängige
// UI-Verhaltensweisen rund um Quellkonfiguration und Dokumentliste. Jedes Szenario legt sich seine
// eigene, wegwerfbare Bibliothek an - dasselbe Muster wie die Negativfälle in
// knowledge-libraries.spec.ts (siehe dort OWN_DOCUMENT_PATH's Kommentar), nur ohne den
// Freigabe-Aspekt, den dieses Issue ausdrücklich nicht abdeckt.
//
// Dateiname bewusst "knowledge-library-..." (Singular): sortiert alphabetisch nach
// "knowledge-libraries.spec.ts", dessen Szenario 2 sich sonst mit den hier angelegten,
// admin-lesbaren Wegwerfdokumenten in die Quere käme (siehe #424, ai-stub liefert für jede Anfrage
// denselben Embedding-Vektor) - jede Bibliothek dieser Datei räumt sich zusätzlich über
// test.afterAll selbst wieder ab (siehe cleanupLibraries unten), das ist also nur die zweite,
// unabhängige Absicherung.
const runId = Date.now()

// Mirrors frontend/src/services/devAuth.ts's DEV_USER_HEADER - not imported from there since e2e/
// is its own npm package with no dependency on frontend/src (see rss-feed-library.spec.ts's
// identical comment on the same constant).
const DEV_USER_HEADER = 'X-OPAA-Dev-User'

// Same waiting pattern as knowledge-libraries.spec.ts's gotoLibraries (kept module-local there,
// duplicated here rather than shared - see rss-feed-library.spec.ts's identical comment).
async function gotoLibraries(page: Page) {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().endsWith('/api/v1/libraries'),
    ),
    page.goto('/libraries'),
  ])
}

/** Reads the library id LibraryCreatePage navigated to after a successful "Bibliothek anlegen". */
function libraryIdFromCurrentUrl(page: Page): string {
  const match = page.url().match(/\/libraries\/([^/]+)$/)
  if (!match) {
    throw new Error(`Unexpected library detail URL after creation: ${page.url()}`)
  }
  return match[1]
}

/**
 * Deletes a library this file created for a scenario, together with any documents it holds -
 * registered per test.describe block (see cleanupLibraries below) rather than relying solely on
 * the filename-ordering trick above to keep other specs' shared, admin-readable corpus from
 * growing without bound across repeated local runs. Works for both library kinds this file
 * creates: an UPLOAD library rejects DELETE while it still holds documents (ADR-0018), so those
 * are removed first; a connector (HTTP_DIRECTORY) library never has any (no indexing run was ever
 * triggered against it here), so the listing call below simply returns nothing to delete.
 */
async function deleteLibraryCompletely(request: APIRequestContext, libraryId: string) {
  const documentsResponse = await request.get(`/api/v1/libraries/${libraryId}/documents?size=100`, {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  if (documentsResponse.ok()) {
    const body = (await documentsResponse.json()) as { items: Array<{ id: string }> }
    for (const document of body.items) {
      await request.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`, {
        headers: { [DEV_USER_HEADER]: 'dev-admin' },
      })
    }
  }
  const libraryResponse = await request.delete(`/api/v1/libraries/${libraryId}`, {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  expect(libraryResponse.ok()).toBe(true)
}

/**
 * Registers a test.describe-scoped cleanup: tests push the id of every library they create onto
 * the returned array, and test.afterAll deletes all of them (see deleteLibraryCompletely) once the
 * block's tests have finished - regardless of whether any of them failed, so a failed assertion
 * never leaves that scenario's own library behind for the next local run.
 */
function cleanupLibraries(): string[] {
  const createdLibraryIds: string[] = []
  test.afterAll(async ({ request }) => {
    for (const libraryId of createdLibraryIds) {
      await deleteLibraryCompletely(request, libraryId)
    }
  })
  return createdLibraryIds
}

/**
 * Builds a real, parseable PDF of roughly 2 MB, entirely in memory - not one of the suite's
 * committed fixtures under demo/seed/e2e-data/test-documents/ (see knowledge-libraries.spec.ts's
 * comment on TEST_DOCUMENT_PATH for why those live there and stay tiny): a 2 MB binary has no
 * business being checked into git for a single regression test.
 *
 * The extractable page text is a single short, real sentence - just enough to avoid
 * FileProcessingService#processUploadedFile's EmptyDocumentContentException (thrown when Tika
 * extracts no text at all), so the document stays a single-digit number of chunks. The ~2 MB of
 * bulk instead comes from an embedded image (pdf.embedPng) built from random pixel data: PDFBox's
 * plain text extraction never reads image content (that would need OCR, which this backend does
 * not run), so it never becomes part of the parsed/chunked/embedded text. An earlier version
 * padded the *page text* itself with ~2 MB of pseudo-random characters instead - technically
 * incompressible enough to survive pdf-lib's own Flate compression at the right file size, but
 * Tika dutifully extracted every byte of it as body text, producing roughly a thousand chunks and
 * turning this test's upload into a real embedding-load test rather than the nginx
 * client_max_body_size regression check it is meant to be (60s upload timeouts even without any
 * other load on the stack).
 *
 * The PNG is hand-built (raw IHDR/IDAT/IEND chunks via node:zlib's deflateSync/crc32, no PNG
 * encoder dependency) rather than filled with e.g. a solid color: real image compression /
 * pdf-lib's own re-encoding of the pixel data on embed would otherwise shrink solid or
 * low-entropy pixels down to a few hundred bytes, the same problem the page-text approach above
 * had.
 */
async function buildLargePdf(): Promise<Buffer> {
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  const page = pdf.addPage([595, 842])
  page.drawText('Testdokument fuer den Upload-Regressionstest (#547).', {
    x: 20,
    y: 800,
    size: 12,
    font,
  })
  const png = buildRandomPng(820, 820) // ~2 MB of near-incompressible random RGB pixel data
  const image = await pdf.embedPng(png)
  page.drawImage(image, { x: 0, y: 0, width: 100, height: 100 })
  const bytes = await pdf.save()
  return Buffer.from(bytes)
}

/** A minimal, valid, uncompressed (per-scanline) truecolor PNG filled with random pixel data. */
function buildRandomPng(width: number, height: number): Buffer {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const ihdrData = Buffer.concat([
    uint32BE(width),
    uint32BE(height),
    Buffer.from([8, 2, 0, 0, 0]), // 8-bit depth, color type 2 (truecolor RGB), default compression/filter/interlace
  ])
  const rowBytes = 1 + width * 3 // leading filter-type byte (0 = None) per scanline
  const raw = Buffer.alloc(rowBytes * height)
  for (let y = 0; y < height; y++) {
    const rowStart = y * rowBytes
    raw[rowStart] = 0
    randomBytes(width * 3).copy(raw, rowStart + 1)
  }
  return Buffer.concat([
    signature,
    pngChunk('IHDR', ihdrData),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

function pngChunk(type: string, data: Buffer): Buffer {
  const typeBuf = Buffer.from(type, 'ascii')
  return Buffer.concat([
    uint32BE(data.length),
    typeBuf,
    data,
    uint32BE(crc32(Buffer.concat([typeBuf, data]))),
  ])
}

function uint32BE(value: number): Buffer {
  const buf = Buffer.alloc(4)
  buf.writeUInt32BE(value >>> 0, 0)
  return buf
}

/**
 * Uploads `count` tiny, distinct, real text documents into `libraryId` directly via the documents
 * API (not the drag-and-drop widget) - the same "viele kleine Dokumente per API anlegen" shortcut
 * the issue itself suggests for reaching a second page of results without paying for dozens of UI
 * uploads. Mirrors rss-feed-library.spec.ts's fetchLibraryDocumentFileNames in using
 * page.request directly with the dev-admin header, and LibraryDocumentService#uploadDocument's
 * "file" multipart field name (frontend/src/services/api.ts's uploadDocument).
 */
async function uploadFillerDocuments(page: Page, libraryId: string, count: number) {
  for (let i = 1; i <= count; i++) {
    const name = `E2E-Fuelldokument-${runId}-${String(i).padStart(2, '0')}.txt`
    const response = await page.request.post(`/api/v1/libraries/${libraryId}/documents`, {
      headers: { [DEV_USER_HEADER]: 'dev-admin' },
      multipart: {
        file: {
          name,
          mimeType: 'text/plain',
          buffer: Buffer.from(`Inhalt des Fuelldokuments Nr. ${i} fuer Issue #547.`, 'utf-8'),
        },
      },
    })
    expect(response.ok()).toBe(true)
  }
}

// #519: an ~2 MB PDF upload through the real, containerized nginx (frontend/nginx.conf) rather
// than a mocked or dev-server request - client_max_body_size is the one setting no unit test can
// exercise, only a request that actually passes through that proxy.
test.describe('Upload > 1 MB durch den echten nginx (#519)', () => {
  const createdLibraryIds = cleanupLibraries()

  test('Ein ~2-MB-PDF wird hochgeladen und erfolgreich indiziert', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Upload-Limit-Bibliothek ${runId}`
    const fileName = `grosses-dokument-${runId}.pdf`
    const pdfBuffer = await buildLargePdf()
    // A hair under 2 MB is not enough to prove anything - old nginx's default is 1 MB, so this
    // only regresses client_max_body_size if the file is unambiguously past that old default too.
    expect(pdfBuffer.byteLength).toBeGreaterThan(1_500_000)

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(libraryName)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await Promise.all([
      // Negative lookahead: the wizard itself lives at /libraries/new, which the plain
      // one-segment pattern would match immediately.
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    await page.getByLabel('Dateien auswählen').setInputFiles({
      name: fileName,
      mimeType: 'application/pdf',
      buffer: pdfBuffer,
    })

    // Both assertions gate on the same round trip: uploadNewDocument's request only resolves once
    // the backend has fully parsed/chunked/embedded the file, and the row (filename + status)
    // only renders once that response comes back - there is no earlier moment at which the
    // filename alone would already be visible. Generous timeouts (not the 10s default from
    // playwright.config.ts's expect.timeout) purely for the extra network transfer time a ~2 MB
    // body needs over the suite's tiny committed fixtures - the document itself stays a
    // single-digit number of chunks (see buildLargePdf), so this is not an embedding-load wait.
    await expect(page.getByText(fileName)).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText('indiziert')).toBeVisible({ timeout: 30_000 })
  })
})

// #514: the connection test in CreateLibraryDialog, both directions.
//
// Happy path: HTTP_DIRECTORY against the suite's own "rss-feed" httpd service
// (docker-compose.e2e.yml, also used by #471), reachable from the backend container under the
// stack-internal hostname "rss-feed" - no dedicated fixture service needed just for this test.
// Not .../anlagen/ itself (used elsewhere in this file and in the #516 scenario below, where only
// creation/editing matters, never a parsed count): since #550, AutoindexCrawlerService#parseDirectory
// also understands the plain <pre> listing httpd:2.4-alpine's stock mod_autoindex config actually
// renders (no "IndexOptions HTMLTable" needed any more), so .../anlagen/ would work here too - but
// its exact rendering depends on the base image's autoindex defaults, which this suite does not
// control. .../webverzeichnis/ instead serves a hand-crafted static index.html mimicking the
// HTMLTable layout deliberately (see demo/seed/e2e-data/rss-feed/htdocs/webverzeichnis/index.html for
// why), with exactly one supported document and a count this test can assert on deterministically.
//
// Error path: a closed port on a hostname the backend container can resolve instantly via Docker's
// own embedded DNS ("ai-stub", already part of this stack) - not a hostname under the reserved
// ".invalid" TLD as a first attempt used: this sandbox's outbound DNS resolution to the public
// internet is unreliable/slow enough that SourceConnectionTestService's own 10s per-request timeout
// was repeatedly reached before an UnknownHostException ever surfaced, producing a timeout message
// instead of the intended "Host not found" one. A connection refused by a real, internally
// resolvable host needs no DNS round trip at all and fails deterministically fast.
test.describe('Verbindungstest im Anlage-Assistenten (#514)', () => {
  const createdLibraryIds = cleanupLibraries()

  test('Erreichbare Quelle zeigt einen Zaehlwert', async ({ authenticatedPage: page }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(`E2E Verbindungstest ${runId}`)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await page.getByLabel('Adresse (URL)').fill('http://rss-feed/webverzeichnis/')
    await page.getByRole('button', { name: 'Verbindung testen' }).click()

    const result = page.getByRole('alert').filter({ hasText: 'Webverzeichnis erreichbar' })
    await expect(result).toBeVisible({ timeout: 15_000 })
    await expect(result).toContainText(
      'Webverzeichnis erreichbar, 1 unterstütztes Dokument auf oberster Ebene gefunden.',
    )
    // Deliberately never clicked "Bibliothek anlegen" - this scenario only exercises the test
    // call itself, no library was created and there is nothing for cleanupLibraries to remove.
  })

  test('Nicht erreichbare Quelle zeigt eine deutsche Fehlermeldung, Anlegen bleibt moeglich', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Verbindungstest-Fehlerfall ${runId}`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(libraryName)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('radio', { name: /Webverzeichnis/ }).click()
    // "ai-stub" resolves instantly (it is part of this very stack) - nothing listens on port 9,
    // so the backend's HTTP client gets an immediate, DNS-independent connection refusal.
    await page.getByLabel('Adresse (URL)').fill('http://ai-stub:9/')
    await page.getByRole('button', { name: 'Verbindung testen' }).click()

    const result = page.getByRole('alert').filter({ hasText: 'abgelehnt' })
    await expect(result).toBeVisible({ timeout: 15_000 })
    await expect(result).toContainText('Die Verbindung wurde vom Server abgelehnt.')

    // The failed test must not block creation itself (#514 acceptance criteria) - the source
    // configuration is only probed, never required to succeed.
    await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))
  })
})

// #517: paging and keyword search over a library's document list, plus the list showing up at all
// for a non-UPLOAD (connector) library - LibraryDetailPage renders LibraryDocumentsSection
// whenever library details are loaded, regardless of sourceType; only the upload widget itself is
// gated to UPLOAD.
test.describe('Dokumentliste mit Paging und Suche (#517)', () => {
  const createdLibraryIds = cleanupLibraries()

  // DEFAULT_PAGE_SIZE in frontend/src/stores/documentStore.ts - not imported from there (e2e/ has
  // no dependency on frontend/src, see DEV_USER_HEADER's comment above for the same reasoning),
  // duplicated as a plain constant instead.
  const PAGE_SIZE = 20

  test('Mehrseitige Liste laesst sich blaettern und durchsuchen', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Paging-Bibliothek ${runId}`
    const searchTargetName = `suchtreffer-${runId}.txt`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(libraryName)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    const libraryId = libraryIdFromCurrentUrl(page)
    createdLibraryIds.push(libraryId)

    // PAGE_SIZE + 1 filler documents plus one distinctly named search target - one more document
    // than a single page holds, so the list is provably on more than one page (#517 acceptance
    // criteria: "mehr als eine Seite Dokumenten").
    await uploadFillerDocuments(page, libraryId, PAGE_SIZE + 1)
    const searchTargetResponse = await page.request.post(
      `/api/v1/libraries/${libraryId}/documents`,
      {
        headers: { [DEV_USER_HEADER]: 'dev-admin' },
        multipart: {
          file: {
            name: searchTargetName,
            mimeType: 'text/plain',
            buffer: Buffer.from('Eindeutiger Suchtreffer fuer Issue #547.', 'utf-8'),
          },
        },
      },
    )
    expect(searchTargetResponse.ok()).toBe(true)

    await page.reload()
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()

    // PAGE_SIZE + 2 documents in total -> two pages (PAGE_SIZE, then 2). Scoped by aria-label
    // (LibraryDetailPage's Pagination) rather than the bare "navigation" role - the app shell's own
    // persistent navigation is a second <nav> on every page and would otherwise make this locator
    // ambiguous.
    const pagination = page.getByRole('navigation', { name: 'Dokumentenliste blättern' })
    await expect(pagination).toBeVisible()
    const page1Item = pagination.getByRole('button', { name: 'page 1' })
    const page2Item = pagination.getByRole('button', { name: 'page 2' })
    await expect(page1Item).toBeVisible()
    await expect(page2Item).toBeVisible()

    // Blaettern: page 1 shows PAGE_SIZE rows. Sorted by fileName (LibraryController#listDocuments,
    // #517 code review finding 1) - uppercase "E2E-Fuelldokument-..." sorts before lowercase
    // "suchtreffer-...", so the search target lands on page 2 alongside the one filler document
    // that does not fit on page 1.
    const documentRows = page.getByText(/^E2E-Fuelldokument-/)
    await expect(documentRows).toHaveCount(PAGE_SIZE)
    await expect(page.getByText(searchTargetName)).toHaveCount(0)

    await page2Item.click()
    await expect(page.getByText(searchTargetName)).toBeVisible()

    // Suche: filtering down to the one uniquely named document collapses the list to a single
    // page and hides everything else.
    await page.getByLabel('Dokumente durchsuchen').fill('suchtreffer')
    await expect(page.getByText(searchTargetName)).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText(/^E2E-Fuelldokument/)).toHaveCount(0)
    await expect(pagination).toHaveCount(0)
  })

  test('Dokumentliste erscheint auch fuer eine Nicht-Upload-Bibliothek', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Konnektor-Dokumentliste ${runId}`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(libraryName)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await page.getByLabel('Adresse (URL)').fill('http://rss-feed/anlagen/')
    await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    // No indexing run was triggered - the section itself must still render (with its empty state
    // and without an upload widget, since this library was never given upload rights at all).
    await expect(page.getByRole('heading', { name: 'Dokumente' })).toBeVisible()
    await expect(page.getByText('Es sind noch keine Dokumente vorhanden.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Dateien hochladen' })).toHaveCount(0)
  })
})

// #516: editing a connector library's source configuration - the "wirkt erst mit dem naechsten
// Indizierungslauf" hint, and the credentials-blank-means-unchanged semantics
// (KnowledgeLibraryService#updateLibrary, ADR-0018; frontend behaviour added by #516, refined by
// #542's origin check).
test.describe('Quellkonfiguration bearbeiten (#516)', () => {
  const createdLibraryIds = cleanupLibraries()

  test('URL-Aenderung zeigt den Hinweis, leeres Credentials-Feld laesst bestehende unveraendert', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Quellkonfiguration-Bearbeiten ${runId}`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByLabel('Name').fill(libraryName)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await page.getByLabel('Adresse (URL)').fill('http://rss-feed/anlagen/')
    // Credentials are set on creation so this test can prove they survive an edit that leaves the
    // field blank - a library created without any has nothing to preserve in the first place.
    await page.getByLabel('Anmeldedaten').fill('testuser:testpass')
    await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    await page.getByRole('button', { name: 'Quellkonfiguration bearbeiten' }).click()
    const editDialog = page.getByRole('dialog')
    await expect(
      editDialog.getByText(
        'Diese Änderung wirkt erst mit dem nächsten Indizierungslauf dieser Bibliothek.',
      ),
    ).toBeVisible()
    // Credentials were just set at creation - the edit dialog must already know that, before this
    // test even touches the field.
    await expect(
      editDialog.getByText(
        'Leer lassen, um die bestehenden Zugangsdaten beizubehalten. Wird nie in einer API-Antwort ausgegeben.',
      ),
    ).toBeVisible()

    // Same origin ("rss-feed"), different path - proves an edit takes effect without triggering
    // #542's origin-change credential drop, which would otherwise confound this test's own
    // "leer lassen = unveraendert" assertion below.
    await editDialog.getByLabel('Adresse (URL)').fill('http://rss-feed/seiten/')
    await editDialog.getByRole('button', { name: 'Speichern' }).click()
    await expect(editDialog).toHaveCount(0)

    await expect(page.getByText('Adresse (URL):')).toBeVisible()
    await expect(page.getByText('http://rss-feed/seiten/')).toBeVisible()

    // Reopening confirms the credentials are still considered present - had the blank field wiped
    // them, this would show the "keine Zugangsdaten hinterlegt" copy instead.
    await page.getByRole('button', { name: 'Quellkonfiguration bearbeiten' }).click()
    await expect(
      page.getByRole('dialog').getByText(
        'Leer lassen, um die bestehenden Zugangsdaten beizubehalten. Wird nie in einer API-Antwort ausgegeben.',
      ),
    ).toBeVisible()
  })
})
