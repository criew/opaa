import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  expectCitedSource,
  expectNoCatalogMatch,
  openNewLibraryWizard,
  searchLibraries,
} from '../fixtures/chat'
import {
  acceptConfirm,
  gotoLibrary,
  referenceLibrary,
  runCleanupSteps,
  deleteProfileViaApi,
  triggerIndexingInUi,
} from '../fixtures/connectionProfiles'
import { libraryIdFromCurrentUrl } from '../fixtures/libraries'
import {
  createNextcloudAccount,
  deleteNextcloudAccount,
  putTextFile,
  waitForNextcloud,
  type NextcloudAccount,
} from '../fixtures/nextcloud'
import {
  accountIdOf,
  createPersonProfile,
  createSpaceWithMember,
  deleteSpaceViaApi,
  disconnectAccountViaApi,
  eraseLibraryAsOwner,
  libraryStatusFor,
  tryShareLibrary,
} from '../fixtures/privateLibraries'
import { escapeRegExp } from '../fixtures/promptLibraries'
import type { Page } from '@playwright/test'

// Connected accounts and private libraries against the suite's real Nextcloud (the only connector
// with ownership "person"). Actors: dev-user owns the library, dev-outsider is another member of
// the same space, dev-admin never sees a private library. Serial: the library from scenario 3 is
// the subject of 4 and 5; afterAll cleans up even after a failure.
const runId = Date.now()
const PROFILE_NAME = `E2E Nextcloud ${runId}`
const SPACE_NAME = `E2E-Privat-Space-${runId}`
// Ohne Leerzeichen: die @-Referenz im Chat endet am ersten Leerzeichen (space-chats.spec.ts).
const LIBRARY_NAME = `E2E-Privat-Bibliothek-${runId}`
const NEXTCLOUD_USER = `e2e-privat-${runId}`
const FOLDER = 'Akten'
// Einmalig in dieser Suite: Kein anderes Szenario kann den Treffer mitliefern.
const FILE_NAME = `privatakte-${runId}.txt`
const FILE_TEXT = `Aktenvermerk ${runId}: Die Fristverlängerung für den Bauantrag wurde bewilligt.`

const OWNER = 'dev-user'
const OTHER_MEMBER = 'dev-outsider'

const NO_KNOWLEDGE_ASSIGNED = 'Diesem Space ist kein Wissen zugeordnet.'
const NO_KNOWLEDGE_UNREADABLE = 'derzeit kein Wissen verfügbar'
const NOT_ALL_READABLE = 'Nicht alle zugeordneten Inhalte sind für Sie lesbar.'

let account: NextcloudAccount
let profileId: string
let spaceId: string
let libraryId: string | null = null

function accountsRoute() {
  return '/settings/accounts'
}

/** Öffnet „Einstellungen → Verbundene Konten“ und wartet, bis die Übersicht geladen ist. */
async function gotoConnectedAccounts(page: Page): Promise<void> {
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/me/connected-accounts',
    ),
    page.goto(accountsRoute()),
  ])
}

/** Die Zeile der Verbindung zum Zugang in „Ihre verbundenen Konten“. */
function connectionRow(page: Page) {
  return page.getByTestId(`connected-account-${profileId}`)
}

/** Trägt Benutzername und Passwort im Dialog „Konto verbinden“ ein und sendet ab. */
async function submitCredentials(page: Page, username: string, secret: string): Promise<void> {
  const dialog = page.getByRole('dialog', { name: /^Konto (neu )?verbinden:/ })
  await dialog.getByLabel('Benutzername').fill(username)
  await dialog.getByLabel('Passwort oder App-Passwort').fill(secret)
  await dialog.getByRole('button', { name: /^(Neu )?[Vv]erbinden$/ }).click()
}

/** Der Reiter „Quelle“ der privaten Bibliothek. */
async function openSourceTab(page: Page): Promise<void> {
  await gotoLibrary(page, libraryId!)
  await page.getByRole('tab', { name: 'Quelle' }).click()
}

/** Fragt im Chat des geteilten Space mit @-Referenz auf die private Bibliothek. */
async function askInSharedSpace(page: Page, withReference = true): Promise<void> {
  await page.goto(`/spaces/${spaceId}/chats/new`)
  await expect(page.getByPlaceholder('Nachricht eingeben …')).toBeVisible()
  if (withReference) await referenceLibrary(page, LIBRARY_NAME)
  await askQuestion(page, `Was steht im Aktenvermerk ${runId} zur Fristverlängerung?`)
}

