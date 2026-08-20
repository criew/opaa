import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../fixtures/auth'
import {
  askQuestion,
  createLibraryWithDocument,
  expectAnyCitedSource,
  expectCitedExclusively,
  shareLibraryWithPerson,
  startFreshChat,
} from '../fixtures/chat'
import type { Page, TestInfo } from '@playwright/test'

// This file (space-chats.spec.ts) is named, and sorts alphabetically, to run *after*
// knowledge-libraries.spec.ts and knowledge-library-nacharbeiten.spec.ts (`s` > `k`) - the same
// pattern the nacharbeiten spec already uses relative to knowledge-libraries.spec.ts, for the same
// reason: #424's own scenarios run an *unscoped* topK search over the whole readable corpus, and
// need a small, unpolluted one for their "the right document was cited" assertions to be
// meaningful rather than incidentally true. This file, in turn, uploads several of its own
// libraries - if it ran first, it would be #424's pollution problem instead of the other way
// round (that was in fact CI-red on an earlier version of this file - see git history).
//
// For the same reason, own fixture files here too, deliberately never uploaded by any other spec
// in this suite: io.opaa.query.QueryService merges source references by file name
// (`toMap(SourceReference::getFileName, ...)`), and the KI stub gives every chunk the exact same
// embedding (see e2e/ai-stub/server.mjs) - reusing wissensdokument.txt/eigenesdokument.txt here
// would let #424's own assertions stay green even if its own upload/share path were broken, a
// same-named document from one of this file's libraries silently standing in for theirs. See
// e2e/README.md's "Szenarien" section for the full reasoning.
const DOCUMENT_A_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'fixtures',
  'test-documents',
  'chatdokument-a.txt',
)
const DOCUMENT_A_NAME = 'chatdokument-a.txt'

const DOCUMENT_B_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'fixtures',
  'test-documents',
  'chatdokument-b.txt',
)
const DOCUMENT_B_NAME = 'chatdokument-b.txt'

// Unique per run so re-runs against a stack that was not torn down (or a shared dev stack) never
// collide with a leftover library/chat of the same name.
const runId = Date.now()

// Every name/question that a test persists (a library, a chat and its title-deriving first
// question) is additionally suffixed with the attempt's retry count via uniqueId below - not just
// runId. playwright.config.ts retries once in CI; a retried test re-runs from scratch, but
// whatever the *previous*, failed attempt already persisted (a chat with the same title, a
// library with the same name) is still sitting in the database - a plain runId suffix, computed
// once at module load and shared across every attempt, would not tell those apart, and assertions
// that require exactly one match (e.g. an exact chat title in the sidebar list) would then find
// two. Including testInfo.retry makes every attempt's data distinct instead.
function uniqueId(testInfo: TestInfo): string {
  return `${runId}-${testInfo.retry}`
}

// io.opaa.chat.ChatService#deriveTitle sets a chat's title from its first question verbatim
// whenever that question is under 80 characters (truncated with an ellipsis above that) - every
// question below stays comfortably under that, and unique per attempt (see uniqueId), so the
// chat's title in the chat list is exactly the question text, no truncation to account for.

/**
 * Opens the '@' mention popup for query and selects the suggestion matching libraryName exactly.
 * Mirrors ChatInput.tsx's own findActiveMention: '@' must start a word, and the fragment typed
 * after it (here: the full library name) must not contain whitespace up to the point captured by
 * `query` - passing the exact name as both trigger and filter keeps the suggestion list down to
 * the one match this needs. Library names in this file are hyphenated (no spaces) for exactly
 * this reason.
 */
async function referenceLibrary(page: Page, libraryName: string) {
  const input = page.getByPlaceholder('Stellen Sie eine Frage …')
  await input.fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
}

async function toggleUseKnowledgeOff(page: Page) {
  await page.getByLabel('Wissen nutzen').click()
}

