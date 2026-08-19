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

// Negative scenarios (4, 5) upload this into their own personal library rather than asserting
// "zero results" against the shared library alone (see the module doc comment below and PR #453
// review, nit 1): the only library an excluded user can otherwise read is their empty personal
// one, so "zero source cards" would just as happily mean "the search never ran" as "the filter
// works" - a stalled or failed personal-library provisioning (see
// io.opaa.user.UserService#ensureBothPersonalAssets, retried on next login) would leave both
// scenarios silently green without checking anything. A real, non-empty, own-vs-foreign
// distinction closes that gap.
const OWN_DOCUMENT_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'fixtures',
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

// Chats are persisted server-side (#525/#527) and keyed per user, not per browser session: a
// fresh Playwright context (a new browser context per fixture, see fixtures/auth.ts) still talks
// to the same backend account, so `/chat` now restores whatever chat that account last used - not
// necessarily an empty one. `expectCitedSource`/`expectOwnFoundForeignNotFound` below assert
// page-wide, which is only correct on a chat that holds exactly the one turn just asked; without
// this, a later scenario reusing the same account (dev-user in scenarios 3 and 5, dev-outsider in
// scenarios 4 and 6) would see source cards from an earlier scenario's turn still in the DOM
// alongside the new one. Every scenario below explicitly starts a fresh, not-yet-persisted chat
// before asking its question instead, so "the page shows exactly this one turn" is a fact, not an
// assumption that happened to hold by scenario order (CI fix following PR #548's review).
async function startFreshChat(page: Page) {
  await page.goto('/chat')
  await page.getByRole('button', { name: 'Neuer Chat' }).click()
  await page.waitForURL(/\/spaces\/[^/]+\/chats\/new$/)
  // The route change can briefly leave ChatPage showing its loading spinner instead of the input -
  // a stale loadChat for the previously active chat racing the reset to "new", or simply the
  // moment before the freshly emptied chat has rendered. Waiting here explicitly, instead of
  // trusting the URL alone, is what askQuestion below actually needs (CI fix following PR #548's
  // review, nit 3).
  await expect(page.getByPlaceholder('Stellen Sie eine Frage …')).toBeVisible()
}

async function askQuestion(page: Page, question: string) {
  const input = page.getByPlaceholder('Stellen Sie eine Frage …')
  await expect(input).toBeVisible()
  await input.fill(question)
  await page.getByRole('button', { name: 'Nachricht senden' }).click()
}

async function expectCitedSource(page: Page, fileName: string) {
  const card = page.getByTestId('source-card').filter({ hasText: fileName })
  await expect(card).toHaveAttribute('data-cited', 'true', { timeout: 15_000 })
}

