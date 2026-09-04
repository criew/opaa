import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  createLibraryWithDocument,
  expectCitedExclusively,
  expectCitedSource,
  gotoLibraries,
  gotoLibraryDetail,
  shareLibraryWithPerson,
  startFreshChat,
} from '../fixtures/chat'
import type { Page } from '@playwright/test'

// Deterministic, tiny, and part of the repo (see AGENTS.md "Reproduktionsnachweis" context on
// preferring committed fixtures over ad-hoc generated data) - not one of
// backend/src/test/resources/test-documents/, which is Java test scope, not reachable from this
// suite's own npm project. Since #233, this suite's own frozen upload fixtures live under
// demo/seed/e2e-data/test-documents/, next to demo/seed/profiles.py's E2E_PROFILE that governs
// the same data profile - not under e2e/fixtures/ any more, which used to be a second, independent
// way to fill an instance (see docs/features/demo-instance.md, "Installation und Seed"). The
// repo's root .gitignore has a blanket `documents/` rule (for the bind-mounted, machine-local
// backend/src/main/resources demo corpus path); "test-documents" does not match it, but stays
// named that way for continuity with the file names below.
const TEST_DOCUMENT_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
  'wissensdokument.txt',
)
const TEST_DOCUMENT_NAME = 'wissensdokument.txt'

// Negative scenarios (4, 5) upload this into a library the acting user creates for the occasion
// (see uploadOwnDocument below) rather than asserting "zero results" against the shared library
// alone (see the module doc comment below and PR #453 review, nit 1): without a real, non-empty
// upload of their own, "zero source cards" would just as happily mean "the search never ran" as
// "the filter works", leaving both scenarios silently green without checking anything. A real,
// non-empty, own-vs-foreign distinction closes that gap. Before #522 this uploaded into the
// automatically provisioned personal library instead - that automation is gone, so each scenario
// now creates its own throwaway library first, the same way scenario 1 does for the shared one.
const OWN_DOCUMENT_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
  'eigenesdokument.txt',
)
const OWN_DOCUMENT_NAME = 'eigenesdokument.txt'

const QUESTION = 'Was steht im Wissensdokument?'

// Unique per run so re-runs against a stack that was not torn down (or a shared dev stack) never
// collide with a leftover library/group of the same name.
const runId = Date.now()
const LIBRARY_NAME = `E2E Wissensbibliothek ${runId}`
const GROUP_NAME = `E2E Gruppe ${runId}`
// One own throwaway library per negative scenario (4, 5) - each acting user (C, then B) creates
// and uploads into their own, never the other's, so a leftover from a previous run can never be
// mistaken for this run's upload (see OWN_DOCUMENT_PATH's comment for why this upload matters at
// all, and uploadOwnDocument for how it is created).
const OWN_LIBRARY_NAME_OUTSIDER = `E2E Eigene Bibliothek Outsider ${runId}`
const OWN_LIBRARY_NAME_REGULAR = `E2E Eigene Bibliothek Regular ${runId}`

// Chats are persisted server-side (#525/#527) and keyed per user, not per browser session: a
// fresh Playwright context (a new browser context per fixture, see fixtures/auth.ts) still talks
// to the same backend account, so `/chat` now restores whatever chat that account last used - not
// necessarily an empty one. `expectCitedSource`/`expectCitedExclusively` below assert page-wide,
// which is only correct on a chat that holds exactly the one turn just asked; without this, a
// later scenario reusing the same account (dev-user in scenarios 3 and 5, dev-outsider in
// scenarios 4 and 6) would see source cards from an earlier scenario's turn still in the DOM
// alongside the new one. Every scenario below explicitly starts a fresh, not-yet-persisted chat
// (`startFreshChat`, see fixtures/chat.ts) before asking its question instead, so "the page shows
// exactly this one turn" is a fact, not an assumption that happened to hold by scenario order (CI
// fix following PR #548's review).

