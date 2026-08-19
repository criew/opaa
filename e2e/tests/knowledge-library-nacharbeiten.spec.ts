import { randomBytes } from 'node:crypto'
import { PDFDocument, StandardFonts } from 'pdf-lib'
import { expect, test } from '../fixtures/auth'
import type { Page } from '@playwright/test'

// Nacharbeiten-Serie aus Epic #458 (#514, #516, #517, #519, siehe Issue #547): vier
// nutzersichtbare Verhaltensweisen, die die Suite bislang nicht abdeckte. Eigene Datei statt
// Erweiterung von knowledge-libraries.spec.ts (#424) - dort geht es um die volle
// Upload/Freigabe/Suche-Kette einer einzelnen Bibliothek, hier um vier voneinander unabhängige
// UI-Verhaltensweisen rund um Quellkonfiguration und Dokumentliste. Jedes Szenario legt sich seine
// eigene, wegwerfbare Bibliothek an - dasselbe Muster wie die Negativfälle in
// knowledge-libraries.spec.ts (siehe dort OWN_DOCUMENT_PATH's Kommentar), nur ohne den
// Freigabe-Aspekt, den dieses Issue ausdrücklich nicht abdeckt.
//
// Dateiname bewusst "knowledge-library-..." (Singular), nicht "knowledge-libraries-...": Playwright
// führt alle Spec-Dateien dieser (workers: 1, fullyParallel: false) Suite in alphabetischer
// Reihenfolge aus, und knowledge-libraries.spec.ts's Szenario 2 verlässt sich darauf, dass admins
// gesamter lesbarer Bestand zum Zeitpunkt seiner Chat-Suche noch klein ist (ai-stub liefert für
// jede Anfrage denselben Embedding-Vektor, siehe e2e/README.md - eine Suche kann das eigene
// Dokument unter vielen weiteren, admin-lesbaren Dokumenten mit identischem Vektor verlieren, wenn
// genug davon vor diesem Test entstehen). Ein Dateiname, der nach "knowledge-libraries.spec.ts"
// sortiert (Singular "library" schlägt hier alphabetisch nach Plural "libraries" durch: 'y' > 'i'
// an der ersten abweichenden Stelle), stellt sicher, dass die eigenen, dutzendfach admin-lesbaren
// Wegwerfdokumente dieser Datei erst NACH #424s vollständigem Lauf entstehen - manuell verifiziert:
// mit einem vor "knowledge-libraries.spec.ts" sortierenden Dateinamen schlägt genau
// "knowledge-libraries.spec.ts:164 › 2. Suche findet das eigene Dokument" fehl (source-card für
// wissensdokument.txt bleibt aus), mit dieser Reihenfolge ist die gesamte Suite wieder grün.
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

/**
 * Builds a real, parseable PDF of roughly 2 MB, entirely in memory - not one of the suite's
 * committed fixtures under fixtures/test-documents/ (see knowledge-libraries.spec.ts's comment on
 * TEST_DOCUMENT_PATH for why those live there and stay tiny): a 2 MB binary has no business being
 * checked into git for a single regression test. Padding is real, Tika-extractable text rather
 * than an opaque padded byte blob - FileProcessingService#processUploadedFile throws
 * EmptyDocumentContentException when parsing yields no content at all (see its Javadoc), so a
 * file PDFBox cannot actually read would fail for the wrong reason and never reach the
 * client_max_body_size regression this test exists to catch (#519).
 *
 * Text content is pseudo-random printable ASCII, not repeated/natural-language text: pdf-lib
 * flate-compresses content streams by default, and a first attempt using a single repeated German
 * sentence collapsed to ~17 KB - nowhere near the ~2 MB this test needs to actually exceed nginx's
 * old 1 MB default. Printable-ASCII noise barely compresses at all, so the saved PDF stays close to
 * the raw text size. Spread across many separate drawText calls rather than one giant string: PDF
 * 1.7's own architectural limit (Appendix C) caps a single string literal at 65,535 bytes - well
 * below the ~46 KB chosen per line, but a single string covering the whole ~2 MB would exceed it
 * several times over. Every character comes from WinAnsiEncoding's plain-ASCII range (0x20-0x7E),
 * which Helvetica (a StandardFonts entry) always supports, so embedding never hits an "unsupported
 * glyph" error regardless of which random bytes come up.
 */
async function buildLargePdf(): Promise<Buffer> {
  const pdf = await PDFDocument.create()
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  const page = pdf.addPage([595, 842])
  const { height } = page.getSize()
  const lineLength = 46_000 // well under the 65,535-byte PDF string literal limit
  const lineCount = 45 // ~46 KB * 45 =~ 2.07 MB of near-incompressible extractable text
  for (let i = 0; i < lineCount; i++) {
    page.drawText(randomPrintableAscii(lineLength), { x: 20, y: height - 20 - i * 2, size: 8, font })
  }
  const bytes = await pdf.save()
  return Buffer.from(bytes)
}

/** Pseudo-random printable ASCII (0x20-0x7E, 95 values) of exactly `length` characters. */
function randomPrintableAscii(length: number): string {
  const bytes = randomBytes(length)
  let result = ''
  for (let i = 0; i < length; i++) {
    result += String.fromCharCode(0x20 + (bytes[i] % 95))
  }
  return result
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
    await page.getByRole('dialog').getByLabel('Name').fill(libraryName)
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      page.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()

    await page.getByLabel('Dateien auswählen').setInputFiles({
      name: fileName,
      mimeType: 'application/pdf',
      buffer: pdfBuffer,
    })

    // Both assertions gate on the same round trip: uploadNewDocument's request only resolves once
    // the backend has fully parsed/chunked/embedded the file, and the row (filename + status)
    // only renders once that response comes back - there is no earlier moment at which the
    // filename alone would already be visible. A larger file needs more time for that whole
    // round trip than the suite's tiny committed fixtures, hence 60s on both (not the 10s
    // default from playwright.config.ts's expect.timeout) rather than the 20-30s used elsewhere
    // in the suite for the same reason.
    await expect(page.getByText(fileName)).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('indiziert')).toBeVisible({ timeout: 60_000 })
  })
})

