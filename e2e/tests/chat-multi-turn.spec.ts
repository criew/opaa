import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import {
  askQuestion,
  sidebarChatEntry,
  startAnotherChatViaSidebar,
  startFreshChat,
} from '../fixtures/chat'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries } from '../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../fixtures/promptLibraries'
import { assignToDefaultSpace } from '../fixtures/spaces'
import type { Locator, Page, TestInfo } from '@playwright/test'

/**
 * A conversation over several question-answer rounds: order, evidence per answer, AI label, scroll
 * position, the same history after a reload and a chat switch, wide and narrow in both colour
 * schemes - and a model failure in the middle of the conversation, after which it carries on.
 *
 * Preconditions go through the API (two upload libraries with one small document each, assigned
 * to dev-admin's default space); the UI is used only where it is under test. Every round narrows
 * the search scope to one library via an @-reference, so each answer provably carries its own
 * evidence (ai-stub gives every chunk the same embedding).
 */

const ADMIN = 'dev-admin'

interface SeededLibrary {
  id: string
  name: string
  documentName: string
}

/**
 * Creates an upload library with one document, waits for it to be indexed and assigns it to the
 * default space. The id lands in `createdIds` right after creation, so a later failure still
 * leaves it to the cleanup.
 */
async function seedLibrary(
  label: string,
  suffix: string,
  createdIds: string[],
): Promise<SeededLibrary> {
  const name = `E2E-Mehrrunden-${label}-${suffix}`
  const documentName = `mehrrunden-${label.toLowerCase()}-${suffix}.txt`
  const id = await createKnowledgeLibraryViaApi(ADMIN, { name })
  createdIds.push(id)
  const api = await apiAs(ADMIN)
  try {
    const uploaded = await api.post(`/api/v1/libraries/${id}/documents`, {
      multipart: {
        file: {
          name: documentName,
          mimeType: 'text/plain',
          buffer: Buffer.from(
            `Dienstanweisung ${label}: Anträge der Gruppe ${label} werden binnen zehn Tagen bearbeitet.`,
            'utf-8',
          ),
        },
      },
    })
    expect(uploaded.ok(), await uploaded.text()).toBe(true)
    await expect
      .poll(
        async () => {
          const response = await api.get(`/api/v1/libraries/${id}/documents?size=10`)
          const body = (await response.json()) as { items: Array<{ status: string }> }
          return body.items.map((item) => item.status).join(',')
        },
        { timeout: 30_000 },
      )
      .toBe('INDEXED')
    await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', id)
    return { id, name, documentName }
  } finally {
    await api.dispose()
  }
}

function messageList(page: Page): Locator {
  return page.getByTestId('message-list')
}

/** Every message of the history, in display order. */
function messages(page: Page): Locator {
  return messageList(page).locator('[data-message-id]')
}

/** The answers: each carries the label "KI-erzeugt". */
function answers(page: Page): Locator {
  return messages(page).filter({ has: page.getByTestId('ai-answer-mark') })
}

/** Replaces the search scope with exactly the library `libraryName` (chip bar above the input). */
async function scopeTo(page: Page, libraryName: string, previous?: string): Promise<void> {
  if (previous) {
    await page
      .getByRole('button', { name: `Bibliotheksreferenz ${previous} entfernen` })
      .press('Backspace')
  }
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
  await expect(
    page.getByRole('button', { name: `Bibliotheksreferenz ${libraryName} entfernen` }),
  ).toBeVisible()
}

/** Asks a question and waits until its answer stands in the history as answer no. `expectedAnswers`. */
async function askRound(page: Page, question: string, expectedAnswers: number): Promise<void> {
  await askQuestion(page, question)
  await expect(answers(page)).toHaveCount(expectedAnswers, { timeout: 15_000 })
  await expect(page.getByText('Denkt nach …')).toHaveCount(0)
}

/**
 * Opens the evidence drawer of answer no. `index` and asserts that `cited` is cited and `absent`
 * is missing, among the uncited sources too. Whether there are uncited ones comes from the
 * answer's own count line, as in showUncitedEvidence (fixtures/chat.ts).
 */
