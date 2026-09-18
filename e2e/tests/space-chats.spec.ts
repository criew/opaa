import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import {
  askQuestion,
  chatSidebarEntries,
  clearSearchScope,
  createLibraryWithDocument,
  expectAnyCitedSource,
  expectCitedExclusively,
  expectTopSidebarChatToBeNamed,
  shareLibraryWithPerson,
  startAnotherChatViaSidebar,
  startFreshChat,
} from '../fixtures/chat'
import type { Page, TestInfo } from '@playwright/test'

// Own fixture files here, deliberately never uploaded by any other spec
// in this suite: io.opaa.query.QueryService merges source references by file name
// (`toMap(SourceReference::getFileName, ...)`), and the KI stub gives every chunk the exact same
// embedding (see e2e/ai-stub/server.mjs) - reusing wissensdokument.txt/eigenesdokument.txt here
// would let #424's own assertions stay green even if its own upload/share path were broken, a
// same-named document from one of this file's libraries silently standing in for theirs. See
// e2e/README.md's "Szenarien" section for the full reasoning.
// Since #233, this suite's own frozen upload fixtures live under demo/seed/e2e-data/test-documents/
// (see knowledge-libraries.spec.ts's TEST_DOCUMENT_PATH comment) - not under e2e/fixtures/ any more.
const DOCUMENT_A_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
  'chatdokument-a.txt',
)
const DOCUMENT_A_NAME = 'chatdokument-a.txt'

const DOCUMENT_B_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'demo',
  'seed',
  'e2e-data',
  'test-documents',
  'chatdokument-b.txt',
)
const DOCUMENT_B_NAME = 'chatdokument-b.txt'

// Unique per run so re-runs against a stack that was not torn down (or a shared dev stack) never
// collide with a leftover library/chat of the same name.
const runId = Date.now()

// Every name/question that a test persists (a library, a chat and its first question) is
// additionally suffixed with the attempt's retry count via uniqueId below - not just runId.
// playwright.config.ts retries once in CI; a retried test re-runs from scratch, but whatever the
// *previous*, failed attempt already persisted (a chat with the same question, a library with the
// same name) is still sitting in the database - a plain runId suffix, computed once at module load
// and shared across every attempt, would not tell those apart, and assertions that require exactly
// one match (e.g. an exact question in the message list) would then find two. Including
// testInfo.retry makes every attempt's data distinct instead.
function uniqueId(testInfo: TestInfo): string {
  return `${runId}-${testInfo.retry}`
}

// #557 review follow-up (PR #561): a chat's title in the sidebar is no longer its first question
// verbatim - io.opaa.chat.ChatService#deriveTitle still sets that as a synchronous fallback, but
// io.opaa.chat.ChatTitleGenerationService then asynchronously replaces it with an LLM-generated
// one. The deterministic KI stub this suite runs against (ai-stub/server.mjs) answers every
// title-generation prompt with the exact same fixed text, so - unlike the question text, which
// uniqueId above keeps unique per chat - the *sidebar's* title can no longer identify one specific
// chat among several; see chatSidebarEntryCount/expectTopSidebarChatToBeNamed in fixtures/chat.ts
// for the position/count-based assertions used below instead. The question text itself remains a
// reliable anchor for the chat's own message history in <main>, which the frontend never rewrites.

/**
 * Opens the '@' mention popup for query and selects the suggestion matching libraryName exactly.
 * Mirrors ChatInput.tsx's own findActiveMention: '@' must start a word, and the fragment typed
 * after it (here: the full library name) must not contain whitespace up to the point captured by
 * `query` - passing the exact name as both trigger and filter keeps the suggestion list down to
 * the one match this needs. Library names in this file are hyphenated (no spaces) for exactly
 * this reason.
 */
async function referenceLibrary(page: Page, libraryName: string) {
  const input = page.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen')
  await input.fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
}

/**
 * Creates a space named spaceName through the four-step wizard (name is the only required field,
 * members and data sources are optional) and returns its id, read off the overview URL the wizard
 * navigates to on success.
 */
