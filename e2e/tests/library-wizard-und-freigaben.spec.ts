import { expect, test } from '../fixtures/auth'
import { gotoLibraries } from '../fixtures/chat'
import type { APIRequestContext, Page } from '@playwright/test'

// Der durchgehende Pfad aus Epic #1927 (#1944): Anlegen über den Assistenten → Detailansicht →
// Reiter „Freigaben", einmal für eine Upload- und einmal für eine Konnektorbibliothek
// (Webverzeichnis gegen den suite-eigenen „rss-feed"-Dienst, wie in
// knowledge-library-nacharbeiten.spec.ts). Geprüft wird die Struktur, die #1939/#1940/#1941/#1942
// hergestellt haben - dass jeder Schritt des Assistenten denselben Namen trägt wie der Reiter, den
// er in der fertigen Bibliothek bekommt, und dass die drei Freigabewege des Reiters
// (Person, „Alle Konten", Katalog) tatsächlich an der Bibliothek ankommen.
//
// Die übrigen Wissensbibliotheks-Szenarien der Suite (#424, #547) decken Upload, Suche und
// Quellkonfiguration ab; hier geht es ausschließlich um den Weg durch Assistent und Reiter.
const runId = Date.now()

// Spiegelt frontend/src/services/devAuth.ts's DEV_USER_HEADER - e2e/ ist ein eigenes Paket ohne
// Abhängigkeit auf frontend/src (dasselbe Muster wie in knowledge-library-nacharbeiten.spec.ts).
const DEV_USER_HEADER = 'X-OPAA-Dev-User'

const UPLOAD_LIBRARY = `E2E Assistent Upload ${runId}`
const CONNECTOR_LIBRARY = `E2E Assistent Webverzeichnis ${runId}`

/** Die Adresse des statischen Webverzeichnis-Fixtures im Stack dieser Suite (#514, #547). */
const DIRECTORY_URL = 'http://rss-feed/webverzeichnis/'

/** Liest die Bibliotheks-ID, auf die der Assistent nach dem Anlegen navigiert hat. */
function libraryIdFromCurrentUrl(page: Page): string {
  const match = page.url().match(/\/libraries\/([^/]+)$/)
  if (!match) throw new Error(`Unerwartete Adresse nach dem Anlegen: ${page.url()}`)
  return match[1]
}

/**
 * Ein Abschnitt des Reiters: seit #1608 ein `section` mit `aria-labelledby` auf seine Überschrift
 * (PageSection.tsx), also eine benannte `region`. Grenzt jede Zusicherung auf den Abschnitt ein,
 * um den es geht - „Dev User" steht sonst auch in der Herleitung darunter.
 */
function section(page: Page, title: string) {
  return page.getByRole('region', { name: title })
}

/** Der Schritt, auf dem der Assistent gerade steht - seine Überschrift trägt den Fokus (#1942). */
async function expectWizardStep(page: Page, heading: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 2, name: heading })).toBeVisible()
}

async function nextStep(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Weiter', exact: true }).click()
}

