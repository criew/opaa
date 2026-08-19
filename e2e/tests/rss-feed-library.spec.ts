import { expect, test } from '../fixtures/auth'
import type { Page, Response } from '@playwright/test'

// Deterministic, generic, fully invented RSS-2.0 fixtures for a fictional "Beispielbehörde" -
// served statically by the "rss-feed" service (e2e/docker-compose.e2e.yml), reachable from the
// backend container under this stack-internal hostname (see e2e/fixtures/rss-feed/htdocs/). No
// real institution, no real domain - mirrors #229's "static content in the compose stack"
// pattern, scoped to this suite instead of docker-compose.yml's own "demo" profile.
const FEED_URL_OK = 'http://rss-feed/feed-ok.xml'
const FEED_URL_ERROR = 'http://rss-feed/feed-error.xml'

// Unique per run so re-runs against a stack that was not torn down never collide with a leftover
// library of the same name (same reasoning as knowledge-libraries.spec.ts's LIBRARY_NAME).
const runId = Date.now()
const LIBRARY_NAME_OK = `E2E RSS-Bibliothek ${runId}`
const LIBRARY_NAME_ERROR = `E2E RSS-Bibliothek Fehlerfall ${runId}`

interface IndexingStatusResponse {
  status: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
}

// Same waiting pattern as knowledge-libraries.spec.ts's gotoLibraries (not imported from there:
// that file keeps its helpers module-local, and duplicating one tiny wait here is cheaper than
// introducing a new shared fixtures module for a single caller elsewhere) - GET /api/v1/libraries
// is what decides whether the overview lists a library at all.
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
 * Creates a library from the RSS-Feed template (#480/#481, ADR-0018: the source type is chosen at
 * creation, in CreateLibraryDialog's "Vorlage" selection, not in a later indexing form) and
 * returns its id, read back from the URL CreateLibraryDialog navigates to on success.
 */
async function createRssLibrary(page: Page, name: string, feedUrl: string): Promise<string> {
  await gotoLibraries(page)
  await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('radio', { name: /RSS-Feed/ }).click()
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByLabel('Adresse (URL)').fill(feedUrl)
  await Promise.all([
    page.waitForURL(/\/libraries\/[^/]+$/),
    dialog.getByRole('button', { name: 'Erstellen' }).click(),
  ])
  await expect(page.getByRole('heading', { name })).toBeVisible()
  const match = page.url().match(/\/libraries\/([^/]+)$/)
  if (!match) {
    throw new Error(`Unexpected library detail URL after creation: ${page.url()}`)
  }
  return match[1]
}

// Navigates straight to an existing library's detail page and waits for its own mount-time
// GET .../indexing/status to resolve, so a later triggerIndexingAndWaitForCompletion's
// waitForResponse cannot accidentally resolve against this stale, pre-click response instead of
// the one the new run actually produces.
async function gotoLibraryDetail(page: Page, libraryId: string) {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().includes(`/libraries/${libraryId}/indexing/status`),
    ),
    page.goto(`/libraries/${libraryId}`),
  ])
}

/**
 * Clicks "Jetzt indizieren" and waits for the run to reach a terminal state, reading the outcome
 * straight from indexingStore's own polling response (POLL_INTERVAL_MS = 2000, see
 * stores/indexingStore.ts) rather than parsing the rendered status text - the response body is
 * this run's actual documentCount/documentsSkipped, the same fields LibraryIndexingSection derives
 * its text from.
 */
async function triggerIndexingAndWaitForCompletion(
  page: Page,
  libraryId: string,
): Promise<IndexingStatusResponse> {
  const completion = page.waitForResponse(
    async (response: Response) => {
      if (response.request().method() !== 'GET') return false
      if (!response.url().includes(`/libraries/${libraryId}/indexing/status`)) return false
      const body = (await response.json().catch(() => null)) as IndexingStatusResponse | null
      return body != null && (body.status === 'COMPLETED' || body.status === 'FAILED')
    },
    { timeout: 30_000 },
  )
  await page.getByRole('button', { name: 'Jetzt indizieren' }).click()
  const response = await completion
  return response.json()
}

/**
 * Covers test(e2e) #471: the RSS path over the full stack, via the library creation/detail UI
 * #480/#481 introduced for the source-type selection (the issue's original "im
 * Indizierungsformular den Quellentyp wählen" wording is outdated, see the issue's own
 * coordination comment) - template selection, feed indexing, progress/result visibility, the
 * entry-level negative path and the unchanged-feed idempotency all in one chain.
 *
 * **Content verification is intentionally count-based, not citation-based (Annahme, see PR).**
 * `e2e/ai-stub` always answers `POST /v1/embeddings` with the *same fixed vector* regardless of
 * input (see e2e/README.md, "KI-Stub statt echtem Modell") - the chat/search path this suite
 * otherwise uses (`source-card`, #424) cannot distinguish which of this library's own documents a
 * particular query "found", since every chunk in every library the asking user can read is equally
 * "relevant". This spec instead asserts the indexing run's own document counts (entries processed
 * vs. skipped) and the library's aggregate `documentCount` - the run api indexing/status and
 * `GET .../libraries/{id}` both computed straight from `document_repository.countByLibraryId`, not
 * from anything content-dependent. There is also no UI at all for a connector library's own
 * document list any more (#481 removed the per-document Dokumente table for non-UPLOAD source
 * types together with DocumentsPage.tsx) - so per-entry filenames or boilerplate-stripped text are
 * not independently checkable from this suite; RssFeedIndexingExecutorTest and
 * RssFeedIndexingExecutorAttachmentTest already cover that at the backend integration level.
 */