async function expectAnswerEvidence(
  page: Page,
  index: number,
  cited: string,
  absent: string,
): Promise<void> {
  const answer = answers(page).nth(index)
  await answer.getByRole('button', { name: 'Belege anzeigen' }).click()
  const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
  await expect(drawer).toBeVisible()
  const rows = drawer.getByTestId('evidence-doc')
  await expect(rows.filter({ hasText: cited })).toHaveAttribute('data-cited', 'true')
  const summary = await answer.getByTestId('evidence-summary').textContent()
  if (summary?.includes('geprüft')) {
    const toggle = drawer.getByRole('button', { name: 'Nicht zitierte anzeigen' })
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  }
  await expect(rows.filter({ hasText: absent })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
}

/** Question, answer, question, answer, … with the questions in the order they were asked. */
async function expectAlternatingHistory(page: Page, questions: string[]): Promise<void> {
  await expect(messages(page)).toHaveCount(questions.length * 2)
  for (const [round, question] of questions.entries()) {
    await expect(messages(page).nth(round * 2)).toContainText(question)
    await expect(
      messages(page)
        .nth(round * 2)
        .getByTestId('ai-answer-mark'),
    ).toHaveCount(0)
    await expect(
      messages(page)
        .nth(round * 2 + 1)
        .getByTestId('ai-answer-mark'),
    ).toBeVisible()
  }
}

async function scrollHistory(page: Page, to: 'top' | 'bottom'): Promise<void> {
  await messageList(page).evaluate((element, where) => {
    const list = element as unknown as { scrollTop: number; scrollHeight: number }
    list.scrollTop = where === 'top' ? 0 : list.scrollHeight
  }, to)
}

/** Screenshots of the start and the end of the history, attached to the report. */
async function attachHistoryShots(page: Page, testInfo: TestInfo, variant: string): Promise<void> {
  for (const position of ['top', 'bottom'] as const) {
    await scrollHistory(page, position)
    // The list's own smooth scroll to the newest message may still be running after a load.
    await expect(messages(page).nth(position === 'top' ? 0 : -1)).toBeInViewport()
    const path = testInfo.outputPath(
      `mehrrunden-${variant}-${position === 'top' ? 'anfang' : 'ende'}.png`,
    )
    await page.screenshot({ path })
    await testInfo.attach(`Verlauf ${variant} (${position === 'top' ? 'Anfang' : 'Ende'})`, {
      path,
      contentType: 'image/png',
    })
  }
}

async function hasHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate<boolean>(
    `(() => {
      const root = document.documentElement
      const list = document.querySelector('[data-testid="message-list"]')
      return root.scrollWidth > root.clientWidth + 1 ||
        (list !== null && list.scrollWidth > list.clientWidth + 1)
    })()`,
  )
}

/** Whether the history is longer than its box and scrolled away from its start. */
async function historyIsScrolled(page: Page): Promise<boolean> {
  return messageList(page).evaluate((element) => {
    const list = element as unknown as {
      scrollTop: number
      scrollHeight: number
      clientHeight: number
    }
    return list.scrollHeight > list.clientHeight && list.scrollTop > 0
  })
}