/** Die vier Reiter der Detailansicht; eine Upload-Bibliothek hat keinen Reiter „Quelle" (#1939). */
async function expectTabs(page: Page, withSource: boolean): Promise<void> {
  await expect(page.getByRole('tab', { name: 'Dokumente' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Quelle' })).toHaveCount(withSource ? 1 : 0)
  await expect(page.getByRole('tab', { name: 'Metadaten' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Freigaben' })).toBeVisible()
}

/**
 * Die drei Freigabewege des Reiters an genau einer Bibliothek: eine Person, „Alle Konten" mit
 * ihrer Rückfrage und der Katalog-Schalter. Der Eigentümer-Abschnitt steht darüber (#1941).
 */
async function walkThroughSharingTab(page: Page): Promise<void> {
  await page.getByRole('tab', { name: 'Freigaben' }).click()

  // 1. Eigentümer - neu in der Detailansicht, mit der Übergabe für den Eigentümer selbst.
  const owner = section(page, 'Eigentümer')
  await expect(owner).toBeVisible()
  await expect(owner.getByRole('button', { name: 'Eigentum übergeben' })).toBeVisible()

  // 2. Berechtigungen - als Liste auf der Seite, nicht mehr hinter „Rechte verwalten".
  const grants = section(page, 'Berechtigungen')
  await grants.getByRole('button', { name: 'Freigeben' }).click()
  const personInput = grants.getByRole('combobox', { name: 'Person suchen' })
  await personInput.click()
  await personInput.fill('Dev User')
  await page.getByRole('option', { name: /Dev User/ }).click()
  await grants.getByRole('button', { name: 'Freigeben' }).last().click()
  // Der Entzugsknopf der Zeile, nicht ihr Name: Der Name steht auch in der Empfängerauswahl des
  // Formulars, das im Moment der Zusicherung noch offen sein kann - eine Mehrdeutigkeit, die
  // Playwright hart abweist statt sie auszuwarten.
  await expect(grants.getByRole('button', { name: 'Freigabe für Dev User entziehen' })).toBeVisible()

  // 3. „Alle Konten" ist ein Empfänger wie jeder andere (#1931) - mit einer Rückfrage davor, die
  //    die Reichweite ausspricht (ADR-0037).
  await grants.getByRole('button', { name: 'Freigeben' }).click()
  await grants.getByRole('radio', { name: 'Alle Konten' }).click()
  await grants.getByRole('button', { name: 'Freigeben' }).last().click()
  const confirm = page.getByRole('dialog').filter({ has: page.locator('#confirm-question') })
  await expect(confirm).toContainText('An alle Konten freigeben?')
  await confirm.locator('#confirm-accept').click()
  await expect(confirm).toHaveCount(0)
  await expect(
    grants.getByRole('button', { name: 'Freigabe für Alle Konten entziehen' }),
  ).toBeVisible()

  // 4. Der Katalog-Schalter steht in einem eigenen Abschnitt mit eigenem Speichern - im
  //    Assistenten war er gesetzt, hier wird er zurückgenommen.
  const listed = section(page, 'Im Katalog auffindbar')
  const listedBox = listed.getByRole('checkbox', {
    name: 'Im Katalog auffindbar, auch ohne Berechtigung',
  })
  await expect(listedBox).toBeChecked()
  await listedBox.uncheck()
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        /\/api\/v1\/libraries\/[^/]+$/.test(new URL(response.url()).pathname) &&
        response.ok(),
    ),
    listed.getByRole('button', { name: 'Auffindbarkeit speichern' }).click(),
  ])

  // Der gespeicherte Zustand, nicht der Entwurf im Formular: nach dem Neuladen steht er immer noch.
  await page.reload()
  await page.getByRole('tab', { name: 'Freigaben' }).click()
  await expect(
    section(page, 'Im Katalog auffindbar').getByRole('checkbox', {
      name: 'Im Katalog auffindbar, auch ohne Berechtigung',
    }),
  ).not.toBeChecked()
}

async function deleteLibrary(request: APIRequestContext, libraryId: string): Promise<void> {
  const response = await request.delete(`/api/v1/libraries/${libraryId}`, {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  expect(response.ok()).toBe(true)
}

test.describe('Anlage-Assistent und Reiter „Freigaben" (#1944)', () => {
  const createdLibraryIds: string[] = []

  test.afterAll(async ({ request }) => {
    for (const libraryId of createdLibraryIds) {
      await deleteLibrary(request, libraryId)
    }
  })

  test('Upload-Bibliothek: drei Schritte, Detailansicht ohne Reiter „Quelle", Freigaben', async ({
    authenticatedPage: page,
  }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()

    // Schritt 1: Art des Wissens - Kacheln je Quellentyp, „Upload" ist vorausgewählt.
    await expectWizardStep(page, 'Welche Art von Wissen soll hier stehen?')
    const uploadTile = page.getByRole('radio', { name: /^Upload/ })
    await expect(uploadTile).toHaveAttribute('aria-checked', 'true')
    await nextStep(page)

    // Eine Upload-Bibliothek hat keine Quelle: der nächste Schritt ist schon „Name & Beschreibung".
    await expectWizardStep(page, 'Name & Beschreibung')
    await page.getByLabel('Name', { exact: true }).fill(UPLOAD_LIBRARY)
    await page.getByLabel('Beschreibung (optional)').fill('Handakte des Assistenten-Durchlaufs')
    await nextStep(page)

    await expectWizardStep(page, 'Freigaben')
    await page.getByRole('checkbox', { name: 'Im Katalog auffindbar, auch ohne Berechtigung' }).check()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    // Der Kopf: Name, Beschreibung und der Quellentyp genau einmal, als Abzeichen (#1939).
    await expect(page.getByRole('heading', { level: 1, name: UPLOAD_LIBRARY })).toBeVisible()
    await expect(page.getByText('Handakte des Assistenten-Durchlaufs')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Weitere Aktionen' })).toBeVisible()
    await expectTabs(page, false)

    await walkThroughSharingTab(page)
  })

  test('Konnektorbibliothek: Schritt „Quelle" mit Zeitplan, vorbelegter Name, Freigaben', async ({
    authenticatedPage: page,
  }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()

    await expectWizardStep(page, 'Welche Art von Wissen soll hier stehen?')
    await page.getByRole('radio', { name: /^Webverzeichnis/ }).click()
    await nextStep(page)

    // Schritt 2: Quelle - Anbindung, Verbindungstest, Zeitplan und der Sofortstart, seit #1942 für
    // jeden Konnektortyp. Der Sofortstart bleibt hier aus: geprüft wird der Weg, nicht ein Lauf.
    await expectWizardStep(page, 'Woher kommen die Dokumente?')
    await page.getByLabel('Adresse (URL)').fill(DIRECTORY_URL)
    await expect(page.getByRole('button', { name: 'Verbindung testen' })).toBeVisible()
    await expect(page.getByRole('heading', { level: 3, name: 'Zeitplan' })).toBeVisible()
    const startFirstRun = page.getByRole('switch', {
      name: 'Erste Indizierung sofort nach dem Anlegen starten',
    })
    await startFirstRun.uncheck()
    await nextStep(page)

    // Schritt 3: Der Name kommt aus der Quelle - hier ihr Hostname - und bleibt überschreibbar.
    await expectWizardStep(page, 'Name & Beschreibung')
    const nameField = page.getByLabel('Name', { exact: true })
    await expect(nameField).toHaveValue('rss-feed')
    await nameField.fill(CONNECTOR_LIBRARY)
    await nextStep(page)

    await expectWizardStep(page, 'Freigaben')
    await page.getByRole('checkbox', { name: 'Im Katalog auffindbar, auch ohne Berechtigung' }).check()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    await expect(page.getByRole('heading', { level: 1, name: CONNECTOR_LIBRARY })).toBeVisible()
    await expectTabs(page, true)

    // Der Reiter „Quelle" führt die vier Abschnitte des Zielentwurfs (#1940); „Umfang" entfällt
    // bei diesem Konnektortyp, die Anbindung trägt die gespeicherte Adresse.
    await page.getByRole('tab', { name: 'Quelle' }).click()
    await expect(section(page, 'Anbindung')).toContainText(DIRECTORY_URL)
    await expect(section(page, 'Zeitplan')).toBeVisible()
    await expect(section(page, 'Läufe')).toBeVisible()

    await walkThroughSharingTab(page)
  })
})
