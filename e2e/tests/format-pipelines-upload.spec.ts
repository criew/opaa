import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import { createLibraryWithDocuments, indexedDocumentStates } from '../fixtures/chat'

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

// Every admitted upload format except `.txt` and `.pdf`, which other specs already upload.
const FORMAT_FILES = [
  'formatdokument.xlsx',
  'formatdokument.html',
  'formatdokument.eml',
  'formatdokument.docx',
  'formatdokument.pptx',
  'formatdokument.odt',
  'formatdokument.odp',
  'formatdokument.md',
  'formatdokument.csv',
  'formatdokument.ods',
  'formatdokument.doc',
  'formatdokument.msg',
]

/**
 * One upload of every other admitted format (`io.opaa.format.SupportedDocumentFormats`) in a
 * single call proves the accept list, the multi-file upload path, the content check and each
 * format's own pipeline all agree end to end. `.eml` is the only format admitted by a text-tolerant
 * content check, and via its attachment the only one that recurses through the pipeline registry
 * for a second format. The fixtures are a few KB each, so the whole upload indexes in seconds.
 */
test.describe('Formatabdeckung Upload', () => {
  test('jedes zugelassene Format wird in einem Durchlauf hochgeladen und indiziert', async ({
    formatPipelinesPage: page,
  }) => {
    // #1184 (ADR-0022, Entscheidung 5): the document list groups attachments collapsed under
    // their parent, so only the uploaded top-level rows carry a visible status chip - the
    // EML fixture's attachment row (its own indexed document) is asserted
    // explicitly below by expanding the group, not via this count.
    await createLibraryWithDocuments(
      page,
      LIBRARY_NAME,
      FORMAT_FILES.map((name) => ({ path: join(FIXTURE_DIR, name), name })),
      FORMAT_FILES.length,
    )

    // Regression guard for #1218 (ADR-0022): the uploaded mail's attachment is its own indexed
    // document row, no longer a nested chunk of the mail - visible as a grouped attachment row
    // with its own "Indiziert" state once the group is expanded: every parent + 1 attachment.
    const toggle = page.getByRole('button', {
      name: '1 Anhang von formatdokument.eml anzeigen',
    })
    await expect(toggle).toHaveText(/1 Anhang/)
    await toggle.click()
    await expect(page.getByText('formatdokument-anhang.txt', { exact: true })).toBeVisible()
    await expect(indexedDocumentStates(page)).toHaveCount(FORMAT_FILES.length + 1)
  })
})