// #514: the connection test in CreateLibraryDialog, both directions.
//
// Happy path: HTTP_DIRECTORY against the suite's own "rss-feed" httpd service
// (docker-compose.e2e.yml, also used by #471), reachable from the backend container under the
// stack-internal hostname "rss-feed" - no dedicated fixture service needed just for this test.
// Not .../anlagen/ itself (used elsewhere in this file and in the #516 scenario below, where only
// creation/editing matters, never a parsed count): a *real* mod_autoindex response from
// httpd:2.4-alpine's stock config renders as a <pre> list, not the <table> layout
// AutoindexCrawlerService#parseDirectory actually parses (that needs "IndexOptions HTMLTable",
// which lives in the image's own commented-out httpd-autoindex.conf) - manually verified: hitting
// .../anlagen/ answers "reachable" but with a count of 0, not 1. .../webverzeichnis/ instead serves
// a hand-crafted static index.html mimicking that exact HTMLTable layout (see
// e2e/fixtures/rss-feed/htdocs/webverzeichnis/index.html for why), with exactly one supported
// document.
//
// Error path: a closed port on a hostname the backend container can resolve instantly via Docker's
// own embedded DNS ("ai-stub", already part of this stack) - not a hostname under the reserved
// ".invalid" TLD as a first attempt used: this sandbox's outbound DNS resolution to the public
// internet is unreliable/slow enough that SourceConnectionTestService's own 10s per-request timeout
// was repeatedly reached before an UnknownHostException ever surfaced, producing a timeout message
// instead of the intended "Host not found" one. A connection refused by a real, internally
// resolvable host needs no DNS round trip at all and fails deterministically fast.
test.describe('Verbindungstest im Erstellungsdialog (#514)', () => {
  test('Erreichbare Quelle zeigt einen Zaehlwert', async ({ authenticatedPage: page }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await dialog.getByLabel('Adresse (URL)').fill('http://rss-feed/webverzeichnis/')
    await dialog.getByRole('button', { name: 'Verbindung testen' }).click()

    const result = dialog.getByRole('alert').filter({ hasText: 'Webverzeichnis erreichbar' })
    await expect(result).toBeVisible({ timeout: 15_000 })
    await expect(result).toContainText(
      'Webverzeichnis erreichbar, 1 unterstuetzte Dokument auf oberster Ebene gefunden.',
    )
  })

  test('Nicht erreichbare Quelle zeigt eine deutsche Fehlermeldung, Anlegen bleibt moeglich', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Verbindungstest-Fehlerfall ${runId}`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await dialog.getByLabel('Name').fill(libraryName)
    // "ai-stub" resolves instantly (it is part of this very stack) - nothing listens on port 9,
    // so the backend's HTTP client gets an immediate, DNS-independent connection refusal.
    await dialog.getByLabel('Adresse (URL)').fill('http://ai-stub:9/')
    await dialog.getByRole('button', { name: 'Verbindung testen' }).click()

    const result = dialog.getByRole('alert').filter({ hasText: 'abgelehnt' })
    await expect(result).toBeVisible({ timeout: 15_000 })
    await expect(result).toContainText('Die Verbindung wurde vom Server abgelehnt.')

    // The failed test must not block creation itself (#514 acceptance criteria) - the source
    // configuration is only probed, never required to succeed.
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      dialog.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
  })
})

// #517: paging and keyword search over a library's document list, plus the list showing up at all
// for a non-UPLOAD (connector) library - LibraryDetailPage renders LibraryDocumentsSection
// whenever library details are loaded, regardless of sourceType; only the upload widget itself is
// gated to UPLOAD.
test.describe('Dokumentliste mit Paging und Suche (#517)', () => {
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
    await page.getByRole('dialog').getByLabel('Name').fill(libraryName)
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      page.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
    const match = page.url().match(/\/libraries\/([^/]+)$/)
    if (!match) {
      throw new Error(`Unexpected library detail URL after creation: ${page.url()}`)
    }
    const libraryId = match[1]

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
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await dialog.getByLabel('Name').fill(libraryName)
    await dialog.getByLabel('Adresse (URL)').fill('http://rss-feed/anlagen/')
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      dialog.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()

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
  test('URL-Aenderung zeigt den Hinweis, leeres Credentials-Feld laesst bestehende unveraendert', async ({
    authenticatedPage: page,
  }) => {
    const libraryName = `E2E Quellkonfiguration-Bearbeiten ${runId}`

    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    const createDialog = page.getByRole('dialog')
    await createDialog.getByRole('radio', { name: /Webverzeichnis/ }).click()
    await createDialog.getByLabel('Name').fill(libraryName)
    await createDialog.getByLabel('Adresse (URL)').fill('http://rss-feed/anlagen/')
    // Credentials are set on creation so this test can prove they survive an edit that leaves the
    // field blank - a library created without any has nothing to preserve in the first place.
    await createDialog.getByLabel('Anmeldedaten').fill('testuser:testpass')
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      createDialog.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()

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
