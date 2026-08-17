import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import type { Page } from '@playwright/test'

// Deterministic, tiny, and part of the repo (see AGENTS.md "Reproduktionsnachweis" context on
// preferring committed fixtures over ad-hoc generated data) - not one of
// backend/src/test/resources/test-documents/, which is Java test scope, not reachable from this
// suite's own npm project. Lives under fixtures/test-documents/, not fixtures/documents/: the
// repo's root .gitignore has a blanket `documents/` rule (for the bind-mounted, machine-local
// backend/src/main/resources demo corpus path), which would silently swallow anything under a
// plain "documents" directory anywhere in the tree.
const TEST_DOCUMENT_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'fixtures',
  'test-documents',
  'wissensdokument.txt',
)
const TEST_DOCUMENT_NAME = 'wissensdokument.txt'

const QUESTION = 'Was steht im Wissensdokument?'
const NO_CONTEXT_ANSWER = 'Dazu liegen mir keine Informationen in den zugänglichen Dokumenten vor.'

// Unique per run so re-runs against a stack that was not torn down (or a shared dev stack) never
// collide with a leftover library/group of the same name.
const runId = Date.now()
const LIBRARY_NAME = `E2E Wissensbibliothek ${runId}`
const GROUP_NAME = `E2E Gruppe ${runId}`

async function askQuestion(page: Page, question: string) {
  const input = page.getByPlaceholder('Stellen Sie eine Frage …')
  await input.fill(question)
  await page.getByRole('button', { name: 'Nachricht senden' }).click()
}

async function expectCitedSource(page: Page, fileName: string) {
  const card = page.getByTestId('source-card').filter({ hasText: fileName })
  await expect(card).toHaveAttribute('data-cited', 'true', { timeout: 15_000 })
}

async function expectNoSourceFor(page: Page, fileName: string) {
  await expect(page.getByText(NO_CONTEXT_ANSWER)).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('source-card').filter({ hasText: fileName })).toHaveCount(0)
}

// Idempotent, unlike a plain click on the summary: a MUI Accordion re-collapses on a second click,
// and a store refresh that fires while it is open (e.g. GroupCard's addMember, which reloads the
// whole group list) can leave it collapsed again by the time the next assertion runs. Checking
// aria-expanded first means this always ends up open, whichever state it started in.
async function ensureAccordionExpanded(page: Page, name: string) {
  const summary = page.getByRole('button', { name })
  if ((await summary.getAttribute('aria-expanded')) !== 'true') {
    await summary.click()
  }
}

// GET /api/v1/libraries is what decides whether a library is listed at all - the very thing
// scenarios 4 and 5 assert the *absence* of. `toHaveCount(0)` alone would pass just as happily
// before that request has even returned as after it confirmed absence, so this waits for the
// concrete response instead of a fixed delay (see AGENTS.md / e2e/README.md "Serialisierungs-
// Konvention" on not hanging scenarios on wall-clock time).
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
 * Covers test(e2e) #424: the full upload -> share -> find chain from Epic #198's "intermediate
 * state" resolution, and its negative counterpart - a share must never leak to someone it was not
 * extended to. Scenarios run in dependency order (each builds on library/group state the previous
 * one created) against the one library/document created in scenario 1, which keeps the suite
 * inside its runtime budget (only a single indexing call - see opaa.rate-limit.indexing, at most
 * one request per window).
 *
 * Scenarios 4 and 5 (the negative cases) are the ones that actually exercise
 * io.opaa.query.QueryService#libraryFilter, the permission filter applied directly to the vector
 * store query (see its Javadoc). Verified manually per AGENTS.md's Reproduktionsnachweis pattern,
 * applied to a pre-existing feature rather than a fix: with `.filterExpression(...)` removed from
 * that call and `readableLibraryIds.isEmpty() ? List.of() : ...` short-circuit bypassed, scenario 4
 * fails with "expected 0 source-card elements, found 1" (Nutzer C suddenly finds Nutzer A's
 * document) and scenario 5 fails the same way for Nutzer B after revocation - both restored to
 * green with the filter back in place. See the PR description for the exact failure output.
 */