// Creates a fresh library named libraryName for the acting user (mirroring scenario 1's own
// creation step) and uploads OWN_DOCUMENT_PATH into it. Since #522 removed the automatically
// provisioned personal library, there is no existing library to rely on for this upload - it must
// be created here, on demand, same as any other library a user wants.
async function uploadOwnDocument(page: Page, libraryName: string) {
  await createLibraryWithDocument(page, libraryName, OWN_DOCUMENT_PATH, OWN_DOCUMENT_NAME)
}

/**
 * Covers test(e2e) #424: the full upload -> share -> find chain from Epic #198's "intermediate
 * state" resolution, and its negative counterpart - a share must never leak to someone it was not
 * extended to. Scenarios run in dependency order (each builds on library/group state the previous
 * one created). Scenarios 1-3 and 6 share the one library/document created in scenario 1, kept to
 * a single upload there for runtime (not to stay under a rate limit: document upload goes through
 * POST /api/v1/libraries/{libraryId}/documents, which io.opaa.ratelimit.RateLimitConfiguration
 * never guards - only POST /api/v1/libraries/{libraryId}/indexing is, which this suite never
 * calls). Scenarios 4
 * and 5 each create one throwaway library of their own and upload one more document into it - see
 * OWN_DOCUMENT_PATH's comment for why.
 *
 * Scenarios 4 and 5 (the negative cases) are the ones that actually exercise
 * io.opaa.query.SearchScopeStage#libraryFilter, the permission filter applied directly to the vector
 * store query (see its Javadoc). Verified manually per AGENTS.md's Reproduktionsnachweis pattern,
 * applied to a pre-existing feature rather than a fix: with `.filterExpression(...)` removed from
 * that call and `readableLibraryIds.isEmpty() ? List.of() : ...` short-circuit bypassed, both
 * scenarios fail identically - the own document is still (rightfully) cited, so
 * `expectCitedExclusively`'s first assertion passes, but its second one does not:
 * `expect(locator).toHaveCount(expected) failed / Locator: getByTestId('source-card').filter({
 * hasText: 'wissensdokument.txt' }) / Expected: 0 / Received: 1` - the excluded user now finds the
 * other's document too. Both restored to green with the filter back in place.
 */