test.describe.serial('Chat über mehrere Runden (#3467)', { tag: '@browser-smoke' }, () => {
  const createdLibraryIds = cleanupLibraries()
  let libraryA: SeededLibrary
  let libraryB: SeededLibrary

  test.beforeAll(async () => {
    test.setTimeout(90_000)
    const suffix = `${Date.now()}`
    ;[libraryA, libraryB] = await Promise.all([
      seedLibrary('A', suffix, createdLibraryIds),
      seedLibrary('B', suffix, createdLibraryIds),
    ])
  })

  test('1. Vier Runden: Reihenfolge, Belege je Antwort, Neuladen, Chatwechsel, Darstellung', async ({
    authenticatedPage: page,
  }, testInfo) => {
    test.setTimeout(90_000)
    const id = `${Date.now()}-${testInfo.retry}`
    const questions = [
      `Wie schnell werden Anträge der Gruppe A bearbeitet? (${id})`,
      `Gilt das auch für Eilanträge der Gruppe A? (${id})`,
      `Und wie ist es bei Gruppe B? (${id})`,
      `Fasse die Regel für Gruppe B bitte in einem Satz zusammen. (${id})`,
    ]
    const scopes = [libraryA, libraryA, libraryB, libraryB]

    await startFreshChat(page)
    for (const [round, question] of questions.entries()) {
      if (round === 0 || scopes[round] !== scopes[round - 1]) {
        await scopeTo(page, scopes[round].name, round === 0 ? undefined : scopes[round - 1].name)
      }
      await askRound(page, question, round + 1)
      // The newest answer is in view without scrolling, its evidence button included.
      await expect(
        answers(page).last().getByRole('button', { name: 'Belege anzeigen' }),
      ).toBeInViewport({ ratio: 1 })
      await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeEnabled()
    }
    // By round four the history no longer fits its box: the newest answer is in view because the
    // list scrolled to it, not because everything fits.
    expect(await historyIsScrolled(page)).toBe(true)
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/(?!new$)[^/]+$/)
    const chatUrl = page.url()

    await expectAlternatingHistory(page, questions)
    // Every answer keeps its own evidence: earlier answers are not rewritten by later rounds.
    for (const [round, scope] of scopes.entries()) {
      const other = scope === libraryA ? libraryB : libraryA
      await expectAnswerEvidence(page, round, scope.documentName, other.documentName)
    }

    await page.reload()
    await expectAlternatingHistory(page, questions)
    await expectAnswerEvidence(page, 0, libraryA.documentName, libraryB.documentName)
    await expectAnswerEvidence(page, 3, libraryB.documentName, libraryA.documentName)

    await startAnotherChatViaSidebar(page)
    await expect(messages(page)).toHaveCount(0)
    await sidebarChatEntry(page, chatUrl).click()
    await expect(page).toHaveURL(chatUrl)
    await expectAlternatingHistory(page, questions)

    await attachHistoryShots(page, testInfo, 'breit-hell')
    await expectNoSeriousA11yViolations(page, 'Chat mit vier Runden (hell)')
    await answers(page).nth(1).getByRole('button', { name: 'Belege anzeigen' }).click()
    const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
    await expect(drawer).toBeVisible()
    const drawerShot = testInfo.outputPath('mehrrunden-breit-hell-belegfenster.png')
    await page.screenshot({ path: drawerShot })
    await testInfo.attach('Verlauf breit-hell mit Belegfenster', {
      path: drawerShot,
      contentType: 'image/png',
    })
    await expectNoSeriousA11yViolations(page, 'Chat mit vier Runden, Belegfenster offen')
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()

    await page.emulateMedia({ colorScheme: 'dark' })
    await attachHistoryShots(page, testInfo, 'breit-dunkel')
    await expectNoSeriousA11yViolations(page, 'Chat mit vier Runden (dunkel)')

    await page.emulateMedia({ colorScheme: 'light' })
    await page.setViewportSize({ width: 320, height: 720 })
    await expect(messages(page)).toHaveCount(questions.length * 2)
    await expect.poll(() => hasHorizontalOverflow(page)).toBe(false)
    await attachHistoryShots(page, testInfo, '320px-hell')
  })

  test('2. Modellfehler mitten im Gespräch: Meldung, Eingabe bleibt bedienbar, es geht weiter', async ({
    authenticatedPage: page,
  }, testInfo) => {
    const id = `${Date.now()}-${testInfo.retry}`
    const before = `Wie schnell werden Anträge der Gruppe A bearbeitet? (${id})`
    const failing = `E2E-MODELL-FEHLER Und bei Gruppe A im Urlaub? (${id})`
    const after = `Dann noch einmal: wie schnell bei Gruppe A? (${id})`
    const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })

    await startFreshChat(page)
    await scopeTo(page, libraryA.name)
    await askRound(page, before, 1)

    await askQuestion(page, failing)
    await expect(page.getByRole('alert').filter({ hasText: 'Fehler im KI-Dienst' })).toBeVisible()
    await expect(page.getByText('Denkt nach …')).toHaveCount(0)
    // The failed question stays, without any answer of its own.
    await expect(messages(page)).toHaveCount(3)
    await expect(messages(page).nth(2)).toContainText(failing)
    await expect(answers(page)).toHaveCount(1)
    await expect(input).toBeEnabled()

    await askRound(page, after, 2)
    await expect(page.getByRole('alert').filter({ hasText: 'Fehler im KI-Dienst' })).toHaveCount(0)
    await expectAnswerEvidence(page, 1, libraryA.documentName, libraryB.documentName)

    // A failed round is not persisted: after a reload the history holds the two answered rounds
    // only, without the failed question and without any half answer.
    await page.reload()
    await expect(messages(page)).toHaveCount(4)
    await expect(answers(page)).toHaveCount(2)
    await expect(messageList(page).getByText(before)).toBeVisible()
    await expect(messageList(page).getByText(after)).toBeVisible()
    await expect(messageList(page).getByText(failing)).toHaveCount(0)
  })
})
