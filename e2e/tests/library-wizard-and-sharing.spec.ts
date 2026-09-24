import { expect, test } from '../fixtures/auth'
import { gotoLibraries } from '../fixtures/chat'
import { cleanupLibraries, libraryIdFromCurrentUrl } from '../fixtures/libraries'
import type { Page } from '@playwright/test'

// The end-to-end path of Epic #1927 (#1944): creation wizard -> detail page -> tab "Freigaben",
// once for an UPLOAD and once for a connector library (HTTP_DIRECTORY against this suite's own
// "rss-feed" service, the same fixture #514/#547 use). What it pins down is the structure
// #1939/#1940/#1941/#1942 built: every wizard step carries the name of the tab it becomes, and the
// three sharing paths of that tab (person, "Alle Konten", catalog) actually reach the library.
//
// The other knowledge-library scenarios (#424, #547) cover upload, search and source
// configuration; this file covers only the way through wizard and tabs.
const runId = Date.now()

const UPLOAD_LIBRARY = `E2E Assistent Upload ${runId}`
const CONNECTOR_LIBRARY = `E2E Assistent Webverzeichnis ${runId}`

/** The static web-directory fixture served inside this suite's stack (#514, #547). */
const DIRECTORY_URL = 'http://rss-feed/webverzeichnis/'

/**
 * One section of a tab: since #1608 PageSection renders a `section` with `aria-labelledby` on its
 * heading, i.e. a named `region`. Scopes every assertion to the section it is about - "Dev User"
 * also appears in the access derivation further down the same tab.
 */
function section(page: Page, title: string) {
  return page.getByRole('region', { name: title })
}

/** The step the wizard currently stands on - its heading is the focus target (#1942). */
async function expectWizardStep(page: Page, heading: string): Promise<void> {
  await expect(page.getByRole('heading', { level: 2, name: heading })).toBeVisible()
}

async function nextStep(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Weiter', exact: true }).click()
}

