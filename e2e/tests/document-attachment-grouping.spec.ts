import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import { createLibraryWithDocuments } from '../fixtures/chat'

// Same rationale/location as format-pipelines-upload.spec.ts's own FIXTURE_DIR: committed,
// deterministic fixtures under demo/seed/e2e-data/test-documents/.
const FIXTURE_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
)

const MAIL_FILE_NAME = 'mail-mit-zwei-anhaengen.eml'

// Unique per run so re-runs against a stack that was not torn down never collide with a leftover
// library of the same name (mirrors knowledge-libraries.spec.ts's own runId convention).
const runId = Date.now()
const LIBRARY_NAME = `E2E Anhangsbibliothek ${runId}`

/**
 * Covers #1184 (ADR-0022, Entscheidung 5): a mail's attachments are their own document rows
 * carrying parentDocumentId, and the library's document list groups them collapsibly under the
 * mail instead of listing them as flat, unexplained extra rows.
 */
test.describe('Anhangsgruppierung in der Dokumentliste', () => {
  test('Eine Mail mit zwei Anhängen wird gruppiert unter dem Elterndokument dargestellt', async ({
    authenticatedPage: page,
  }) => {
    await createLibraryWithDocuments(page, LIBRARY_NAME, [
      { path: join(FIXTURE_DIR, MAIL_FILE_NAME), name: MAIL_FILE_NAME },
    ])

    // The two attachments are indexed asynchronously after the mail itself reaches "indiziert",
    // and the list only keeps re-fetching while something on it is still PENDING - reload until
    // the mail row shows its attachment toggle.
    const toggle = page.getByRole('button', {
      name: `Anhänge von ${MAIL_FILE_NAME} anzeigen`,
    })
    await expect(async () => {
      await page.reload()
      await expect(toggle).toBeVisible({ timeout: 3_000 })
    }).toPass({ timeout: 30_000 })
    await expect(toggle).toHaveText(/2 Anhänge/)

    // Collapsed by default: the list stays at its parent-level length.
    await expect(page.getByText('foerderbescheid-anlage-eins.txt', { exact: true })).toBeHidden()

    await toggle.click()
    await expect(page.getByText('foerderbescheid-anlage-eins.txt', { exact: true })).toBeVisible()
    await expect(page.getByText('foerderbescheid-anlage-zwei.txt', { exact: true })).toBeVisible()
    // Each attachment row is marked with its own "Anhang" chip.
    await expect(page.getByText('Anhang', { exact: true })).toHaveCount(2)

    await page.getByRole('button', { name: `Anhänge von ${MAIL_FILE_NAME} verbergen` }).click()
    await expect(page.getByText('foerderbescheid-anlage-eins.txt', { exact: true })).toBeHidden()
  })
})
