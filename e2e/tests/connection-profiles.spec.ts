import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  expectCitedSource,
  openNewLibraryWizard,
  startFreshChat,
} from '../fixtures/chat'
import {
  acceptConfirm,
  createOwnAddressRssLibrary,
  createReleasedGroupWithMember,
  deleteGroupViaApi,
  gotoConnectionProfiles,
  gotoLibrary,
  openEvidenceRow,
  profileRow,
  referenceLibrary,
  deleteProfileViaApi,
  profileIdsByPrefix,
  resetRssProfileRequirement,
  runCleanupSteps,
  runIndexingViaApi,
  unlockRssConnector,
  triggerIndexingInUi,
} from '../fixtures/connectionProfiles'
import { deleteLibraryCompletely, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import { assignLibraryToDefaultSpace } from '../fixtures/spaces'
import type { Page } from '@playwright/test'

// Zugänge, Freigabe und Profilpflicht über den vollen Stack (test(e2e) #2175, Szenarien 1 und 2;
// docs/handbuch/indexierung.md, Abschnitt „Zugänge“). Quellart ist RSS-Feed gegen den suite-eigenen
// `rss-feed`-Dienst - die einzige Quelle dieses Stacks, die ohne Anmeldung einen Lauf mit Inhalt
// liefert. Handelnde: `dev-admin` als Systemverwaltung, `dev-user` als Mitglied der Gruppe, der der
// Zugang freigegeben wird, und `dev-outsider` als Person außerhalb.
//
// `test.describe.serial`: Die Szenarien bauen eine Installation weiter - Szenario 2 braucht den
// Zugang aus Szenario 1, denn ohne passenden Zugang lässt sich die Profilpflicht nicht einschalten.
// Das Aufräumen setzt die Quellart RSS-Feed zurück (keine Pflicht, nicht gesperrt), entfernt
// Bibliotheken, Zugänge und die Gruppe; sonst scheiterten die RSS-Szenarien späterer Dateien an
// einer liegengebliebenen Pflicht.
const runId = Date.now()
const PREFIX = `E2E Zugang ${runId}`
const PROFILE_NAME = `${PREFIX} RSS`
const REQUESTED_PROFILE_NAME = `${PREFIX} aus Wunsch`
const GROUP_NAME = `E2E Zugangsgruppe ${runId}`
// Ohne Leerzeichen: die @-Referenz im Chat endet am ersten Leerzeichen (space-chats.spec.ts).
const PROFILE_LIBRARY = `E2E-Zugang-Feed-${runId}`
const STOCK_LIBRARY = `E2E-Zugang-Bestand-${runId}`

// Ohne Schrägstrich am Ende: so speichert OPAA die Server-Adresse.
const SERVER_URL = 'http://rss-feed'
const FEED_URL = 'http://rss-feed/feed-ok.xml'
// Ein Eintrag von feed-ok.xml; sein Titel ist der Dateiname des Dokuments (rss-feed-library.spec.ts).
const ENTRY_TITLE = 'Öffnungszeiten der Bürgerauskunft angepasst'
// Eine Adresse, die nur dieser Lauf vorschlägt: Derselbe offene Wunsch entsteht kein zweites Mal.
const REQUESTED_SERVER_URL = `http://rss-feed/wunsch-${runId}`

// „Stand vom“ mit dem Datum des letzten erfolgreichen Laufs, nicht „unbekannt“.
const STAND_VOM = /Stand vom \d{2}\.\d{2}\.\d{4}/

const createdLibraryIds: string[] = []
let groupId: string | null = null
let stockLibraryId: string

/** Der Reiter „Quelle“ einer Bibliothek, mit dem Hinweis einer Sperre, falls es einen gibt. */
async function openSourceTab(page: Page, libraryId: string): Promise<void> {
  await gotoLibrary(page, libraryId)
  await page.getByRole('tab', { name: 'Quelle' }).click()
}

function lockNotice(page: Page) {
  return page.getByTestId('source-lock-notice')
}

/** Im Assistenten bis zum Schritt „Quelle“ einer RSS-Bibliothek. */
async function openRssSourceStep(page: Page): Promise<void> {
  await openNewLibraryWizard(page)
  await page.getByRole('radio', { name: /RSS-Feed/ }).click()
  await page.getByRole('button', { name: 'Weiter', exact: true }).click()
  await expect(
    page.getByRole('heading', {
      level: 2,
      name: 'Woher kommen die Dokumente?',
    }),
  ).toBeVisible()
}

/** Die Wahl „Zugang“ im Schritt „Quelle“ (ConnectionProfileSelect). */
function connectionChoice(page: Page) {
  return page.getByRole('radiogroup', { name: 'Zugang' })
}

/** Fragt im eigenen Chat mit @-Referenz und öffnet die Belegzeile des Feed-Eintrags. */
async function askAboutLibrary(page: Page, libraryName: string) {
  await startFreshChat(page)
  await referenceLibrary(page, libraryName)
  await askQuestion(page, `Was steht in ${libraryName} zu den Öffnungszeiten?`)
  return openEvidenceRow(page, ENTRY_TITLE)
}

test.describe.serial('Zugänge, Freigabe und Profilpflicht (#2175)', () => {
  test.beforeAll(async () => {
    groupId = await createReleasedGroupWithMember(GROUP_NAME, 'Dev User')
  })

  test.afterAll(async ({ request }) => {
    // Die Profilpflicht zuerst: Sie ist globaler Zustand und bräche die RSS-Szenarien späterer
    // Dateien.
    await runCleanupSteps([
      ['Profilpflicht RSS-Feed', resetRssProfileRequirement],
      ['Sperre RSS-Feed', unlockRssConnector],
      ...createdLibraryIds.map((libraryId): [string, () => Promise<void>] => [
        `Bibliothek ${libraryId}`,
        () => deleteLibraryCompletely(request, libraryId),
      ]),
      [
        'Zugänge',
        async () =>
          runCleanupSteps(
            (await profileIdsByPrefix(PREFIX)).map((profileId) => [
              `Zugang ${profileId}`,
              () => deleteProfileViaApi(profileId),
            ]),
          ),
      ],
      ['Gruppe', async () => (groupId ? deleteGroupViaApi(groupId) : undefined)],
    ])
  })

  test('1a. Systemverwaltung legt einen RSS-Zugang an und gibt ihn einer Gruppe frei', async ({
    authenticatedPage: page,
  }) => {
    await gotoConnectionProfiles(page)
    await page.getByRole('button', { name: 'Neuer Zugang' }).click()
    const form = page.getByRole('dialog', { name: 'Zugang anlegen' })
    await form.getByRole('radio', { name: /RSS-Feed/ }).click()
    await form.getByLabel('Name').fill(PROFILE_NAME)
    await form.getByLabel('Server-Adresse').fill(SERVER_URL)
    await form.getByRole('combobox', { name: 'Anmeldeart' }).click()
    await page.getByRole('option', { name: 'Ohne Anmeldung' }).click()
    await expect(form.getByRole('combobox', { name: 'Besitzart' })).toHaveText('Bibliothek')
    await form.getByRole('button', { name: 'Anlegen' }).click()
    await expect(form).toHaveCount(0)

    const row = profileRow(page, PROFILE_NAME)
    await expect(row).toContainText('RSS-Feed')
    await expect(row).toContainText(SERVER_URL)
    await expect(row).toContainText('Ohne Anmeldung')

    // Freigabe = Anlegerecht „Konnektorbibliotheken anlegen“ für den Zugang (Handbuch: „Wer einen
    // Zugang nutzen darf“). Ein neuer Zugang ist für niemanden außer der Systemverwaltung frei.
    await page.goto('/admin/capabilities')
    const card = page.getByRole('region', {
      name: 'Konnektorbibliotheken anlegen',
    })
    const scope = card.getByRole('region', { name: `Zugang ${PROFILE_NAME}` })
    await expect(scope).toContainText('Niemand hält dieses Anlegerecht.')
    await card.getByRole('combobox', { name: 'Geltungsbereich' }).click()
    await page.getByRole('option', { name: `Zugang ${PROFILE_NAME}` }).click()
    const groupInput = card.getByRole('combobox', { name: 'Gruppe' })
    await groupInput.click()
    await groupInput.fill(GROUP_NAME)
    await page.getByRole('option', { name: new RegExp(GROUP_NAME) }).click()
    await card.getByRole('button', { name: 'Erteilen' }).click()
    await expect(scope).toContainText(GROUP_NAME)
  })

  test('1b. Gruppenmitglied legt eine Bibliothek über den Zugang an, sie läuft und wird gefunden', async ({
    regularUserPage: page,
  }) => {
    await openRssSourceStep(page)
    const choice = connectionChoice(page)
    await expect(choice.getByRole('radio', { name: /^Eigene Adresse/ })).toBeChecked()
    // Gegenprobe zum Wegfall unten: mit eigener Adresse fragt das Formular Zugangsdaten ab.
    const credentials = page.getByLabel('Anmeldedaten (optional)')
    await expect(credentials).toBeVisible()
    await choice.getByRole('radio', { name: new RegExp(`^${PROFILE_NAME}`) }).click()

    // Die Adresse kommt aus dem Zugang; ein Pfad darunter ist erlaubt.
    const address = page.getByLabel('Adresse (URL)')
    await expect(address).toHaveValue(SERVER_URL)
    // „ohne Anmeldung“: kein Feld für Zugangsdaten.
    await expect(credentials).toHaveCount(0)
    await address.fill(FEED_URL)
    await page.getByRole('button', { name: 'Verbindung testen' }).click()
    await expect(page.getByRole('alert').filter({ hasText: /erreichbar/ })).toBeVisible({
      timeout: 15_000,
    })
    await page
      .getByRole('switch', {
        name: 'Erste Indizierung sofort nach dem Anlegen starten',
      })
      .uncheck()
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await page.getByLabel('Name', { exact: true }).fill(PROFILE_LIBRARY)
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    const libraryId = libraryIdFromCurrentUrl(page)
    createdLibraryIds.push(libraryId)

    await page.getByRole('tab', { name: 'Quelle' }).click()
    await expect(page.getByTestId('connection-profile')).toHaveText(`Zugang: ${PROFILE_NAME}`)

    const status = await triggerIndexingInUi(page, libraryId)
    expect(status.status).toBe('COMPLETED')
    expect(status.documentCount).toBe(2)

    await assignLibraryToDefaultSpace('dev-user', PROFILE_LIBRARY)
    await startFreshChat(page)
    await referenceLibrary(page, PROFILE_LIBRARY)
    await askQuestion(page, 'Wann hat die Bürgerauskunft geöffnet?')
    await expectCitedSource(page, ENTRY_TITLE)
  })

  test('1c. Person außerhalb der Gruppe sieht den Zugang gesperrt, mit Hinweis auf die Systemverwaltung', async ({
    outsiderPage: page,
  }) => {
    await openRssSourceStep(page)
    const tile = connectionChoice(page).getByRole('radio', {
      name: new RegExp(`^${PROFILE_NAME}`),
    })
    await expect(tile).toBeDisabled()
    await expect(tile).toContainText(
      `Ihnen fehlt das Anlegerecht „Konnektorbibliotheken anlegen“ für den Zugang „${PROFILE_NAME}“.`,
    )
    await expect(tile).toContainText('Wenden Sie sich an die Systemverwaltung')
  })

  test('2a. „Nur über Zugänge“ mit Bestandswahl „Sperren“ sperrt eine Bibliothek mit eigener Adresse', async ({
    authenticatedPage: adminPage,
    regularUserPage: page,
  }) => {
    // Vorbedingung: eine Bestandsbibliothek von dev-user mit eigener Adresse und einem Lauf.
    stockLibraryId = await createOwnAddressRssLibrary('dev-user', STOCK_LIBRARY, FEED_URL)
    createdLibraryIds.push(stockLibraryId)
    expect((await runIndexingViaApi('dev-user', stockLibraryId)).status).toBe('COMPLETED')
    await assignLibraryToDefaultSpace('dev-user', STOCK_LIBRARY)

    await gotoConnectionProfiles(adminPage)
    await adminPage.getByRole('switch', { name: 'Nur über Zugänge für RSS-Feed' }).click()
    const dialog = adminPage.getByRole('dialog', {
      name: '„Nur über Zugänge“ für RSS-Feed einschalten',
    })
    // Der RSS-Hinweis: Die Pflicht legt nur die Feed-Adresse fest, nicht die Detailseiten.
    await expect(dialog.getByTestId('profile-requirement-coverage')).toContainText('Detailseiten')
    await expect(
      dialog.getByRole('list', { name: 'Bibliotheken mit eigener Adresse' }),
    ).toContainText(`${STOCK_LIBRARY} · Konto Dev User`)
    await dialog.getByRole('radio', { name: /^Sperren/ }).check()
    await dialog.getByRole('button', { name: 'Einschalten' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(adminPage.getByRole('table', { name: 'Quellarten' })).toContainText(
      'Bibliotheken mit eigener Adresse sind gesperrt.',
    )

    // Die Bestandsbibliothek: Hinweis im Reiter „Quelle“, kein Lauf.
    await openSourceTab(page, stockLibraryId)
    await expect(lockNotice(page)).toContainText('nur noch über Zugänge nutzbar')
    const status = await triggerIndexingInUi(page, stockLibraryId)
    expect(status.status).toBe('FAILED')
    expect(status.message).toContain('nur noch über Zugänge nutzbar')

    // Der Beleg im Chat trägt „Stand vom …“ mit den Verwaltenden als zuständiger Stelle.
    const row = await askAboutLibrary(page, STOCK_LIBRARY)
    await expect(row).toContainText(STAND_VOM)
    await expect(row).toContainText('zuständig: Verwaltende der Bibliothek')
    await page.keyboard.press('Escape')
  })

  test('2b. Im Assistenten gibt es für RSS keine eigene Adresse mehr', async ({
    regularUserPage: page,
  }) => {
    await openRssSourceStep(page)
    const choice = connectionChoice(page)
    await expect(choice.getByRole('radio', { name: /^Eigene Adresse/ })).toHaveCount(0)
    await expect(
      page.getByText(
        'Die Quellart „RSS-Feed“ ist nur über einen Zugang nutzbar; eine eigene Adresse ist nicht möglich.',
      ),
    ).toBeVisible()
    await expect(choice.getByRole('radio', { name: new RegExp(`^${PROFILE_NAME}`) })).toBeChecked()
  })

  test('2c. Die Verwaltende repariert die gesperrte Bibliothek über „Zugang zuordnen“', async ({
    regularUserPage: page,
  }) => {
    await openSourceTab(page, stockLibraryId)
    await lockNotice(page).getByRole('button', { name: 'Zugang zuordnen' }).click()
    const dialog = page.getByRole('dialog', { name: 'Zugang zuordnen' })
    await dialog.getByRole('radio', { name: new RegExp(`^${PROFILE_NAME}`) }).click()
    // Die Adresse liegt unter dem Zugang und wandert unverändert mit.
    await expect(dialog.getByTestId('library-connection-address')).toContainText(FEED_URL)
    await dialog.getByRole('button', { name: 'Verbindung prüfen' }).click()
    await expect(dialog.getByTestId('library-connection-test')).toContainText('erreichbar', {
      timeout: 15_000,
    })
    await dialog.getByRole('button', { name: 'Prüfen und zuordnen' }).click()
    await expect(dialog).toHaveCount(0)

    await expect(lockNotice(page)).toHaveCount(0)
    await expect(page.getByTestId('connection-profile')).toHaveText(`Zugang: ${PROFILE_NAME}`)
    const status = await triggerIndexingInUi(page, stockLibraryId)
    expect(status.status).toBe('COMPLETED')
  })

  test('2d. Sperre des Zugangs stoppt die Läufe und zeigt den Hinweis; Entsperren stellt wieder her', async ({
    authenticatedPage: adminPage,
    regularUserPage: page,
  }) => {
    await gotoConnectionProfiles(adminPage)
    await profileRow(adminPage, PROFILE_NAME)
      .getByRole('button', { name: `${PROFILE_NAME} sperren` })
      .click()
    await acceptConfirm(adminPage, `Den Zugang „${PROFILE_NAME}“ sperren?`)
    await expect(profileRow(adminPage, PROFILE_NAME)).toContainText('Gesperrt')

    await openSourceTab(page, stockLibraryId)
    await expect(lockNotice(page)).toContainText('Gesperrt – Inhalt wird nicht mehr aktualisiert')
    const blocked = await triggerIndexingInUi(page, stockLibraryId)
    expect(blocked.status).toBe('FAILED')
    expect(blocked.message).toContain('Gesperrt – Inhalt wird nicht mehr aktualisiert')

    const row = await askAboutLibrary(page, STOCK_LIBRARY)
    await expect(row).toContainText(STAND_VOM)
    await expect(row).toContainText('zuständig: Systemverwaltung')
    await page.keyboard.press('Escape')

    await gotoConnectionProfiles(adminPage)
    await profileRow(adminPage, PROFILE_NAME)
      .getByRole('button', { name: `Sperre von ${PROFILE_NAME} aufheben` })
      .click()
    await acceptConfirm(adminPage, `Sperre für den Zugang „${PROFILE_NAME}“ aufheben?`)
    await expect(profileRow(adminPage, PROFILE_NAME)).not.toContainText('Gesperrt')

    await openSourceTab(page, stockLibraryId)
    await expect(lockNotice(page)).toHaveCount(0)
    const resumed = await triggerIndexingInUi(page, stockLibraryId)
    expect(resumed.status).toBe('COMPLETED')
  })

  test('3. Zugangswunsch: eine Person schlägt einen Zugang vor, die Verwaltung legt ihn daraus an', async ({
    authenticatedPage: adminPage,
    outsiderPage: page,
  }) => {
    // Unter der Profilpflicht und ohne freigegebenen Zugang ist RSS für dev-outsider schon im
    // Schritt „Art des Wissens“ nicht wählbar; dort steht „Zugang vorschlagen“.
    await openNewLibraryWizard(page)
    await expect(page.getByRole('radio', { name: /RSS-Feed/ })).toBeDisabled()
    await page.getByRole('button', { name: 'Zugang für RSS-Feed vorschlagen' }).click()
    const dialog = page.getByRole('dialog', { name: 'Zugang vorschlagen' })
    await dialog.getByLabel('Server-Adresse').fill(REQUESTED_SERVER_URL)
    await dialog.getByLabel('Begründung').fill('Mitteilungen der Beispielbehörde')
    await dialog.getByRole('button', { name: 'Vorschlagen' }).click()
    await expect(dialog).toHaveCount(0)
    const mine = page.getByRole('list', { name: 'Ihre Zugangswünsche' })
    await expect(mine).toContainText(REQUESTED_SERVER_URL)
    await expect(mine).toContainText('Offen')

    await gotoConnectionProfiles(adminPage)
    await adminPage
      .getByRole('button', {
        name: `Zugang für ${REQUESTED_SERVER_URL} anlegen`,
      })
      .click()
    const form = adminPage.getByRole('dialog', { name: 'Zugang anlegen' })
    await expect(form.getByTestId('connection-profile-from-request')).toContainText('Dev Outsider')
    await expect(form.getByRole('radio', { name: /RSS-Feed/ })).toBeChecked()
    await expect(form.getByLabel('Server-Adresse')).toHaveValue(REQUESTED_SERVER_URL)
    await form.getByLabel('Name').fill(REQUESTED_PROFILE_NAME)
    await form.getByRole('combobox', { name: 'Anmeldeart' }).click()
    await adminPage.getByRole('option', { name: 'Ohne Anmeldung' }).click()
    await form.getByRole('button', { name: 'Anlegen' }).click()
    await expect(form).toHaveCount(0)
    await expect(profileRow(adminPage, REQUESTED_PROFILE_NAME)).toContainText(REQUESTED_SERVER_URL)
    await expect(
      adminPage.getByRole('button', {
        name: `Zugang für ${REQUESTED_SERVER_URL} anlegen`,
      }),
    ).toHaveCount(0)

    await openNewLibraryWizard(page)
    const done = page.getByRole('list', { name: 'Ihre Zugangswünsche' })
    await expect(done).toContainText('Erledigt')
    await expect(done).toContainText(`Zugang „${REQUESTED_PROFILE_NAME}“`)

    // Ausschalten der Pflicht über die Oberfläche: fragt nach, danach ist eine eigene Adresse
    // wieder möglich.
    await gotoConnectionProfiles(adminPage)
    await adminPage.getByRole('switch', { name: 'Nur über Zugänge für RSS-Feed' }).click()
    await acceptConfirm(adminPage, '„Nur über Zugänge“ für die Quellart „RSS-Feed“ ausschalten?')
    await expect(
      adminPage.getByRole('switch', { name: 'Nur über Zugänge für RSS-Feed' }),
    ).not.toBeChecked()
  })
})
