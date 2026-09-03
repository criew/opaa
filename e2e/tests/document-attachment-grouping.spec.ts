import { expect, test } from '../fixtures/auth'
import type { Page, Response } from '@playwright/test'

// The mail fixture is mounted into the backend container by e2e/docker-compose.e2e.yml
// (demo/seed/e2e-data/filesystem-library/ -> /data/e2e-mail-bibliothek, on the dev profile's
// filesystem allowlist). A FILESYSTEM library over that mount creates attachment rows for the
// mail; chosen when the UPLOAD path still discarded discovered attachments (closed by #1227
// since), and kept as the deterministic fixture-driven connector path.
const SOURCE_PATH = '/data/e2e-mail-bibliothek'
const MAIL_FILE_NAME = 'mail-mit-zwei-anhaengen.eml'

// Unique per run so re-runs against a stack that was not torn down never collide with a leftover
// library of the same name (mirrors knowledge-libraries.spec.ts's own runId convention).
const runId = Date.now()
const LIBRARY_NAME = `E2E Anhangsbibliothek ${runId}`

// Same waiting pattern as rss-feed-library.spec.ts's own gotoLibraries (kept module-local there,
// mirrored here for the same single-caller reason).
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
 * Creates a FILESYSTEM library over SOURCE_PATH via the create wizard's origin step (ADR-0018)
 * and returns its id, read back from the URL LibraryCreatePage navigates to on success.
 */
async function createFilesystemLibrary(page: Page, name: string): Promise<string> {
  await gotoLibraries(page)
  await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
  await page.getByLabel('Name').fill(name)
  await page.getByRole('button', { name: 'Weiter', exact: true }).click()
  await page.getByRole('radio', { name: /Dateisystem/ }).click()
  await page.getByLabel('Verzeichnispfad').fill(SOURCE_PATH)
  await page.getByRole('button', { name: 'Weiter zu Rechten' }).click()
  await Promise.all([
    // /libraries/new is the wizard itself - without the lookahead the wait would resolve
    // immediately against the current URL.
    page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
    page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
  ])
  await expect(page.getByRole('heading', { name })).toBeVisible()
  const match = page.url().match(/\/libraries\/([^/]+)$/)
  if (!match) {
    throw new Error(`Unexpected library detail URL after creation: ${page.url()}`)
  }
  return match[1]
}

/** Clicks "Jetzt indizieren" and waits for the run to reach a terminal state (see rss spec). */
async function triggerIndexingAndWaitForCompletion(page: Page, libraryId: string): Promise<void> {
  const completion = page.waitForResponse(
    async (response: Response) => {
      if (response.request().method() !== 'GET') return false
      if (!response.url().includes(`/libraries/${libraryId}/indexing/status`)) return false
      const body = (await response.json().catch(() => null)) as { status?: string } | null
      return body != null && (body.status === 'COMPLETED' || body.status === 'FAILED')
    },
    { timeout: 60_000 },
  )
  await page.getByRole('button', { name: 'Jetzt indizieren' }).click()
  const response = await completion
  const body = (await response.json()) as { status: string; message: string | null }
  expect(body.status, body.message ?? undefined).toBe('COMPLETED')
}

/**
 * Covers #1184 (ADR-0022, Entscheidung 5): a mail's attachments are their own document rows
 * carrying parentDocumentId, and the library's document list groups them collapsibly under the
 * mail instead of listing them as flat, unexplained extra rows.
 */
test.describe('Anhangsgruppierung in der Dokumentliste', () => {
  test('Eine Mail mit zwei Anhängen wird gruppiert unter dem Elterndokument dargestellt', async ({
    authenticatedPage: page,
  }) => {
    const libraryId = await createFilesystemLibrary(page, LIBRARY_NAME)
    await triggerIndexingAndWaitForCompletion(page, libraryId)

    // The two attachments are indexed within the same run, but the already-rendered document list
    // does not re-fetch on run completion - reload until the mail row shows its attachment toggle.
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
