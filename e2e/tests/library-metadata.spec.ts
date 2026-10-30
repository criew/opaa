import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Page } from '@playwright/test'
import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  createLibraryWithDocuments,
  expectCitedExclusively,
  startFreshChat,
} from '../fixtures/chat'
import { referenceLibrary } from '../fixtures/connectionProfiles'
import { cleanupLibraries, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import { assignToDefaultSpace } from '../fixtures/spaces'

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
// Without spaces: the chat's @-reference takes the name as one word (see referenceLibrary).
const LIBRARY_NAME = `E2E-Metadaten-${runId}`
const DOCUMENT_2024 = 'metadatendokument-2024.txt'
const DOCUMENT_2025 = 'metadatendokument-2025.txt'

/** Sets the library field „Fassung" of one document through its metadata view in the list. */
async function setVersion(page: Page, fileName: string, valueLabel: string): Promise<void> {
  await page.getByRole('button', { name: `Metadaten von ${fileName} anzeigen` }).click()
  const panel = page.getByRole('region', { name: `Metadaten von ${fileName}` })
  await panel.getByRole('button', { name: `Fassung von ${fileName} bearbeiten` }).click()
  const dialog = page.getByRole('dialog', { name: 'Fassung setzen' })
  await dialog.getByRole('combobox', { name: /Fassung/ }).click()
  await page.getByRole('option', { name: valueLabel }).click()
  await dialog.getByRole('button', { name: 'Speichern' }).click()
  await expect(dialog).toBeHidden()
  await expect(panel.getByText(valueLabel, { exact: true })).toBeVisible()
  await expect(panel.getByText('manuell')).toBeVisible()
}

/**
 * A library field from definition to effect: „Feld anlegen" in the tab „Metadaten", a value per
 * document by hand, and the chat filter on that value, which leaves the other document out of the
 * Belege. Both documents carry a value, because a document without one is never filtered out.
 */
test.describe('Metadaten einer Bibliothek', () => {
  const createdLibraryIds = cleanupLibraries()

  test('Feld anlegen, Wert pflegen, Filter im Chat schränkt die Belege ein', async ({
    authenticatedPage: page,
  }) => {
    await createLibraryWithDocuments(page, LIBRARY_NAME, [
      { path: join(FIXTURE_DIR, DOCUMENT_2024), name: DOCUMENT_2024 },
      { path: join(FIXTURE_DIR, DOCUMENT_2025), name: DOCUMENT_2025 },
    ])
    const libraryId = libraryIdFromCurrentUrl(page)
    createdLibraryIds.push(libraryId)

    await page.getByRole('tab', { name: 'Metadaten' }).click()
    await page.getByRole('button', { name: 'Feld anlegen' }).click()
    const createDialog = page.getByRole('dialog', {
      name: 'Metadatenfeld anlegen',
    })
    await createDialog.getByLabel('Feldschlüssel').fill('fassung')
    await createDialog.getByLabel('Feldname').fill('Fassung')
    await createDialog
      .getByRole('textbox', { name: 'Werteliste' })
      .fill('F2024=Fassung 2024\nF2025=Fassung 2025')
    await createDialog.getByRole('button', { name: 'Anlegen' }).click()
    await expect(createDialog).toBeHidden()
    await expect(page.getByRole('button', { name: '2 Werte von Fassung' })).toBeVisible()

    await page.getByRole('tab', { name: 'Dokumente' }).click()
    await setVersion(page, DOCUMENT_2024, 'Fassung 2024')
    await setVersion(page, DOCUMENT_2025, 'Fassung 2025')

    // The @-reference offers only libraries of the chat's space.
    await assignToDefaultSpace('dev-admin', 'KNOWLEDGE_LIBRARY', libraryId)
    await startFreshChat(page)
    await referenceLibrary(page, LIBRARY_NAME)
    await page.getByRole('button', { name: 'Filter für Metadaten setzen' }).click()
    await page.getByRole('checkbox', { name: 'Fassung 2024 (1)' }).check()
    await page.getByRole('button', { name: 'Anwenden' }).click()
    await expect(page.getByRole('button', { name: /^Filter nach Fassung/ })).toBeVisible()

    await askQuestion(page, 'Wie hoch ist die Jahresgebühr für einen Leseausweis?')
    await expectCitedExclusively(page, DOCUMENT_2024, DOCUMENT_2025)
  })
})
