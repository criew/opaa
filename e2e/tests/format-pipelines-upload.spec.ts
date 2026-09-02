import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import { createLibraryWithDocuments } from '../fixtures/chat'

// Same rationale/location as knowledge-libraries.spec.ts's own TEST_DOCUMENT_PATH: committed,
// deterministic fixtures under demo/seed/e2e-data/test-documents/, not backend/src/test/resources/
// (Java test scope, unreachable from this npm project).
const FIXTURE_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
)

// Unique per run so re-runs against a stack that was not torn down never collide with a leftover
// library of the same name (mirrors knowledge-libraries.spec.ts's own runId convention).
const runId = Date.now()
const LIBRARY_NAME = `E2E Formatbibliothek ${runId}`

/**
 * Covers test(e2e) #1109 (Epic #1054/#1110 review, E5): the suite otherwise only ever uploads
 * `.txt` (and one `.pdf` fixture elsewhere) - every other admitted format
 * (`io.opaa.indexing.SupportedDocumentFormats`) has never been driven through the real upload UI
 * at all. One upload of three formats in a single call proves the accept list, the multi-file
 * upload path and each format's own pipeline all still agree end to end - not encyclopedic
 * coverage of every admitted extension, which belongs to the backend-level
 * `DocumentIndexingIntegrationTest` instead. `.eml` is the most valuable of the three: the only
 * format admitted by a text-tolerant content check rather than a strict media-type match, and (via
 * its bundled attachment) the only one that recurses back through the pipeline registry for a
 * second, different format.
 */
test.describe('Formatabdeckung Upload', () => {
  test('XLSX, HTML und EML werden in einem Durchlauf hochgeladen und jeweils indiziert', async ({
    authenticatedPage: page,
  }) => {
    await createLibraryWithDocuments(page, LIBRARY_NAME, [
      { path: join(FIXTURE_DIR, 'formatdokument.xlsx'), name: 'formatdokument.xlsx' },
      { path: join(FIXTURE_DIR, 'formatdokument.html'), name: 'formatdokument.html' },
      { path: join(FIXTURE_DIR, 'formatdokument.eml'), name: 'formatdokument.eml' },
    ])

    // Redundant with createLibraryWithDocuments's own toHaveCount assertion, but explicit here as
    // the scenario's actual point, not just a side effect of the helper it calls.
    await expect(page.getByText('formatdokument.xlsx', { exact: true })).toBeVisible()
    await expect(page.getByText('formatdokument.html', { exact: true })).toBeVisible()
    await expect(page.getByText('formatdokument.eml', { exact: true })).toBeVisible()
  })
})