test.describe.serial('Verbundene Konten und private Bibliotheken', () => {
  test.beforeAll(async () => {
    // Hooks run under the test timeout; the first Nextcloud start alone may take longer.
    test.setTimeout(300_000)
    await waitForNextcloud()
    account = await createNextcloudAccount(NEXTCLOUD_USER)
    await putTextFile(account, FOLDER, FILE_NAME, FILE_TEXT)
    profileId = await createPersonProfile(PROFILE_NAME)
    spaceId = await createSpaceWithMember(SPACE_NAME, OWNER, OTHER_MEMBER)
  })

  test.afterAll(async () => {
    test.setTimeout(300_000)
    await runCleanupSteps([
      [
        'private Bibliothek',
        async () => (libraryId ? eraseLibraryAsOwner(OWNER, libraryId) : undefined),
      ],
      [
        'verbundenes Konto',
        async () => (profileId ? disconnectAccountViaApi(OWNER, profileId) : undefined),
      ],
      ['Space', async () => (spaceId ? deleteSpaceViaApi(OWNER, spaceId) : undefined)],
      ['Zugang', async () => (profileId ? deleteProfileViaApi(profileId) : undefined)],
      ['Nextcloud-Konto', () => deleteNextcloudAccount(NEXTCLOUD_USER)],
    ])
  })

  test('3a. Person verbindet ihr Konto per App-Passwort; ein falsches wird nicht gespeichert', async ({
    regularUserPage: page,
  }) => {
    await gotoConnectedAccounts(page)
    await expect(page.getByText('Sie haben kein Konto verbunden.')).toBeVisible()
    const connectable = page.getByRole('list', { name: 'Zugänge zum Verbinden' })
    await expect(connectable).toContainText(PROFILE_NAME)

    await connectable.getByRole('button', { name: `Konto verbinden: ${PROFILE_NAME}` }).click()
    await submitCredentials(page, NEXTCLOUD_USER, `${account.appPassword}-falsch`)
    const dialog = page.getByRole('dialog', { name: /^Konto verbinden:/ })
    await expect(dialog.getByRole('alert')).toContainText('Die Anmeldung wurde nicht angenommen')
    // Nichts wurde gespeichert, und das Passwortfeld ist nach dem Absenden leer.
    await expect(dialog.getByLabel('Passwort oder App-Passwort')).toHaveValue('')
    await expect(connectionRow(page)).toHaveCount(0)

    await submitCredentials(page, NEXTCLOUD_USER, account.appPassword)
    await expect(dialog).toHaveCount(0)
    await expect(
      page.getByRole('alert').filter({ hasText: `Ihr Konto ist mit „${PROFILE_NAME}“ verbunden.` }),
    ).toBeVisible()

    const row = connectionRow(page)
    await expect(row).toContainText('Verbunden')
    await expect(row).toContainText(`Konto: ${NEXTCLOUD_USER}`)
    await expect(row).toContainText('Von keiner Ihrer Bibliotheken genutzt.')
    // Das Geheimnis kommt nie wieder zurück.
    await expect(page.locator('body')).not.toContainText(account.appPassword)
  })

  test('3b. Private Bibliothek über das verbundene Konto: Marke „Privat“, Lauf, Inhalt', async ({
    regularUserPage: page,
  }) => {
    await openNewLibraryWizard(page)
    await page.getByRole('radio', { name: /Nextcloud/ }).click()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await expect(
      page.getByRole('heading', { level: 2, name: 'Quelle einrichten' }),
    ).toBeVisible()

    // Der private Weg steht als eigene Wahl in der Liste der Zugänge, nie vorgewählt.
    const own = page
      .getByRole('radiogroup', { name: 'Zugang' })
      .getByRole('radio', { name: new RegExp(`^${escapeRegExp(PROFILE_NAME)} · privat`) })
    await expect(own).not.toBeChecked()
    await expect(page.getByRole('radiogroup', { name: 'Zugang' })).toContainText(
      'nur für Sie, später nicht änderbar',
    )
    await own.click()
    // Die Adresse kommt aus dem Zugang, und es gibt kein Feld für Zugangsdaten.
    await expect(page.getByLabel('Adresse', { exact: true })).toHaveValue('http://nextcloud')
    await expect(page.getByLabel('App-Passwort')).toHaveCount(0)

    // Ordner und Verbindungstest laufen über das verbundene Konto.
    await page.getByRole('button', { name: 'Ändern Umfang' }).click()
    await page.getByRole('radio', { name: 'Nur ausgewählte Ordner' }).check()
    await page.getByRole('group', { name: 'Ordner' }).getByRole('checkbox', { name: new RegExp(FOLDER) }).check()
    await page.getByRole('button', { name: 'Ändern Verbindung' }).click()
    await page.getByRole('button', { name: 'Verbindung testen' }).click()
    await expect(
      page.getByRole('status').filter({ hasText: 'Anmeldung angenommen' }),
    ).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: 'Ändern Zeitplan' }).click()
    await page.getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen' }).uncheck()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    // Der Schritt „Name“ sagt, wer die Bibliothek sieht.
    await expect(page.getByTestId('library-create-private-note')).toContainText(
      'Nur Sie sehen diese Bibliothek.',
    )
    await page.getByLabel('Name', { exact: true }).fill(LIBRARY_NAME)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    // Der Schritt „Freigaben“ entfällt: Eine private Bibliothek wird nicht geteilt; nach dem Namen
    // folgt gleich „Prüfen“.
    await expect(page.getByRole('heading', { level: 2, name: 'Prüfen' })).toBeAttached()
    await expect(page.getByText('Freigaben', { exact: true })).toHaveCount(0)
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    libraryId = libraryIdFromCurrentUrl(page)

    await expect(
      page.getByRole('img', { name: 'Private Bibliothek – nur Sie sehen sie' }),
    ).toBeVisible()
    await page.getByRole('tab', { name: 'Quelle' }).click()
    await expect(page.getByRole('region', { name: 'Verbindung' })).toContainText(
      `Zugang „${PROFILE_NAME}“`,
    )
    const status = await triggerIndexingInUi(page, libraryId)
    expect(status.status).toBe('COMPLETED')
    expect(status.documentCount).toBe(1)

    await page.getByRole('tab', { name: 'Dokumente' }).click()
    await expect(page.getByText(FILE_NAME)).toBeVisible()
    // Die Übersicht der verbundenen Konten nennt die Bibliothek als Nutzerin der Verbindung.
    await gotoConnectedAccounts(page)
    await expect(connectionRow(page).getByRole('link', { name: LIBRARY_NAME })).toBeVisible()
    // Positivprobe zu 3d: Die Besitzerin findet die Bibliothek im Katalog.
    await searchLibraries(page, LIBRARY_NAME)
    await expect(page.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()
  })

  test('3c. Im geteilten Space gehört die Zuordnung nur der Besitzerin: der Inhalt wird gefunden', async ({
    regularUserPage: page,
  }) => {
    await page.goto(`/spaces/${spaceId}/settings/content`)
    await page.getByRole('button', { name: 'Nur zugeordnete' }).click()
    await page.getByRole('searchbox', { name: 'Suchen' }).fill(LIBRARY_NAME)
    const tile = page.getByRole('checkbox', {
      name: new RegExp(`^${escapeRegExp(LIBRARY_NAME)}`),
    })
    await expect(tile).toHaveAttribute('aria-checked', 'false')
    // The server's answer, not the optimistic check mark, proves the association exists.
    const associated = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === `/api/v1/spaces/${spaceId}/assets`,
    )
    await tile.click()
    expect((await associated).ok()).toBe(true)
    await expect(tile).toHaveAttribute('aria-checked', 'true')

    // The owner finds her private content in the space chat, with a citation: by reference and,
    // like the other member in 3d, through the default @Space-Wissen scope without one.
    await askInSharedSpace(page, true)
    await expectCitedSource(page, FILE_NAME)
    await askInSharedSpace(page, false)
    await expectCitedSource(page, FILE_NAME)
  })

  test('3d. Ein anderes Mitglied sieht die Zuordnung nicht und findet nichts daraus', async ({
    outsiderPage: page,
  }) => {
    // Das andere Mitglied sieht die Zuordnung nicht: weder die Bibliothek noch ihren Namen noch
    // einen Hinweis „nicht alle lesbar“ - für fremde private Bibliotheken gibt es auch das nicht.
    await page.goto(`/spaces/${spaceId}/settings/content`)
    await expect(page.getByText('Diesem Space ist noch nichts zugeordnet.')).toBeVisible()
    await expect(page.getByText(NOT_ALL_READABLE)).toHaveCount(0)
    await expect(page.getByText(LIBRARY_NAME)).toHaveCount(0)
    await page.getByRole('searchbox', { name: 'Suchen' }).fill(LIBRARY_NAME)
    await expect(page.getByText(LIBRARY_NAME)).toHaveCount(0)

    // To this member the space looks empty, notice and answer alike: nothing assigned, not
    // "assigned but unreadable". The answer carries no citation and names nothing of the file.
    await page.goto(`/spaces/${spaceId}/chats/new`)
    await expect(page.getByTestId('space-knowledge-notice')).toContainText(NO_KNOWLEDGE_ASSIGNED)
    await askQuestion(page, `Was steht im Aktenvermerk ${runId} zur Fristverlängerung?`)
    await expect(page.getByTestId('message-list')).toContainText('keine Informationen')
    await expect(page.getByTestId('message-list')).not.toContainText(NO_KNOWLEDGE_UNREADABLE)
    await expect(page.getByRole('button', { name: 'Belege anzeigen' })).toHaveCount(0)
    await expect(page.getByText(FILE_NAME)).toHaveCount(0)
    await expect(page.getByText('Fristverlängerung für den Bauantrag')).toHaveCount(0)

    // Auch Katalog und Adresse kennen die Bibliothek nicht; für die Systemverwaltung ist sie
    // ebenso nicht vorhanden.
    await searchLibraries(page, LIBRARY_NAME)
    await expectNoCatalogMatch(page, LIBRARY_NAME)
    expect(await libraryStatusFor(OTHER_MEMBER, libraryId!)).toBe(404)
    expect(await libraryStatusFor('dev-admin', libraryId!)).toBe(404)
  })

  test('4. Teilen ist nicht möglich: der Reiter „Freigaben“ bietet es nicht an, die API lehnt ab', async ({
    regularUserPage: page,
  }) => {
    await gotoLibrary(page, libraryId!)
    await page.getByRole('tab', { name: 'Freigaben' }).click()
    // Weder Berechtigungen noch externe KI-Werkzeuge noch Herleitung: Die Besitzerin hat hier nichts zu
    // vergeben. Die Zeile „Suchdiagnose“ gibt es dagegen, sie belegt, dass der Reiter geladen ist.
    await expect(page.getByRole('region', { name: 'Suchdiagnose' })).toBeVisible()
    await expect(page.getByRole('region', { name: 'Berechtigungen' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Externer Zugang' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'Herleitung' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Freigeben' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Übergeben/ })).toHaveCount(0)

    const recipient = await accountIdOf(OTHER_MEMBER)
    const refused = await tryShareLibrary(OWNER, libraryId!, recipient)
    expect(refused.status).toBe(403)
    expect(refused.code).toBe('OWNER_ONLY_ASSET')
    // Die Systemverwaltung kommt gar nicht erst bis zur Prüfung: Für sie gibt es die Bibliothek nicht.
    expect((await tryShareLibrary('dev-admin', libraryId!, recipient)).status).toBe(404)
    // Es ist nichts entstanden: Das andere Mitglied findet die Bibliothek weiterhin nicht.
    expect(await libraryStatusFor(OTHER_MEMBER, libraryId!)).toBe(404)
  })

  test('5. Trennen lässt die Bibliothek ruhen, zeigt den Hinweis und lässt den Inhalt durchsuchbar', async ({
    regularUserPage: page,
  }) => {
    await gotoConnectedAccounts(page)
    await page.getByRole('button', { name: `Verbindung trennen: ${PROFILE_NAME}` }).click()
    // Die Rückfrage nennt, was geschieht: Zugangsdaten weg, Bibliothek ruht, Inhalt bleibt.
    const confirm = page.getByRole('dialog').filter({ has: page.locator('#confirm-question') })
    await expect(confirm).toContainText(`Ihre Bibliothek „${LIBRARY_NAME}“ ruht danach`)
    await acceptConfirm(page, `Verbindung zu „${PROFILE_NAME}“ trennen?`)
    await expect(
      page
        .getByRole('alert')
        .filter({ hasText: `Die Verbindung zu „${PROFILE_NAME}“ ist getrennt.` }),
    ).toBeVisible()

    // Die Zeile bleibt, solange eine private Bibliothek daran hängt.
    const row = connectionRow(page)
    await expect(row).toContainText('Getrennt')
    await expect(row.getByRole('link', { name: LIBRARY_NAME })).toBeVisible()
    await expect(
      row.getByRole('button', { name: `Konto neu verbinden: ${PROFILE_NAME}` }),
    ).toBeVisible()

    // An der Bibliothek steht der Hinweis, der die Besitzerin zu „Verbundene Konten“ zurückführt.
    await openSourceTab(page)
    const notice = page.getByTestId('source-lock-notice')
    await expect(notice).toContainText('Verbindung getrennt')
    await expect(notice.getByRole('link', { name: 'Konto verbinden' })).toHaveAttribute(
      'href',
      accountsRoute(),
    )

    // Der Bestand bleibt durchsuchbar.
    await askInSharedSpace(page)
    await expectCitedSource(page, FILE_NAME)
  })

  test('5b. Neu verbinden hebt den Hinweis wieder auf', async ({ regularUserPage: page }) => {
    await gotoConnectedAccounts(page)
    await page.getByRole('button', { name: `Konto neu verbinden: ${PROFILE_NAME}` }).click()
    await submitCredentials(page, NEXTCLOUD_USER, account.appPassword)
    await expect(connectionRow(page)).toContainText('Verbunden')
    await expect(connectionRow(page)).not.toContainText('Getrennt')

    await openSourceTab(page)
    await expect(page.getByTestId('source-lock-notice')).toHaveCount(0)
    const status = await triggerIndexingInUi(page, libraryId!)
    expect(status.status).toBe('COMPLETED')
  })
})
