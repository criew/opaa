import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import { createLibraryWithDocument, indexedDocumentStates } from '../fixtures/chat'
import { cleanupLibraries, libraryIdFromCurrentUrl } from '../fixtures/libraries'

const FIXTURE_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
)

const runId = Date.now()
const LIBRARY_NAME = `E2E Grenzbibliothek ${runId}`
const MIB = 1024 * 1024

// e2e.env sets OPAA_UPLOAD_MAX_FILE_SIZE to 4 MiB and OPAA_LIBRARY_QUOTA_BYTES to 3 MiB, so a
// 3.5 MiB file passes the file limit and fails the quota, a 4.5 MiB file fails the file limit.
const OVER_QUOTA = { name: `ueber-kontingent-${runId}.txt`, bytes: 3.5 * MIB }
const OVER_FILE_LIMIT = { name: `zu-gross-${runId}.txt`, bytes: 4.5 * MIB }
const INFECTED_NAME = `eicar-${runId}.txt`

// The EICAR test string, assembled here so no file of the repository carries it in one piece.
const EICAR = ['X5O!P%@AP[4\\PZX54(P^)7CC)7}$', 'EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'].join('')

function textFile(name: string, bytes: number) {
  return { name, mimeType: 'text/plain', buffer: Buffer.alloc(bytes, 'a') }
}

/**
 * The three refusals of an upload before anything is stored: the file limit, the library's storage
 * quota and the malware scan (clamd-stub). Each one names the file and gives its German reason, and
 * none of the three leaves a document row behind. Small limits from e2e.env keep the whole upload at
 * about 8 MB.
 */
test.describe('Grenzen der Aufnahme', () => {
  const createdLibraryIds = cleanupLibraries()

  test('Dateigröße, Speicherkontingent und Virenprüfung weisen den Upload mit Grund ab', async ({
    formatPipelinesPage: page,
  }) => {
    // The one file the library takes in also passed the malware scan, so the scanner says OK.
    await createLibraryWithDocument(
      page,
      LIBRARY_NAME,
      join(FIXTURE_DIR, 'formatdokument.md'),
      'formatdokument.md',
    )
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    await page.getByLabel('Dateien auswählen').setInputFiles([
      textFile(OVER_FILE_LIMIT.name, OVER_FILE_LIMIT.bytes),
      textFile(OVER_QUOTA.name, OVER_QUOTA.bytes),
      {
        name: INFECTED_NAME,
        mimeType: 'text/plain',
        buffer: Buffer.from(EICAR, 'latin1'),
      },
    ])

    const rejections = page.getByRole('alert').filter({ hasText: 'Nicht hochgeladen' })
    await expect(rejections).toContainText(OVER_FILE_LIMIT.name)
    // The notice shortens a reason to its first sentence, except for the quota with its figures.
    await expect(rejections).toContainText('Die Datei ist zu groß')
    await expect(rejections).toContainText(OVER_QUOTA.name)
    await expect(rejections).toContainText(
      /Speicherkontingent der Bibliothek erschöpft \(\d+ B von 3 MB belegt\)/,
    )
    await expect(rejections).toContainText(INFECTED_NAME)
    await expect(rejections).toContainText('Die Virenprüfung hat ein Schadprogramm gefunden')

    // Nothing of the three was stored: the list still holds just the one indexed document.
    await expect(indexedDocumentStates(page)).toHaveCount(1)
    for (const name of [OVER_FILE_LIMIT.name, OVER_QUOTA.name, INFECTED_NAME]) {
      await expect(page.getByText(name, { exact: true })).toHaveCount(0)
    }
  })
})