async function createSpace(page: Page, spaceName: string): Promise<string> {
  await page.goto('/spaces/new')
  await page.getByLabel('Name', { exact: true }).fill(spaceName)
  for (let step = 0; step < 3; step++) {
    await page.getByRole('button', { name: 'Weiter', exact: true }).click()
  }
  // The lookahead keeps /spaces/new itself from matching while the wizard is still open.
  await Promise.all([
    page.waitForURL(/\/spaces\/(?!new$)[^/]+$/),
    page.getByRole('button', { name: 'Space anlegen' }).click(),
  ])
  await expect(page.getByRole('heading', { name: spaceName })).toBeVisible()
  return page.url().split('/spaces/')[1]
}

/**
 * Covers #529 (part of Epic #523): the persistent, space-owned chat and its `@`-reference / chip
 * bar search scope (#560), built on top of test(e2e) #424's upload/share/search chain and its
 * helpers (see fixtures/chat.ts). Every scenario starts its own fresh chat (`startFreshChat`)
 * rather than relying on scenario order, for the same reason knowledge-libraries.spec.ts does
 * (see its module doc comment): chats are persisted per account, so a later scenario reusing the
 * same dev user would otherwise still see an earlier scenario's turn in the DOM. Scenarios also
 * each create their own library rather than sharing one across scenarios (review finding on PR
 * #554, nit 3): a scenario run in isolation (`--grep`) must not depend on module state a
 * different scenario would otherwise have set.
 */
