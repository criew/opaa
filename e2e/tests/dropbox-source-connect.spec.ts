import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  createFromReview,
  expectCitedSource,
  openNewLibraryWizard,
  startFreshChat,
} from '../fixtures/chat'
import {
  deleteProfileViaApi,
  gotoLibrary,
  referenceLibrary,
  runCleanupSteps,
  triggerIndexingInUi,
} from '../fixtures/connectionProfiles'
import { deleteLibraryCompletely, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import { createDropboxProfile } from '../fixtures/dropbox'
import { apiAs } from '../fixtures/externalAccess'
import { assignLibraryToDefaultSpace } from '../fixtures/spaces'

// "Quelle verbinden" with the Dropbox connector against dropbox-stub: without the service account
// confirmation nothing leaves OPAA, after it the consent runs through the provider and back, the
// account is named, and the library indexes a file that the chat then finds. Actor: dev-admin.
// Serial: the library from the first test is the subject of the second.
const runId = Date.now()
const PROFILE_NAME = `E2E Dropbox ${runId}`
// No spaces: an @ reference in the chat ends at the first space.
const LIBRARY_NAME = `E2E-Dropbox-Bibliothek-${runId}`
const FOLDER = '/E2E-Ablage'
const FILE_NAME = 'dropbox-aktenvermerk.txt'
const STUB_ACCOUNT = 'dienstkonto@example.org'
const STUB_URL = process.env.E2E_DROPBOX_STUB_BASE_URL ?? 'http://localhost:18091'

let profileId: string | null = null
let libraryId: string | null = null

async function authorizeVisits(): Promise<number> {
  const response = await fetch(`${STUB_URL}/__state`)
  return ((await response.json()) as { authorizeVisits: number }).authorizeVisits
}

test.describe.serial('Dropbox: Quelle verbinden', () => {
  test.beforeAll(async () => {
    profileId = await createDropboxProfile(PROFILE_NAME)
  })

  test.afterAll(async ({ request }) => {
    await runCleanupSteps([
      ['Bibliothek', async () => (libraryId ? deleteLibraryCompletely(request, libraryId) : undefined)],
      ['Zugang', async () => (profileId ? deleteProfileViaApi(profileId) : undefined)],
    ])
  })

  test('1. Ohne Bestätigung keine Weiterleitung, danach Verbindung und Kontoadresse', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000)
    await openNewLibraryWizard(page)
    await page.getByRole('radio', { name: /Dropbox/ }).click()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(
      page.getByRole('heading', { level: 2, name: 'Quelle einrichten' }),
    ).toBeVisible()
    // The profile is the only way for Dropbox: it is set without a choice.
    await expect(page.getByRole('radiogroup', { name: 'Zugang' })).toHaveCount(0)

    const consent = page.getByTestId('source-consent')
    await expect(consent).toContainText('Quelle verbinden')
    // Folder choice and connection test wait for the consent: no test, and „Umfang“ lists no
    // folder but names the missing connection.
    await expect(page.getByRole('button', { name: 'Verbindung testen' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Ändern Umfang' }).click()
    await page.getByRole('radio', { name: 'Nur ausgewählte Ordner' }).check()
    const scope = page.getByRole('region', { name: 'Umfang' })
    await expect(scope).toContainText('Für die Ordnerauswahl muss die Quelle verbunden sein')
    await expect(scope.getByRole('checkbox')).toHaveCount(0)
    await page.getByRole('button', { name: 'Ändern Verbindung' }).click()

    const visitsBefore = await authorizeVisits()
    const startRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/connections/authorizations')) {
        startRequests.push(request.method())
      }
    })
    await consent.getByRole('button', { name: 'Quelle verbinden' }).click()
    await expect(
      consent.getByText('Bitte bestätigen Sie, dass Sie ein Dienstkonto verbinden.'),
    ).toBeVisible()
    expect(page.url()).toContain('/libraries/new')
    // No consent was started at OPAA, and the provider was not visited. The server refuses it too.
    expect(startRequests).toEqual([])
    expect(await authorizeVisits()).toBe(visitsBefore)
    const api = await apiAs('dev-admin')
    const refused = await api.post('/api/v1/connections/authorizations', {
      data: { profileId, purpose: 'LIBRARY_NEW', serviceAccountConfirmed: false },
    })
    expect(refused.status()).toBe(400)
    expect(((await refused.json()) as { code: string }).code).toBe(
      'SERVICE_ACCOUNT_CONFIRMATION_REQUIRED',
    )
    await api.dispose()
    expect(await authorizeVisits()).toBe(visitsBefore)

    await consent
      .getByRole('checkbox', { name: 'Ich verbinde ein Dienstkonto, kein persönliches Konto' })
      .check()
    await consent.getByRole('button', { name: 'Quelle verbinden' }).click()
    // Provider and back: the wizard returns to the step. Nothing is left to fill in under
    // „Verbindung“, so „Umfang“ is open, listed through the consent.
    await expect(page.getByRole('radio', { name: 'Nur ausgewählte Ordner' })).toBeVisible({
      timeout: 30_000,
    })
    expect(await authorizeVisits()).toBe(visitsBefore + 1)
    await page.getByRole('radio', { name: 'Nur ausgewählte Ordner' }).check()
    await page
      .getByRole('group', { name: 'Ordner' })
      .getByRole('checkbox', { name: new RegExp(FOLDER.slice(1)) })
      .check()
    // „Verbindung“ names the account the consent was given as.
    await page.getByRole('button', { name: 'Ändern Verbindung' }).click()
    await expect(page.getByTestId('source-consent')).toContainText(`Verbunden als „${STUB_ACCOUNT}“`)
    await page.getByRole('button', { name: 'Verbindung testen' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Verbindung erfolgreich; der Ordner ist erreichbar.' }),
    ).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: 'Ändern Zeitplan' }).click()
    await page.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen' }).uncheck()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByLabel('Name', { exact: true }).fill(LIBRARY_NAME)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await createFromReview(page)
    libraryId = libraryIdFromCurrentUrl(page)

    // The details name the account the consent was given as.
    await page.getByRole('tab', { name: 'Quelle' }).click()
    await page.getByRole('button', { name: 'Ändern Verbindung' }).click()
    const connection = page.getByTestId('source-connection')
    await expect(connection).toContainText('Verbunden als')
    await expect(connection).toContainText(STUB_ACCOUNT)
  })

  test('2. Der Lauf nimmt die Datei auf, und der Chat findet sie', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000)
    await gotoLibrary(page, libraryId!)
    await page.getByRole('tab', { name: 'Quelle' }).click()
    const status = await triggerIndexingInUi(page, libraryId!)
    expect(status.status).toBe('COMPLETED')
    expect(status.documentCount).toBe(1)

    await assignLibraryToDefaultSpace('dev-admin', LIBRARY_NAME)
    await startFreshChat(page)
    await referenceLibrary(page, LIBRARY_NAME)
    await askQuestion(page, 'Was steht im Aktenvermerk zur Förderzusage?')
    await expectCitedSource(page, FILE_NAME)
  })
})