/**
 * Covers #529 (part of Epic #523): the persistent, space-owned chat and its `@`-reference /
 * "Wissen nutzen" search scope, built on top of test(e2e) #424's upload/share/search chain and
 * its helpers (see fixtures/chat.ts). Every scenario starts its own fresh chat
 * (`startFreshChat`) rather than relying on scenario order, for the same reason
 * knowledge-libraries.spec.ts does (see its module doc comment): chats are persisted per account,
 * so a later scenario reusing the same dev user would otherwise still see an earlier scenario's
 * turn in the DOM. Scenarios also each create their own library rather than sharing one across
 * scenarios (review finding on PR #554, nit 3): a scenario run in isolation (`--grep`) must not
 * depend on module state a different scenario would otherwise have set.
 */
test.describe.serial('Chats im Space, @-Referenzen und Wissens-Schalter (#529)', () => {
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
    // reload), not about which library "Wissen nutzen" = on (the default, left untouched here) end
    // up citing - that search is an unscoped topK over the entire readable corpus, and this file
    // deliberately runs after several other specs that keep adding to it (see the module doc
    // comment on sort order above). Asserting on this scenario's own document specifically would
    // make the test fragile to how large that corpus has grown by the time it runs, not to
    // anything this scenario is meant to catch - scenarios 2 and 5 below cover "the right library
    // was searched" deterministically via "Wissen nutzen" off plus an explicit @-reference.
    await expectAnyCitedSource(page)

    // sendMessage implicitly creates the chat and replaces the URL to point at it (#548) - once
    // that has happened, the URL no longer ends in ".../chats/new".
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    const chatUrl = page.url()

    await page.reload()

    await expect(page).toHaveURL(chatUrl)
    // AppShell.tsx renders the chat itself inside <main> and the space's chat list inside <nav>
    // (Sidebar) - once the chat has a title, both show the same question text (see the module doc
    // comment on deriveTitle), so every assertion below scopes to one or the other rather than the
    // whole page, to stay a single, unambiguous match.
    await expect(page.getByRole('main').getByText(question)).toBeVisible()
    await expectAnyCitedSource(page)
    // The chat list (sidebar) reloads from the backend on a fresh page load - its entry is what
    // proves the chat itself, not just this one still-open tab, was actually persisted.
    await expect(page.getByRole('navigation').getByText(question, { exact: true })).toBeVisible()
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
    await toggleUseKnowledgeOff(page)
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
    await toggleUseKnowledgeOff(page)

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
    const adminInput = adminPage.getByPlaceholder('Stellen Sie eine Frage …')
    await adminInput.fill(`@${privateLibraryName}`)
    await expect(adminPage.getByRole('option', { name: privateLibraryName })).toBeVisible()

    await startFreshChat(bPage)
    const input = bPage.getByPlaceholder('Stellen Sie eine Frage …')
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
    const main = page.getByRole('main')
    const sidebar = page.getByRole('navigation')

    await createLibraryWithDocument(page, libraryName, DOCUMENT_A_PATH, DOCUMENT_A_NAME)

    await startFreshChat(page)
    await askQuestion(page, questionChat1)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    await expect(main.getByText(questionChat1)).toBeVisible()
    const chat1Url = page.url()

    // "Neuer Chat" in the sidebar (same as startFreshChat) starts a second, independent chat in
    // the same space rather than reusing chat 1.
    await startFreshChat(page)
    await toggleUseKnowledgeOff(page)
    await referenceLibrary(page, libraryName)
    await askQuestion(page, questionChat2)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    const chat2Url = page.url()
    expect(chat2Url).not.toBe(chat1Url)

    // Chat 2 shows only its own turn and its own sticky reference chip.
    await expect(main.getByText(questionChat1)).toHaveCount(0)
    await expect(main.getByText(questionChat2)).toBeVisible()
    await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toBeVisible()

    // Back to chat 1 via the sidebar list, then a reload (review finding on PR #554, nit 5): the
    // list navigation alone would only prove in-memory Zustand-store state survives switching
    // chats, not that chat 1's own history and (lack of) reference are actually persisted server-
    // side, independent of chat 2's.
    await sidebar.getByText(questionChat1, { exact: true }).click()
    await expect(page).toHaveURL(chat1Url)
    await page.reload()
    await expect(page).toHaveURL(chat1Url)
    await expect(main.getByText(questionChat1)).toBeVisible()
    await expect(main.getByText(questionChat2)).toHaveCount(0)
    await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toHaveCount(0)
  })
})