test.describe.serial('Chats im Space, @-Referenzen und Suchbereich-Chip-Leiste (#529)', () => {
  test('1. Chat im Space: Frage, Antwort mit Quellen, Verlauf überlebt Neuladen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const libraryName = `E2E-Chat-Bibliothek-${id}`
    const question = `Was steht im Wissensdokument fuer Chat-Szenario 1 (${id})?`

    await createLibraryWithDocument(page, libraryName, DOCUMENT_A_PATH, DOCUMENT_A_NAME)

    await startFreshChat(page)
    await askQuestion(page, question)
    // Deliberately not expectCitedSource(page, DOCUMENT_A_NAME): this scenario is about the chat
    // mechanism (an answer with sources exists, and both it and the chat list entry survive a
    // reload), not about which library the default @Alles-Wissen scope (left untouched here) ends
    // up citing - that search is an unscoped topK over the entire readable corpus, which keeps
    // growing as earlier specs run. Asserting on this scenario's own document specifically would
    // make the test fragile to how large that corpus has grown by the time it runs, not to
    // anything this scenario is meant to catch - scenarios 2 and 5 below cover "the right library
    // was searched" deterministically via an explicit @-reference, which replaces @Alles-Wissen.
    await expectAnyCitedSource(page)

    // sendMessage implicitly creates the chat and replaces the URL to point at it (#548) - once
    // that has happened, the URL no longer ends in ".../chats/new".
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    const chatUrl = page.url()

    await page.reload()

    await expect(page).toHaveURL(chatUrl)
    // AppShell.tsx renders the chat itself inside <main> and the space's chat list inside <nav>
    // (Sidebar). <main> still shows the question verbatim (#557 only ever rewrites the sidebar
    // title, never the message history).
    await expect(page.getByTestId('message-list').getByText(question)).toBeVisible()
    await expectAnyCitedSource(page)
    // #749: the chat page must fill exactly the viewport - the message list scrolls internally,
    // the page itself must not. A stray outer scrollbar (root cause was an absolutely positioned
    // live region without a positioning context, see MessageList.tsx) would make
    // document.documentElement taller than the viewport it actually renders in.
    // The cast avoids depending on DOM lib types in this suite's tsconfig (Node-only "lib":
    // ["ES2022"]) - page.evaluate's callback still runs in the browser at runtime.
    const hasOuterScrollbar = await page.evaluate(() => {
      const root = (
        globalThis as unknown as {
          document: { documentElement: { scrollHeight: number; clientHeight: number } }
        }
      ).document.documentElement
      return root.scrollHeight > root.clientHeight + 1
    })
    expect(hasOuterScrollbar).toBe(false)
    // The chat list (sidebar) reloads from the backend on a fresh page load - its entry is what
    // proves the chat itself, not just this one still-open tab, was actually persisted. #557
    // review follow-up: no longer asserted via the question text (see the module doc comment on
    // uniqueId/title generation) - this chat is the one most recently interacted with, so it is
    // topmost in the sidebar (chatListStore's sortByLastUse); asserting that entry has a real,
    // non-placeholder title proves both that it is listed at all and that title generation
    // completed.
    await expectTopSidebarChatToBeNamed(page)
  })

  test('2. @-Referenz schränkt die Suche auf die referenzierte Bibliothek ein', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const libraryAName = `E2E-Chat-Referenz-A-${id}`
    const libraryBName = `E2E-Chat-Referenz-B-${id}`
    const question = `Was steht im referenzierten Dokument (${id})?`

    await createLibraryWithDocument(page, libraryAName, DOCUMENT_A_PATH, DOCUMENT_A_NAME)
    await createLibraryWithDocument(page, libraryBName, DOCUMENT_B_PATH, DOCUMENT_B_NAME)

    await startFreshChat(page)
    // Referencing a library replaces the default @Alles-Wissen chip outright (#560) - no separate
    // step to leave the default scope first.
    await referenceLibrary(page, libraryAName)
    await expect(page.getByLabel(`Bibliotheksreferenz ${libraryAName} entfernen`)).toBeVisible()

    await askQuestion(page, question)
    await expectCitedExclusively(page, DOCUMENT_A_NAME, DOCUMENT_B_NAME)
  })

  test('3. Ohne Wissensbasis: Antwort ohne Quellen mit Hinweis', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const question = `Was ist die Hauptstadt von Deutschland (${id})?`

    await startFreshChat(page)
    // Leaving the chip bar empty (not a switch, #560) is what "ohne Wissensbasis" means now:
    // remove the default @Alles-Wissen chip and reference nothing in its place.
    await clearSearchScope(page)

    await askQuestion(page, question)

    await expect(page.getByText('Diese Antwort wurde ohne Wissensbasis erstellt.')).toBeVisible()
    await expect(page.getByTestId('source-card')).toHaveCount(0)
  })

  test('4. Rechte-Negativfall: nicht lesbare Bibliothek erscheint nicht in den @-Vorschlägen', async (
    { authenticatedPage: adminPage, regularUserPage: bPage },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const privateLibraryName = `E2E-Chat-Privatbibliothek-${id}`
    const sharedLibraryName = `E2E-Chat-Freigegeben-${id}`

    // Not shared with anyone - stays admin-only.
    await createLibraryWithDocument(adminPage, privateLibraryName, DOCUMENT_A_PATH, DOCUMENT_A_NAME)
    // Shared with dev-user, so the suggestion list for bPage is proven non-empty rather than
    // trivially empty because dev-user cannot read anything at all.
    await createLibraryWithDocument(adminPage, sharedLibraryName, DOCUMENT_B_PATH, DOCUMENT_B_NAME)
    await shareLibraryWithPerson(adminPage, sharedLibraryName, 'Dev User', /Dev User/)

    // Positive control (review finding on PR #554, nit 4): the private library's own creator can
    // read it, so it must appear in *their* @-suggestions. Without this, "absent from dev-user's
    // suggestions" would be indistinguishable from "never suggested to anyone, e.g. because the
    // mention feature itself is broken" - only the contrast between adminPage and bPage below
    // makes the absence a real, permission-specific finding.
    await startFreshChat(adminPage)
    const adminInput = adminPage.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen')
    await adminInput.fill(`@${privateLibraryName}`)
    await expect(adminPage.getByRole('option', { name: privateLibraryName })).toBeVisible()

    await startFreshChat(bPage)
    const input = bPage.getByPlaceholder('Frage stellen … mit @ auf eine Quelle eingrenzen')
    await input.fill(`@${sharedLibraryName}`)
    await expect(bPage.getByRole('option', { name: sharedLibraryName })).toBeVisible()

    await input.fill(`@${privateLibraryName}`)
    await expect(bPage.getByRole('option', { name: privateLibraryName })).toHaveCount(0)
  })

  test('5. Mehrere Chats: zweiter Chat hält eigenen Verlauf und eigene Referenzen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const libraryName = `E2E-Chat-Zweitchat-${id}`
    const questionChat1 = `Frage im ersten Chat (${id})`
    const questionChat2 = `Frage im zweiten Chat (${id})`
    // The message history, not <main>: the chat header repeats the fallback title (#658).
    const main = page.getByTestId('message-list')

    // #557 review follow-up: captured before either chat exists, so the count-based assertions
    // below (chat titles are no longer a reliable per-chat anchor, see the module doc comment) can
    // check "exactly two more entries appeared" without needing an absolute baseline of zero.
    const chatCountBefore = await chatSidebarEntries(page).count()

    await createLibraryWithDocument(page, libraryName, DOCUMENT_A_PATH, DOCUMENT_A_NAME)

    await startFreshChat(page)
    await askQuestion(page, questionChat1)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(main.getByText(questionChat1)).toBeVisible()
    const chat1Url = page.url()

    // "Neuer Chat" in the sidebar starts a second, independent chat in the same space rather than
    // reusing chat 1 - the in-app path, deliberately not another entry via `/chat`. Referencing a
    // library replaces its default @Alles-Wissen chip outright (#560).
    await startAnotherChatViaSidebar(page)
    await referenceLibrary(page, libraryName)
    await askQuestion(page, questionChat2)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    const chat2Url = page.url()
    expect(chat2Url).not.toBe(chat1Url)

    // Chat 2 shows only its own turn and its own sticky reference chip.
    await expect(main.getByText(questionChat1)).toHaveCount(0)
    await expect(main.getByText(questionChat2)).toBeVisible()
    await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toBeVisible()

    // Both chats are listed in the sidebar as two distinct entries (#557 review follow-up: no
    // longer asserted via their title text, which - unlike the question text above - can end up
    // identical for both, see the module doc comment on uniqueId/title generation).
    await expect(chatSidebarEntries(page)).toHaveCount(chatCountBefore + 2)

    // Back to chat 1 directly by URL (#557 review follow-up: a sidebar click keyed on chat 1's
    // title text is no longer reliable, same reasoning as above), then a reload (review finding on
    // PR #554, nit 5): the reload is what actually proves chat 1's own history and (lack of)
    // reference are persisted server-side, independent of chat 2's - not the navigation method.
    await page.goto(chat1Url)
    await expect(page).toHaveURL(chat1Url)
    await page.reload()
    await expect(page).toHaveURL(chat1Url)
    await expect(main.getByText(questionChat1)).toBeVisible()
    await expect(main.getByText(questionChat2)).toHaveCount(0)
    await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toHaveCount(0)
  })

  test('6. Einstieg und Space-Wechsel landen auf einem leeren Gespräch', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const question = `Frage vor dem Neueinstieg (${id})`
    const spaceName = `E2E-Chat-Zielspace-${id}`
    const main = page.getByTestId('message-list')

    // A second space of this scenario's own, so the switch below really leaves the space it starts
    // in: the account otherwise owns nothing but its automatic default space, and no other
    // scenario of this suite creates one.
    const targetSpaceId = await createSpace(page, spaceName)

    // This scenario's own persisted chat (it must not depend on what the scenarios above left
    // behind, see the describe's doc comment) - the state in which the entry point used to reopen
    // the most recently used conversation instead of offering an empty one.
    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, question)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(main.getByText(question)).toBeVisible()

    await page.goto('/chat')
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/new$/)
    await expect(
      page.getByRole('heading', { name: 'Womit kann ich Ihnen heute helfen?' }),
    ).toBeVisible()
    // Scoped to <main>: the sidebar lists the chat just created, and its fallback title is this
    // very question until title generation replaces it.
    await expect(page.getByRole('main').getByText(question)).toHaveCount(0)

    // Picking a space in the sidebar's space switcher lands on an empty chat in that space, not on
    // its overview page. Deliberately the space created above and not the one currently open (the
    // default space, which /chat just resolved to), so this really exercises a switch. The
    // switcher itself is named after the space it shows, hence the scope to the sidebar landmark
    // plus its "Space" overline instead of a fixed name.
    await page
      .getByRole('complementary', { name: 'Space-Bereich' })
      .getByRole('button', { name: /^Space/ })
      .click()
    await page.getByRole('menuitem', { name: new RegExp(spaceName) }).click()
    await expect(page).toHaveURL(new RegExp(`/spaces/${targetSpaceId}/chats/new$`))
    await expect(
      page.getByRole('heading', { name: 'Womit kann ich Ihnen heute helfen?' }),
    ).toBeVisible()
  })

  test('7. Chatliste ordnen: Titelfilter, Anheften und Gruppe „Angeheftet“ nach Neuladen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const title = `E2E-Anheften-${id}`
    const question = `Frage zum Anheften (${id})`
    const chatList = page.getByRole('navigation', { name: 'Chats' })
    const filter = chatList.getByRole('searchbox', { name: 'Chats filtern' })
    const actionsName = `Aktionen für Chat „${title}“`
    const actionsOfChat = chatList.getByRole('button', { name: actionsName })
    const inGroup = (group: string) =>
      chatList.getByRole('list', { name: group }).getByRole('button', { name: actionsName })

    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, question)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(page.getByTestId('message-list').getByText(question)).toBeVisible()

    // Every chat of this suite ends up with the KI stub's one fixed title (see the module doc
    // comment), so this chat gets a unique one first: the newest chat is the top entry of "Heute".
    await chatList
      .getByRole('list', { name: 'Heute' })
      .getByRole('button', { name: /^Aktionen für Chat/ })
      .first()
      .click()
    await page.getByRole('menuitem', { name: /umbenennen$/ }).click()
    const titleField = chatList.getByLabel('Chat-Titel')
    await titleField.fill(title)
    await titleField.press('Enter')
    await expect(actionsOfChat).toHaveCount(1)

    // Filter: narrows the list to the matching title while typing, Escape restores it.
    const entriesBefore = await chatSidebarEntries(page).count()
    await filter.fill(title.toLowerCase())
    await expect(chatSidebarEntries(page)).toHaveCount(1)
    await expect(actionsOfChat).toHaveCount(1)
    await expect(chatList.getByRole('status')).toHaveText('1 Chat gefunden')
    await filter.press('Escape')
    await expect(filter).toHaveValue('')
    await expect(chatSidebarEntries(page)).toHaveCount(entriesBefore)

    // Pin via the context menu; the chat moves into "Angeheftet" and stays there after a reload.
    await actionsOfChat.click()
    await page.getByRole('menuitem', { name: `Chat „${title}“ anheften` }).click()
    await expect(inGroup('Angeheftet')).toHaveCount(1)

    await page.reload()
    await expect(inGroup('Angeheftet')).toHaveCount(1)
    await expectNoSeriousA11yViolations(page, 'Seitenleiste mit angeheftetem Chat')

    // Unpinning puts it back into its time group - and leaves no pin behind for later runs.
    await actionsOfChat.click()
    await page.getByRole('menuitem', { name: `Chat „${title}“ lösen` }).click()
    await expect(inGroup('Heute')).toHaveCount(1)
    await page.reload()
    await expect(inGroup('Heute')).toHaveCount(1)
  })

  test('8. Chat-Archiv: archivieren, im Archiv öffnen, zurückholen, Mehrfachauswahl, Weiterschreiben', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    const titleA = `E2E-Archiv-A-${id}`
    const titleB = `E2E-Archiv-B-${id}`
    const questionA = `Frage fürs Archiv A (${id})`
    const chatList = page.getByRole('navigation', { name: 'Chats' })
    const main = page.getByTestId('message-list')
    const actionsOf = (title: string) =>
      chatList.getByRole('button', { name: `Aktionen für Chat „${title}“` })
    const archivedHint = page.getByRole('main').getByText('Archiviert', { exact: true })
    const archiveTable = page.getByRole('table', { name: 'Chat-Archiv' })

    // Two chats of this scenario's own, each with a unique title (see scenario 7 for why).
    async function createTitledChat(title: string, question: string): Promise<string> {
      await startFreshChat(page)
      await clearSearchScope(page)
      await askQuestion(page, question)
      await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
      await expect(main.getByText(question)).toBeVisible()
      await chatList
        .getByRole('list', { name: 'Heute' })
        .getByRole('button', { name: /^Aktionen für Chat/ })
        .first()
        .click()
      await page.getByRole('menuitem', { name: /umbenennen$/ }).click()
      const titleField = chatList.getByLabel('Chat-Titel')
      await titleField.fill(title)
      await titleField.press('Enter')
      await expect(actionsOf(title)).toHaveCount(1)
      return page.url()
    }
    const chatAUrl = await createTitledChat(titleA, questionA)
    await createTitledChat(titleB, `Frage fürs Archiv B (${id})`)
    const chatsPageUrl = chatAUrl.replace(/\/chats\/[^/]+$/, '/chats')

    // Archive A from the sidebar: it leaves the list.
    await actionsOf(titleA).click()
    await page.getByRole('menuitem', { name: `Chat „${titleA}“ archivieren` }).click()
    await expect(actionsOf(titleA)).toHaveCount(0)
    await expect(page.getByText(`Chat „${titleA}“ archiviert`)).toBeVisible()

    // The page "Chats" lists it in the archive; opening it shows the chat with the hint.
    await chatList.getByRole('link', { name: 'Alle Chats' }).click()
    await expect(page).toHaveURL(chatsPageUrl)
    await page.getByRole('tab', { name: /^Archiv \(/ }).click()
    await archiveTable.getByRole('link', { name: titleA }).click()
    await expect(page).toHaveURL(chatAUrl)
    await expect(main.getByText(questionA)).toBeVisible()
    await expect(archivedHint).toBeVisible()

    // "Zurückholen" puts it back into the list.
    await page.getByRole('button', { name: 'Chat aus dem Archiv zurückholen' }).click()
    await expect(archivedHint).toHaveCount(0)
    await expect(actionsOf(titleA)).toHaveCount(1)

    // Multi-selection on the page "Chats": archive both chats at once.
    await page.goto(chatsPageUrl)
    const activeTable = page.getByRole('table', { name: 'Aktive Chats' })
    await activeTable.getByRole('checkbox', { name: `„${titleA}“ auswählen` }).check()
    await activeTable.getByRole('checkbox', { name: `„${titleB}“ auswählen` }).check()
    await page.getByRole('button', { name: 'Archivieren', exact: true }).click()
    await expect(page.getByText('2 Chats archiviert')).toBeVisible()
    await expect(actionsOf(titleA)).toHaveCount(0)
    await expect(actionsOf(titleB)).toHaveCount(0)
    await page.getByRole('tab', { name: /^Archiv \(/ }).click()
    await expect(archiveTable.getByRole('link', { name: titleB })).toBeVisible()
    await expectNoSeriousA11yViolations(page, 'Seite „Chats“, Reiter Archiv')

    // Writing in an archived chat brings it back, with a notice.
    await archiveTable.getByRole('link', { name: titleA }).click()
    await expect(archivedHint).toBeVisible()
    await askQuestion(page, `Weiter im archivierten Chat (${id})`)
    await expect(page.getByText('Chat aus dem Archiv zurückgeholt')).toBeVisible()
    await expect(archivedHint).toHaveCount(0)
    await expect(actionsOf(titleA)).toHaveCount(1)

    // Bring B back as a bulk action from the archive tab - leaves nothing archived behind.
    await page.goto(chatsPageUrl)
    await page.getByRole('tab', { name: /^Archiv \(/ }).click()
    await archiveTable.getByRole('checkbox', { name: `„${titleB}“ auswählen` }).check()
    await page.getByRole('button', { name: 'Zurückholen', exact: true }).click()
    await expect(page.getByText('1 Chat aus dem Archiv zurückgeholt')).toBeVisible()
    await expect(actionsOf(titleB)).toHaveCount(1)
  })

  test('9. Chatsuche: Begriff aus der Seitenleiste suchen, archivierten Treffer an der Stelle öffnen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    // One word of letters only, unique per attempt: the full-text parser splits digits and hyphens
    // into separate tokens, which would make the term match other runs' chats.
    const letters = uniqueId(testInfo)
      .replace(/-/g, '')
      .replace(/\d/g, (digit) => 'abcdefghij'[Number(digit)])
    const term = `Quarzlampe${letters}`
    const question = `Wo steht die ${term} im Lager?`
    const chatList = page.getByRole('navigation', { name: 'Chats' })
    const main = page.getByTestId('message-list')

    await startFreshChat(page)
    await clearSearchScope(page)
    await askQuestion(page, question)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(main.getByText(question)).toBeVisible()
    const chatUrl = page.url()
    const chatsPageUrl = chatUrl.replace(/\/chats\/[^/]+$/, '/chats')

    // Into the chat archive: the search includes it, and the hit must open there as well.
    await chatList
      .getByRole('list', { name: 'Heute' })
      .getByRole('button', { name: /^Aktionen für Chat/ })
      .first()
      .click()
    await page.getByRole('menuitem', { name: /archivieren$/ }).click()
    await expect(page.getByText(/^Chat „.*“ archiviert$/)).toBeVisible()

    // The title filter finds nothing and hands the term over to the chat search.
    await chatList.getByRole('searchbox', { name: 'Chats filtern' }).fill(term)
    await chatList.getByRole('link', { name: 'In Inhalten suchen' }).first().click()
    // The term is never part of the address.
    await expect(page).toHaveURL(chatsPageUrl)
    await expect(page.getByRole('searchbox', { name: 'In Chats suchen' })).toHaveValue(term)

    const hits = page.getByRole('list', { name: 'Suchtreffer' })
    const hit = hits.getByRole('listitem')
    await expect(hit).toHaveCount(1)
    await expect(hit).toContainText('Frage')
    await expect(hit).toContainText('Archiviert')
    await expect(hit.locator('mark')).toHaveText(term)
    await expectNoSeriousA11yViolations(page, 'Seite „Chats“ mit Trefferliste der Chatsuche')

    await hit.getByRole('link').click()
    await expect(page).toHaveURL(new RegExp(`^${chatUrl}\\?message=[^&]+$`))
    const found = page.getByRole('article', { name: 'Gefundene Nachricht: Frage' })
    await expect(found).toBeVisible()
    await expect(found).toBeFocused()
    await expect(found).toContainText(question)

    // The link stays valid after a reload; the page "Chats" has forgotten the term by then.
    await page.reload()
    await expect(page.getByRole('article', { name: 'Gefundene Nachricht: Frage' })).toBeFocused()
    await page.goBack()
    await expect(page.getByRole('searchbox', { name: 'In Chats suchen' })).toHaveValue('')

    // Leaves nothing archived behind for later runs.
    await page.goto(chatUrl)
    await page.getByRole('button', { name: 'Chat aus dem Archiv zurückholen' }).click()
    await expect(page.getByRole('main').getByText('Archiviert', { exact: true })).toHaveCount(0)
  })
})
