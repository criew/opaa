import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * Reusable building blocks for scenarios that drive the chat UI (test(e2e) #424, #529). Extracted
 * out of knowledge-libraries.spec.ts, whose scenarios needed the exact same "reach a clean chat,
 * ask a question, assert on the cited sources" and "create/share a library" steps that #529's
 * space-chats.spec.ts now also needs - kept here once instead of duplicated across both spec
 * files.
 */

/**
 * Starts an empty, not-yet-persisted chat in the user's default space via the sidebar's "Neuer
 * Chat" button, and waits for the chat input to be ready.
 *
 * Chats are persisted server-side and keyed per user (#525/#527), not per browser session: a
 * fresh Playwright context (a new browser context per fixture, see fixtures/auth.ts) still talks
 * to the same backend account, so `/chat` restores whatever chat that account last used - not
 * necessarily an empty one. Assertions that scope page-wide (e.g. "exactly one source card is
 * cited") are only correct on a chat that holds exactly the one turn just asked; every scenario
 * that needs that must start here first, rather than assume chat state left over from an earlier
 * scenario or run.
 *
 * The route change to ".../chats/new" can briefly leave ChatPage showing its loading spinner
 * instead of the input - a stale loadChat for the previously active chat racing the reset to
 * "new", or simply the moment before the freshly emptied chat has rendered. Waiting here
 * explicitly, instead of trusting the URL alone, is what askQuestion below actually needs (CI fix
 * following PR #548's review, nit 3).
 */
export async function startFreshChat(page: Page): Promise<void> {
  await page.goto('/chat')
  // Wait for whatever chat ChatRedirect just landed on (the account's most recently used one, or
  // "new" if it has none yet) to finish its own load before clicking "Neuer Chat" - the sidebar
  // (and its "Neuer Chat" button) renders independently of ChatPage's loading state, so clicking
  // it while an existing chat's loadChat() is still in flight is possible well before that fetch
  // resolves. chatStore's loadChat/startNewChat guard against a *stale* response overwriting the
  // newer state via a sequence token, but startNewChat does not itself reset `isLoadingChat` back
  // to false, and the superseded loadChat's own response handler skips its `set()` entirely once
  // it detects it was superseded - so isLoadingChat can get stuck `true` forever, and ChatPage
  // never renders anything but its spinner. Observed on CI once this suite started running last,
  // against accounts that by then already had several persisted chats (PR #554) - reported as a
  // product bug rather than fixed here. Settling on the landed chat's own input first (instead of
  // firing the click immediately) avoids ever hitting that window in the first place.
  await expect(page.getByPlaceholder('Stellen Sie eine Frage …')).toBeVisible()
  await page.getByRole('button', { name: 'Neuer Chat' }).click()
  await page.waitForURL(/\/spaces\/[^/]+\/chats\/new$/)
  await expect(page.getByPlaceholder('Stellen Sie eine Frage …')).toBeVisible()
}

/** Fills the chat input and sends it, waiting for it to be visible first (see startFreshChat). */
export async function askQuestion(page: Page, question: string): Promise<void> {
  const input = page.getByPlaceholder('Stellen Sie eine Frage …')
  await expect(input).toBeVisible()
  await input.fill(question)
  await page.getByRole('button', { name: 'Nachricht senden' }).click()
}

/** Waits for fileName's source card to appear in the current answer, cited. */
export async function expectCitedSource(page: Page, fileName: string): Promise<void> {
  const card = page.getByTestId('source-card').filter({ hasText: fileName })
  await expect(card).toHaveAttribute('data-cited', 'true', { timeout: 15_000 })
}

/**
 * Waits for *some* source card to appear cited, without pinning down which file. For scenarios
 * whose point is the chat mechanism itself (an answer with sources exists, and survives a reload)
 * rather than which library the default, unscoped @Alles-Wissen search actually reached (#560):
 * that search runs topK over the *entire* readable corpus, which by the time a given scenario
 * runs also holds whatever every earlier-sorting spec file left behind (same fixed ai-stub
 * embedding for every chunk, see ai-stub/server.mjs) - a specific document can legitimately fall
 * out of the top results as the corpus grows, without anything actually being broken. Scenarios
 * that need to prove *which* library a search reached still use expectCitedSource/
 * expectCitedExclusively with an explicit @-reference, which replaces @Alles-Wissen and scopes
 * the search deterministically regardless of corpus size.
 */
export async function expectAnyCitedSource(page: Page): Promise<void> {
  const citedCard = page.locator('[data-testid="source-card"][data-cited="true"]').first()
  await expect(citedCard).toBeVisible({ timeout: 15_000 })
}

// Not "no source card at all": a plain absence check would pass just as happily before the
// answer has even come back as after it confirmed exclusion. Asserting a real found-vs-excluded
// split closes that gap - see e.g. test(e2e) #424 review, nit 1.
export async function expectCitedExclusively(
  page: Page,
  citedFileName: string,
  excludedFileName: string,
): Promise<void> {
  await expectCitedSource(page, citedFileName)
  await expect(page.getByTestId('source-card').filter({ hasText: excludedFileName })).toHaveCount(
    0,
  )
}

// GET /api/v1/libraries is what decides whether a library is listed (or suggested) at all -
// waiting for the concrete response instead of a fixed delay avoids racing it (see AGENTS.md /
// e2e/README.md "Serialisierungs-Konvention" on not hanging scenarios on wall-clock time).
export async function gotoLibraries(page: Page): Promise<void> {
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
export async function gotoLibraryDetail(page: Page, libraryName: string): Promise<void> {
  await Promise.all([
    page.waitForURL(/\/libraries\/[^/]+$/),
    page.getByText(libraryName, { exact: true }).click(),
  ])
  await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()
}

/**
 * Creates a fresh UPLOAD library named libraryName for the acting user and uploads documentPath
 * (whose file name is documentName) into it, waiting for indexing to finish.
 */
export async function createLibraryWithDocument(
  page: Page,
  libraryName: string,
  documentPath: string,
  documentName: string,
): Promise<void> {
  await gotoLibraries(page)
  await page.getByRole('button', { name: 'Neue Bibliothek' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill(libraryName)
  // #481: the create dialog navigates straight to the new library's detail page on success -
  // there is no separate documents page or picker to visit afterwards.
  await Promise.all([
    page.waitForURL(/\/libraries\/[^/]+$/),
    page.getByRole('button', { name: 'Erstellen' }).click(),
  ])
  await expect(page.getByRole('heading', { name: libraryName })).toBeVisible()

  await page.getByLabel('Dateien auswählen').setInputFiles(documentPath)
  // exact: true - a non-exact match risks a strict-mode violation once anything else on the page
  // (e.g. a status hint) also happens to contain this filename as a substring.
  await expect(page.getByText(documentName, { exact: true })).toBeVisible()
  await expect(page.getByText('indiziert')).toBeVisible({ timeout: 30_000 })
}

/**
 * Shares libraryName (already visible on adminPage's library list) with the person matched by
 * personOption, via "Rechte verwalten" -> "Freigeben". personQuery is what gets typed into the
 * picker to narrow it down to personOption.
 */
export async function shareLibraryWithPerson(
  adminPage: Page,
  libraryName: string,
  personQuery: string,
  personOption: RegExp,
): Promise<void> {
  await gotoLibraries(adminPage)
  await gotoLibraryDetail(adminPage, libraryName)
  await adminPage.getByRole('button', { name: 'Rechte verwalten' }).click()
  await adminPage.getByRole('button', { name: 'Freigeben' }).click()
  // Not getByLabel: once the Autocomplete's listbox is open, its aria-labelledby also points back
  // at "Person auswählen", so getByLabel resolves to both the input and the listbox.
  // getByRole('combobox', ...) only ever matches the input itself.
  const personInput = adminPage.getByRole('combobox', { name: 'Person auswählen' })
  await personInput.click()
  await personInput.fill(personQuery)
  await adminPage.getByRole('option', { name: personOption }).click()
  await adminPage.getByRole('button', { name: 'Freigeben' }).last().click()
  await expect(adminPage.getByText(personOption)).toBeVisible()
  await adminPage.getByRole('button', { name: 'Schließen' }).click()
}