/** The four tabs of the detail page; an UPLOAD library has no tab "Quelle" (#1939). */
async function expectTabs(page: Page, withSource: boolean): Promise<void> {
  await expect(page.getByRole('tab', { name: 'Dokumente' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Quelle' })).toHaveCount(withSource ? 1 : 0)
  await expect(page.getByRole('tab', { name: 'Metadaten' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Freigaben' })).toBeVisible()
}

/**
 * The three sharing paths of the tab on one library: a person, "Alle Konten" with its
 * confirmation, and the catalog switch. The owner section sits above them (#1941).
 */
async function walkThroughSharingTab(page: Page): Promise<void> {
  await page.getByRole('tab', { name: 'Freigaben' }).click()

  // 1. Owner - new on the detail page, with the transfer for the owner themselves.
  const owner = section(page, 'Eigentümer')
  await expect(owner).toBeVisible()
  await expect(owner.getByRole('button', { name: 'Eigentum übergeben' })).toBeVisible()

  // 2. Grants - a list on the page, no longer behind a "Rechte verwalten" dialog.
  const grants = section(page, 'Berechtigungen')
  await grants.getByRole('button', { name: 'Freigeben' }).click()
  const personInput = grants.getByRole('combobox', { name: 'Person suchen' })
  await personInput.click()
  await personInput.fill('Dev User')
  await page.getByRole('option', { name: /Dev User/ }).click()
  await grants.getByRole('button', { name: 'Freigeben' }).last().click()
  // The row's revoke button, not its subject name: the name also sits in the recipient picker of
  // the form, which can still be open at the moment of the assertion - an ambiguity Playwright
  // rejects outright instead of waiting it out.
  await expect(grants.getByRole('button', { name: 'Freigabe für Dev User entziehen' })).toBeVisible()

  // 3. "Alle Konten" is a recipient like any other (#1931) - with a confirmation in front of it
  //    that spells out the reach (ADR-0037).
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

  // 4. The catalog switch is a section of its own with its own save button - set in the wizard,
  //    taken back here.
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

  // The saved state, not the draft in the form: after a reload it still stands.
  await page.reload()
  await page.getByRole('tab', { name: 'Freigaben' }).click()
  await expect(
    section(page, 'Im Katalog auffindbar').getByRole('checkbox', {
      name: 'Im Katalog auffindbar, auch ohne Berechtigung',
    }),
  ).not.toBeChecked()
}

test.describe('Anlage-Assistent und Reiter „Freigaben" (#1944)', () => {
  const createdLibraryIds = cleanupLibraries()

  test('Upload-Bibliothek: drei Schritte, Detailansicht ohne Reiter „Quelle", Freigaben', async ({
    authenticatedPage: page,
  }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()

    // Step 1: kind of knowledge - one tile per source type, "Upload" preselected.
    await expectWizardStep(page, 'Welche Art von Wissen soll hier stehen?')
    const uploadTile = page.getByRole('radio', { name: /^Upload/ })
    await expect(uploadTile).toHaveAttribute('aria-checked', 'true')
    await nextStep(page)

    // An UPLOAD library has no source, so the next step is already "Name & Beschreibung".
    await expectWizardStep(page, 'Name & Beschreibung')
    await page.getByLabel('Name', { exact: true }).fill(UPLOAD_LIBRARY)
    await page.getByLabel('Beschreibung (optional)').fill('Handakte des Assistenten-Durchlaufs')
    await nextStep(page)

    await expectWizardStep(page, 'Freigaben')
    await page
      .getByRole('checkbox', { name: 'Im Katalog auffindbar, auch ohne Berechtigung' })
      .check()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    // The header: name, description, and the source type exactly once, as a badge (#1939).
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

    // Step 2: source - connection, connection test, schedule and the immediate first run, which
    // since #1942 exists for every connector type. It stays off here: this is about the path, not
    // about a run.
    await expectWizardStep(page, 'Woher kommen die Dokumente?')
    await page.getByLabel('Adresse (URL)').fill(DIRECTORY_URL)
    await expect(page.getByRole('button', { name: 'Verbindung testen' })).toBeVisible()
    await expect(page.getByRole('heading', { level: 3, name: 'Zeitplan' })).toBeVisible()
    await page
      .getByRole('switch', { name: 'Erste Indizierung sofort nach dem Anlegen starten' })
      .uncheck()
    await nextStep(page)

    // Step 3: the name comes prefilled from the source - here its hostname - and stays editable.
    await expectWizardStep(page, 'Name & Beschreibung')
    const nameField = page.getByLabel('Name', { exact: true })
    await expect(nameField).toHaveValue('rss-feed')
    await nameField.fill(CONNECTOR_LIBRARY)
    await nextStep(page)

    await expectWizardStep(page, 'Freigaben')
    await page
      .getByRole('checkbox', { name: 'Im Katalog auffindbar, auch ohne Berechtigung' })
      .check()
    await Promise.all([
      page.waitForURL(/\/libraries\/(?!new$)[^/]+$/),
      page.getByRole('button', { name: 'Bibliothek anlegen' }).click(),
    ])
    createdLibraryIds.push(libraryIdFromCurrentUrl(page))

    await expect(page.getByRole('heading', { level: 1, name: CONNECTOR_LIBRARY })).toBeVisible()
    await expectTabs(page, true)

    // The tab "Quelle" carries the four sections of the target design (#1940); "Umfang" is absent
    // for this source type, and "Anbindung" shows the stored address.
    await page.getByRole('tab', { name: 'Quelle' }).click()
    await expect(section(page, 'Anbindung')).toContainText(DIRECTORY_URL)
    await expect(section(page, 'Zeitplan')).toBeVisible()
    await expect(section(page, 'Läufe')).toBeVisible()

    await walkThroughSharingTab(page)
  })
})