test.describe.serial('Wissensbibliotheken: Upload, Freigabe, rechtebewusste Suche (#424)', () => {
  test('1. Eigene Bibliothek anlegen und befüllen', async ({ authenticatedPage: page }) => {
    await createLibraryWithDocument(page, LIBRARY_NAME, TEST_DOCUMENT_PATH, TEST_DOCUMENT_NAME)
  })

  test('2. Suche findet das eigene Dokument', async ({ authenticatedPage: page }) => {
    await startFreshChat(page)
    await askQuestion(page, QUESTION)
    await expectCitedSource(page, TEST_DOCUMENT_NAME)
  })

  test('3. Freigeben und finden', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    // dev-user is already provisioned by now regardless of this test's own regularUserPage fixture
    // - the #233 seed run (scripts/run-e2e.mjs, before Playwright starts) already logs it in once
    // as part of provision_users (demo/seed/seed.py). That authenticated request is what GET
    // /v1/users (#777) below actually needs to find the account; without either the seed or
    // regularUserPage having done it, the admin's picker would never list dev-user at all.
    await shareLibraryWithPerson(adminPage, LIBRARY_NAME, 'Dev User', /Dev User/)

    await gotoLibraries(bPage)
    await expect(bPage.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()

    await startFreshChat(bPage)
    await askQuestion(bPage, QUESTION)
    await expectCitedSource(bPage, TEST_DOCUMENT_NAME)
  })

  test('4. Negativfall: keine Freigabe, kein Treffer', async ({ outsiderPage: cPage }) => {
    await uploadOwnDocument(cPage, OWN_LIBRARY_NAME_OUTSIDER)

    await gotoLibraries(cPage)
    await expect(cPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await startFreshChat(cPage)
    await askQuestion(cPage, QUESTION)
    await expectCitedExclusively(cPage, OWN_DOCUMENT_NAME, TEST_DOCUMENT_NAME)
  })

  test('5. Entzug wirkt', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    await gotoLibraries(adminPage)
    await gotoLibraryDetail(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('tab', { name: 'Verwaltung' }).click()
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    adminPage.once('dialog', (dialog) => dialog.accept())
    await adminPage.getByRole('button', { name: 'Freigabe für Dev User entziehen' }).click()
    // Not "the grant list is empty": the creator's own OWNER grant is a row in this same list and
    // outlives every other grant, so the list is never actually empty here - only "Dev User"'s row
    // is gone.
    await expect(adminPage.getByText('Dev User')).toHaveCount(0)
    // Scoped to the dialog: the deDE MUI locale also names an error Alert's own close button
    // "Schließen" (#784), so an unscoped lookup could resolve to two elements on the error path.
    await adminPage.getByRole('dialog').getByRole('button', { name: 'Schließen' }).click()

    await uploadOwnDocument(bPage, OWN_LIBRARY_NAME_REGULAR)

    await gotoLibraries(bPage)
    await expect(bPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await startFreshChat(bPage)
    await askQuestion(bPage, QUESTION)
    await expectCitedExclusively(bPage, OWN_DOCUMENT_NAME, TEST_DOCUMENT_NAME)
  })

  test('6. Freigabe an eine Gruppe', async ({ authenticatedPage: adminPage, outsiderPage: cPage }) => {
    // dev-outsider is already provisioned by the #233 seed run same as dev-user, same reasoning
    // as scenario 3's comment above.
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
    await gotoLibraryDetail(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('tab', { name: 'Verwaltung' }).click()
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).click()
    await adminPage.getByRole('radio', { name: 'Gruppe' }).click()
    const groupInput = adminPage.getByRole('combobox', { name: 'Gruppe auswählen' })
    await groupInput.click()
    await groupInput.fill(GROUP_NAME)
    await adminPage.getByRole('option', { name: GROUP_NAME }).click()
    await adminPage.getByRole('button', { name: 'Freigeben' }).last().click()
    await expect(adminPage.getByText(GROUP_NAME)).toBeVisible()
    // Scoped to the dialog: the deDE MUI locale also names an error Alert's own close button
    // "Schließen" (#784), so an unscoped lookup could resolve to two elements on the error path.
    await adminPage.getByRole('dialog').getByRole('button', { name: 'Schließen' }).click()

    await gotoLibraries(cPage)
    await expect(cPage.getByText(LIBRARY_NAME, { exact: true })).toBeVisible()

    await startFreshChat(cPage)
    await askQuestion(cPage, QUESTION)
    await expectCitedSource(cPage, TEST_DOCUMENT_NAME)
  })

  test('7. Upload ohne Recht', async ({ outsiderPage: cPage }) => {
    await gotoLibraries(cPage)
    await gotoLibraryDetail(cPage, LIBRARY_NAME)

    await expect(cPage.getByText('Sie haben in dieser Bibliothek nur Leserechte.')).toBeVisible()
    await expect(cPage.getByRole('button', { name: 'Dateien hochladen' })).toHaveCount(0)
    await expect(cPage.getByLabel('Dateien auswählen')).toHaveCount(0)
  })
})

// Idempotent, unlike a plain click on the summary: a MUI Accordion re-collapses on a second click,
// and a store refresh that fires while it is open (e.g. GroupCard's addMember, which reloads the
// whole group list) can leave it collapsed again by the time the next assertion runs. Checking
// aria-expanded first means this always ends up open, whichever state it started in. Still used
// for the group management accordion (#481 only touched the library pages).
async function ensureAccordionExpanded(page: Page, name: string) {
  const summary = page.getByRole('button', { name })
  if ((await summary.getAttribute('aria-expanded')) !== 'true') {
    await summary.click()
  }
}