// Not "no source card at all": see OWN_DOCUMENT_PATH's comment above for why the negative
// scenarios assert a real own-vs-foreign split instead of a plain absence check.
async function expectOwnFoundForeignNotFound(
  page: Page,
  ownFileName: string,
  foreignFileName: string,
) {
  await expectCitedSource(page, ownFileName)
  await expect(page.getByTestId('source-card').filter({ hasText: foreignFileName })).toHaveCount(
    0,
  )
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

// #481: the library overview no longer expands inline - every row navigates to its own detail
// page (/libraries/:id), which is where Stammdaten, "Rechte verwalten" and, for an UPLOAD
// library, the upload zone and document list now live.
async function gotoLibraryDetail(page: Page, libraryName: string) {
  await Promise.all([
    page.waitForURL(/\/libraries\/[^/]+$/),
    page.getByText(libraryName, { exact: true }).click(),
  ])
  await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
}

// Uploads OWN_DOCUMENT_PATH into the caller's own personal library. The personal library is
// always the first row on the overview (LibraryManagementPage sorts it first), marked "persönlich"
// in its summary line - unlike the pre-#481 DocumentsPage, there is no default-selected library to
// rely on, so this actually finds and opens that row.
async function uploadOwnDocument(page: Page) {
  await gotoLibraries(page)
  await Promise.all([
    page.waitForURL(/\/libraries\/[^/]+$/),
    page.getByText('persönlich', { exact: false }).first().click(),
  ])
  await page.getByLabel('Dateien auswählen').setInputFiles(OWN_DOCUMENT_PATH)
  // exact: true - a non-exact match risks a strict-mode violation once anything else on the page
  // (e.g. a status hint) also happens to contain this filename as a substring.
  await expect(page.getByText(OWN_DOCUMENT_NAME, { exact: true })).toBeVisible()
  await expect(page.getByText('indiziert')).toBeVisible({ timeout: 30_000 })
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
 * and 5 each add one more upload of their own, into the acting user's own personal library - see
 * OWN_DOCUMENT_PATH's comment for why.
 *
 * Scenarios 4 and 5 (the negative cases) are the ones that actually exercise
 * io.opaa.query.QueryService#libraryFilter, the permission filter applied directly to the vector
 * store query (see its Javadoc). Verified manually per AGENTS.md's Reproduktionsnachweis pattern,
 * applied to a pre-existing feature rather than a fix: with `.filterExpression(...)` removed from
 * that call and `readableLibraryIds.isEmpty() ? List.of() : ...` short-circuit bypassed, both
 * scenarios fail identically - the own document is still (rightfully) cited, so
 * `expectOwnFoundForeignNotFound`'s first assertion passes, but its second one does not:
 * `expect(locator).toHaveCount(expected) failed / Locator: getByTestId('source-card').filter({
 * hasText: 'wissensdokument.txt' }) / Expected: 0 / Received: 1` - the excluded user now finds the
 * other's document too. Both restored to green with the filter back in place.
 */
test.describe.serial('Wissensbibliotheken: Upload, Freigabe, rechtebewusste Suche (#424)', () => {
  test('1. Eigene Bibliothek anlegen und befüllen', async ({ authenticatedPage: page }) => {
    await gotoLibraries(page)
    await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
    await page.getByRole('dialog').getByLabel('Name').fill(LIBRARY_NAME)
    // #481: the create dialog navigates straight to the new library's detail page on success -
    // there is no separate documents page or picker to visit afterwards.
    await Promise.all([
      page.waitForURL(/\/libraries\/[^/]+$/),
      page.getByRole('button', { name: 'Erstellen' }).click(),
    ])
    await expect(page.getByRole('heading', { name: LIBRARY_NAME })).toBeVisible()

    await page.getByLabel('Dateien auswählen').setInputFiles(TEST_DOCUMENT_PATH)

    await expect(page.getByText(TEST_DOCUMENT_NAME)).toBeVisible()
    await expect(page.getByText('indiziert')).toBeVisible({ timeout: 30_000 })
  })

  test('2. Suche findet das eigene Dokument', async ({ authenticatedPage: page }) => {
    await startFreshChat(page)
    await askQuestion(page, QUESTION)
    await expectCitedSource(page, TEST_DOCUMENT_NAME)
  })

  test('3. Freigeben und finden', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    // regularUserPage's fixture setup already logged dev-user in once (see fixtures/auth.ts),
    // which is what provisions the account GET /v1/admin/users below can find - without this, the
    // admin's picker would never list dev-user at all.
    await gotoLibraries(adminPage)
    await gotoLibraryDetail(adminPage, LIBRARY_NAME)
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

    await startFreshChat(bPage)
    await askQuestion(bPage, QUESTION)
    await expectCitedSource(bPage, TEST_DOCUMENT_NAME)
  })

  test('4. Negativfall: keine Freigabe, kein Treffer', async ({ outsiderPage: cPage }) => {
    await uploadOwnDocument(cPage)

    await gotoLibraries(cPage)
    await expect(cPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await startFreshChat(cPage)
    await askQuestion(cPage, QUESTION)
    await expectOwnFoundForeignNotFound(cPage, OWN_DOCUMENT_NAME, TEST_DOCUMENT_NAME)
  })

  test('5. Entzug wirkt', async ({ authenticatedPage: adminPage, regularUserPage: bPage }) => {
    await gotoLibraries(adminPage)
    await gotoLibraryDetail(adminPage, LIBRARY_NAME)
    await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
    adminPage.once('dialog', (dialog) => dialog.accept())
    await adminPage.getByRole('button', { name: 'Freigabe für Dev User entziehen' }).click()
    // Not "the grant list is empty": the creator's own OWNER grant is a row in this same list and
    // outlives every other grant, so the list is never actually empty here - only "Dev User"'s row
    // is gone.
    await expect(adminPage.getByText('Dev User')).toHaveCount(0)
    await adminPage.getByRole('button', { name: 'Schließen' }).click()

    await uploadOwnDocument(bPage)

    await gotoLibraries(bPage)
    await expect(bPage.getByText(LIBRARY_NAME, { exact: true })).toHaveCount(0)

    await startFreshChat(bPage)
    await askQuestion(bPage, QUESTION)
    await expectOwnFoundForeignNotFound(bPage, OWN_DOCUMENT_NAME, TEST_DOCUMENT_NAME)
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
    await gotoLibraryDetail(adminPage, LIBRARY_NAME)
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