test.describe.serial('Wissensbibliotheken: Upload, Freigabe, rechtebewusste Suche (#424)', () => {
  test('1. Eigene Bibliothek anlegen und befüllen', async ({ authenticatedPage: page }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    // Not just page.getByLabel('Name'): MUI Accordion keeps a collapsed LibraryCard's fields
    // mounted (just visually hidden), so the personal library's "Name der Bibliothek" field is
    // also in the DOM and its label contains "Name" as a substring - scoping to the dialog is
    // what disambiguates this from the create dialog's own field (an additional `exact: true`
    // does not help here: the required "Name" field's computed accessible name includes the "*"
    // required marker, so an exact match against plain "Name" never resolves at all).
    await page.getByRole('dialog').getByLabel('Name').fill(LIBRARY_NAME)
    await page.getByRole('button', { name: 'Erstellen' }).click()
    await expect(page.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()

    await page.goto('/documents')
    await page.getByLabel('Bibliothek').click()
    await page.getByRole('option', { name: LIBRARY_NAME }).click()

    await page.getByLabel('Dateien auswählen').setInputFiles(TEST_DOCUMENT_PATH)

    await expect(page.getByText(TEST_DOCUMENT_NAME)).toBeVisible()
    await expect(page.getByText('indiziert')).toBeVisible({ timeout: 30_000 })
  })

  test('2. Suche findet das eigene Dokument', async ({ authenticatedPage: page }) => {
    await page.goto('/chat')
    await askQuestion(page, QUESTION)
    await expectCitedSource(page, TEST_DOCUMENT_NAME)
  })

  test('3. Freigeben und finden', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    // regularUserPage's fixture setup already logged dev-user in once (see fixtures/auth.ts),
    // which is what provisions the account GET /v1/admin/users below can find - without this, the
    // admin's picker would never list dev-user at all.
    await gotoLibraries(adminPage)
    await ensureAccordionExpanded(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).click()
    // Not getByLabel: once the Autocomplete's listbox is open, its aria-labelledby also points
    // back at "Person auswählen", so getByLabel resolves to both the input and the listbox.
    // getByRole('combobox', ...) only ever matches the input itself.
    const personInput = adminPage.getByRole('combobox', { name: 'Person auswählen' })
    await personInput.click()
    await personInput.fill('Dev User')
    await adminPage.getByRole('option', { name: /Dev User/ }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(adminPage.getByText('Dev User')).toBeVisible()
    await adminPage.getByRole('button', { name: 'Schließen' }).click()

    await gotoLibraries(bPage)
    await expect(bPage.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()

    await bPage.goto('/chat')
    await askQuestion(bPage, QUESTION)
    await expectCitedSource(bPage, TEST_DOCUMENT_NAME)
  })

  test('4. Negativfall: keine Freigabe, kein Treffer', async ({ outsiderPage: cPage }) => {
    await gotoLibraries(cPage)
    await expect(cPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await cPage.goto('/chat')
    await askQuestion(cPage, QUESTION)
    await expectNoSourceFor(cPage, TEST_DOCUMENT_NAME)
  })

  test('5. Entzug wirkt', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    await gotoLibraries(adminPage)
    await ensureAccordionExpanded(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    adminPage.once('dialog', (dialog) => dialog.accept())
    await adminPage.getByRole('button', { name: 'Freigabe für Dev User entziehen' }).click()
    // Not "the grant list is empty": the creator's own OWNER grant is a row in this same list and
    // outlives every other grant, so the list is never actually empty here - only "Dev User"'s row
    // is gone.
    await expect(adminPage.getByText('Dev User')).toHaveCount(0)
    await adminPage.getByRole('button', { name: 'Schließen' }).click()

    await gotoLibraries(bPage)
    await expect(bPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await bPage.goto('/chat')
    await askQuestion(bPage, QUESTION)
    await expectNoSourceFor(bPage, TEST_DOCUMENT_NAME)
  })

  test('6. Freigabe an eine Gruppe', async ({ authenticatedPage: adminPage, outsiderPage: cPage }) => {
    // outsiderPage's fixture setup provisions dev-outsider first, same reasoning as scenario 3.
    await adminPage.goto('/admin/groups')
    await adminPage.getByRole('button', { name: 'Neue Gruppe' }).click()
    // CreateGroupDialog's field is labelled "Name" (not "Name der Gruppe" - that label belongs to
    // the already-created GroupCard's own rename field instead), so this needs the same dialog
    // scoping as the library's "Name" field above, for the same reason.
    await adminPage.getByRole('dialog').getByLabel('Name').fill(GROUP_NAME)
    await adminPage.getByRole('button', { name: 'Erstellen' }).click()
    await expect(adminPage.getByText(GROUP_NAME)).toBeVisible()

    await ensureAccordionExpanded(adminPage, GROUP_NAME)
    const memberInput = adminPage.getByRole('combobox', { name: 'Benutzer' })
    await memberInput.click()
    await memberInput.fill('Dev Outsider')
    await adminPage.getByRole('option', { name: /Dev Outsider/ }).click()
    // addMember's loadGroups() flips GroupManagementPage's isLoading to true while it refetches,
    // which swaps out the entire list of GroupCards for a loading message - unmounting this card
    // and, with it, its local `expanded` state. ensureAccordionExpanded only helps once that
    // reload has actually finished (a freshly mounted card really does start collapsed); waiting
    // for the GET it triggers is what pins that moment down, rather than guessing at a delay.
    await Promise.all([
      adminPage.waitForResponse(
        (response) =>
          response.request().method() === 'GET' && response.url().endsWith('/api/v1/admin/groups'),
      ),
      adminPage.getByRole('button', { name: 'Mitglied hinzufügen' }).click(),
    ])
    await ensureAccordionExpanded(adminPage, GROUP_NAME)
    await expect(adminPage.getByText('Dev Outsider')).toBeVisible()

    await gotoLibraries(adminPage)
    await ensureAccordionExpanded(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).click()
    await adminPage.getByRole('radio', { name: 'Gruppe' }).click()
    const groupInput = adminPage.getByRole('combobox', { name: 'Gruppe auswählen' })
    await groupInput.click()
    await groupInput.fill(GROUP_NAME)
    await adminPage.getByRole('option', { name: GROUP_NAME }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(adminPage.getByText(GROUP_NAME)).toBeVisible()
    await adminPage.getByRole('button', { name: 'Schließen' }).click()

    await gotoLibraries(cPage)
    await expect(cPage.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()

    await cPage.goto('/chat')
    await askQuestion(cPage, QUESTION)
    await expectCitedSource(cPage, TEST_DOCUMENT_NAME)
  })

  test('7. Upload ohne Recht', async ({ outsiderPage: cPage }) => {
    await cPage.goto('/documents')
    await cPage.getByLabel('Bibliothek').click()
    await cPage.getByRole('option', { name: LIBRARY_NAME }).click()

    await expect(cPage.getByText('Sie haben in dieser Bibliothek nur Leserechte.')).toBeVisible()
    await expect(cPage.getByRole('button', { name: 'Dateien hochladen' })).toHaveCount(0)
    await expect(cPage.getByLabel('Dateien auswählen')).toHaveCount(0)
  })
})