test.describe.serial('RSS-Feed-Quelle: Positiv-/Negativpfad, unveränderter Feed (#471)', () => {
  let libraryIdOk: string

  test('1. Bibliothek aus dem RSS-Feed-Template anlegen und indizieren', async ({
    authenticatedPage: page,
  }) => {
    libraryIdOk = await createRssLibrary(page, LIBRARY_NAME_OK, FEED_URL_OK)

    // feed-ok.xml carries two entries, both resolving to a real detail page; one of them also
    // links a small attachment (see e2e/fixtures/rss-feed/htdocs/seiten/oeffnungszeiten.html).
    // The job's own progress only counts entries (RssFeedIndexingExecutor never calls
    // progress.recordProcessed/-Skipped for an attachment) - documentCount is therefore 2, not 3.
    const status = await triggerIndexingAndWaitForCompletion(page, libraryIdOk)
    expect(status.status).toBe('COMPLETED')
    expect(status.totalDocuments).toBe(2)
    expect(status.documentCount).toBe(2)
    expect(status.documentsSkipped).toBe(0)
    await expect(page.getByText('Letzter Lauf: Abgeschlossen')).toBeVisible()

    // The library's own aggregate documentCount (2 entries + 1 attachment = 3) is not refreshed
    // by LibraryIndexingSection on completion (#481 left no such hook) - a reload re-runs
    // LibraryDetailPage's loadLibraryDetails effect, which is what actually re-fetches it.
    await page.reload()
    await expect(page.getByText('3 Dokumente')).toBeVisible()
  })

  test('2. Zweiter Lauf über den unveränderten Feed erzeugt keine neuen Dokumente', async ({
    authenticatedPage: page,
  }) => {
    await gotoLibraryDetail(page, libraryIdOk)

    // "No new documents" (#471 acceptance criteria) can be reached two different ways here, and
    // this run's own outcome depends on which one applies (documented in the task's own "ACHTUNG"
    // note, not just this comment): run 1 deferred nothing and failed nothing, so it persisted the
    // feed's ETag/Last-Modified (RssFeedIndexingExecutor#saveFeedState) - this run's conditional
    // GET for feed-ok.xml itself then answers 304, and the run ends with totalDocuments = 0
    // without ever looking at the individual entries again (httpd:2.4-alpine serves static files
    // with an ETag out of the box, so this is the path this suite's own fixture actually takes).
    // Had run 1 deferred or failed anything, no feed state would have been saved, and this run
    // would instead re-fetch the feed and skip each entry individually by its unchanged pubDate
    // (totalDocuments = 2, documentsSkipped = 2) - either way documentCount, the "were any new
    // documents processed" figure, is 0.
    const status = await triggerIndexingAndWaitForCompletion(page, libraryIdOk)
    expect(status.status).toBe('COMPLETED')
    expect(status.documentCount).toBe(0)

    await page.reload()
    await expect(page.getByText('3 Dokumente')).toBeVisible()
  })

  test('3. Ein fehlerhafter Eintrag (404) bricht den Lauf nicht ab und wird ausgewiesen', async ({
    authenticatedPage: page,
  }) => {
    const libraryIdError = await createRssLibrary(page, LIBRARY_NAME_ERROR, FEED_URL_ERROR)

    // feed-error.xml's second entry links a detail page that does not exist in
    // e2e/fixtures/rss-feed/htdocs/ at all - a genuine 404 from the "rss-feed" service, not a
    // simulated one. The first entry is unaffected and still gets processed.
    const status = await triggerIndexingAndWaitForCompletion(page, libraryIdError)
    expect(status.status).toBe('COMPLETED')
    expect(status.totalDocuments).toBe(2)
    expect(status.documentCount).toBe(1)
    expect(status.documentsSkipped).toBe(1)
    await expect(page.getByText('Letzter Lauf: Abgeschlossen')).toBeVisible()
    // Not a plain /1 übersprungen/ text match (strict-mode violation): the just-finished run's
    // success snackbar ("Indizierung abgeschlossen: 1 verarbeitet, 1 übersprungen") and
    // LibraryIndexingSection's own persistent status line both carry that phrase at the same time.
    // The status line's exact wording (indexingStore.ts / LibraryIndexingSection) is the one this
    // assertion actually cares about - it is what stays on screen, unlike the snackbar.
    await expect(page.getByText('Dokumente: 1 verarbeitet (1 übersprungen)')).toBeVisible()

    await page.reload()
    await expect(page.getByText('1 Dokument', { exact: true })).toBeVisible()
  })
})
