import { expect, test } from '../fixtures/auth'
import { askQuestion, clearSearchScope, startFreshChat } from '../fixtures/chat'
import type { Locator, Page, TestInfo } from '@playwright/test'

/**
 * Covers test(e2e) #1489 (T7 of Epic #1482): the Gesprächsnotiz in the browser - its display
 * threshold, removing a point, and what only a real rendering engine can show
 * (docs/features/conversation-memory.md, "Oberfläche", and ADR-0031).
 *
 * **Deterministic condensation.** Note points come from a model call. The suite's KI stub
 * (e2e/ai-stub/server.mjs) recognises io.opaa.chat.ChatNoteExtractionService's prompt and answers
 * it with a fixed line per user message - a message carrying "Ich arbeite …" or "bitte knapp"
 * produces exactly that point, every other message produces the "KEINE" sentinel. Same idea as the
 * stub already serves the chat-title prompt with: one fixed answer per prompt shape, never a model.
 *
 * **Display lag by one round.** The condensation runs after the answer, so the note state an
 * answer carries is the one that went *into* it - a point condensed from turn n first shows with
 * turn n+1's answer or on a reload. Every scenario below therefore waits for the note state the
 * server actually holds (waitForNoteItems) before asking the next question, instead of trusting
 * that the asynchronous condensation of the previous turn happened to win the race. That wait is
 * on observed state, not on a timespan.
 *
 * **No knowledge base.** Every chat here empties its chip bar before the first question
 * (`clearSearchScope`): the note is kept and used regardless of the search scope (a decided
 * Randfall of the specification), and an unscoped search over dev-admin's ever-growing corpus
 * would only add latency and coupling to what the note has nothing to do with.
 */

const DEV_USER_HEADER = 'X-OPAA-Dev-User'
const ADMIN_USER = 'dev-admin'

// What the stub condenses, and what the note point then reads - the pair is the contract between
// e2e/ai-stub/server.mjs's NOTE_RULES and the assertions below.
const WORKPLACE_STATEMENT = 'Ich arbeite im Bürgerbüro Nebenstelle 3'
const WORKPLACE_NOTE = 'Arbeitet im Bürgerbüro Nebenstelle 3'
const BREVITY_STATEMENT = 'Bitte knapp antworten'
const BREVITY_NOTE = 'Möchte knappe Antworten'

// 195 characters after condensation - just under the specification's 200-character cap, so the
// point arrives in the browser uncut and any shortening seen there is the stylesheet's doing, not
// the backend's (which has its own unit tests for the cap).
const LONG_WORKPLACE_STATEMENT =
  'Ich arbeite im Bürgerbüro der Nebenstelle 3 am Rathausplatz, zuständig für ' +
  'Anwohnerparkausweise, Bewohnerparkzonen, Sondernutzungserlaubnisse, Halteverbotszonen und die ' +
  'Ausgabe von Bewohnerplaketten'
const LONG_WORKPLACE_NOTE = LONG_WORKPLACE_STATEMENT.replace('Ich arbeite', 'Arbeitet')

// Unique per run and per retry, for the same reason space-chats.spec.ts does it: a retried attempt
// re-runs from scratch while whatever the failed attempt persisted is still in the database.
const runId = Date.now()

function uniqueId(testInfo: TestInfo): string {
  return `${runId}-${testInfo.retry}`
}

/** The chat header's Gesprächsnotiz button - absent entirely while the note has no surface. */
function noteToggle(page: Page): Locator {
  return page.getByRole('button', { name: /^Gesprächsnotiz · \d+$/ })
}

/** The expanded panel; `Collapse unmountOnExit` keeps it out of the DOM while collapsed. */
function notePanel(page: Page): Locator {
  return page.getByRole('region', { name: 'Gesprächsnotiz' })
}

interface ChatDetail {
  messages: { role: string }[]
  noteItems?: { id: string; text: string }[] | null
}

async function fetchChat(page: Page, chatId: string): Promise<ChatDetail> {
  const response = await page.request.get(`/api/v1/chats/${chatId}`, {
    headers: { [DEV_USER_HEADER]: ADMIN_USER },
  })
  expect(response.status()).toBe(200)
  return (await response.json()) as ChatDetail
}

/**
 * Asks one question and returns once its answer has arrived, yielding the chat's id. Waits for the
 * query response and then for the "Denkt nach …" indicator to be gone, so the next step acts on a
 * rendered turn rather than on an in-flight one.
 */
async function askTurn(page: Page, question: string): Promise<string> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' && candidate.url().endsWith('/api/v1/query'),
    ),
    askQuestion(page, question),
  ])
  expect(response.status()).toBe(200)
  await expect(page.getByText('Denkt nach …')).toHaveCount(0)
  return ((await response.json()) as { chatId: string }).chatId
}

/**
 * Waits until the chat's persisted note holds exactly `expected` points. The condensation of a
 * turn runs off the request thread, so this is the one point where a scenario has to wait for
 * something the chat page cannot show yet (below the three-round threshold the note has no
 * surface at all) - observed server state, never a fixed delay.
 */
async function waitForNoteItems(page: Page, chatId: string, expected: number): Promise<void> {
  await expect
    .poll(async () => (await fetchChat(page, chatId)).noteItems?.length ?? 0, {
      message: `Die Gesprächsnotiz von Chat ${chatId} sollte ${expected} Punkte tragen`,
      timeout: 15_000,
    })
    .toBe(expected)
}

/** A fresh chat of the acting user's default space, with an empty search scope. */
async function startChatWithoutKnowledge(page: Page): Promise<void> {
  await startFreshChat(page)
  await clearSearchScope(page)
}

test.describe('Gesprächsnotiz im Chat (#1489)', () => {
  test('1. Schaltfläche erscheint erst mit der dritten abgeschlossenen Runde', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    await startChatWithoutKnowledge(page)

    const chatId = await askTurn(
      page,
      `${WORKPLACE_STATEMENT}. Was kostet ein Anwohnerparkausweis? (${id})`,
    )
    await askTurn(page, `Und wie lange dauert die Bearbeitung? (${id})`)

    // Zwei abgeschlossene Runden, und die Angabe aus Runde 1 ist bereits verdichtet: Die
    // Abwesenheit der Schaltfläche liegt damit an der Rundenuntergrenze, nicht an einer leeren
    // Notiz - ohne diese Wartezeit wäre der folgende Nachweis von beidem gleich gut erklärbar.
    await waitForNoteItems(page, chatId, 1)
    await expect(noteToggle(page)).toHaveCount(0)

    await askTurn(page, `Gibt es eine Ermäßigung für Schwerbehinderte? (${id})`)

    const toggle = noteToggle(page)
    await expect(toggle).toHaveText('Gesprächsnotiz · 1')
    // Zugeklappt als Standard: die Zahl ist das Signal, das Panel existiert noch gar nicht.
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(notePanel(page)).toHaveCount(0)

    await toggle.click()
    const panel = notePanel(page)
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(panel.getByRole('listitem')).toHaveText([WORKPLACE_NOTE])
    await expect(
      panel.getByText(
        'Diese Angaben hat OPAA aus Ihren Nachrichten in diesem Chat festgehalten. Sie fließen in' +
          ' die nächsten Antworten ein.',
      ),
    ).toBeVisible()
  })

  test('2. Entfernen senkt die Zahl und überlebt ein Neuladen', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    await startChatWithoutKnowledge(page)

    // Beide Arten in einer Runde (RAHMEN und ANTWORTFORM, Deckel von zwei Punkten je Runde): so
    // trägt die Notiz zwei unterscheidbare Punkte, und die Zahl kann überhaupt sinken.
    const chatId = await askTurn(
      page,
      `${WORKPLACE_STATEMENT}. ${BREVITY_STATEMENT}. Was kostet ein Anwohnerparkausweis? (${id})`,
    )
    await askTurn(page, `Und wie lange dauert die Bearbeitung? (${id})`)
    await waitForNoteItems(page, chatId, 2)
    await askTurn(page, `Gibt es eine Ermäßigung für Schwerbehinderte? (${id})`)

    const toggle = noteToggle(page)
    await expect(toggle).toHaveText('Gesprächsnotiz · 2')
    await toggle.click()
    const panel = notePanel(page)
    await expect(panel.getByRole('listitem')).toHaveText([WORKPLACE_NOTE, BREVITY_NOTE])
    // Die Art eines Punktes ist eine interne Unterscheidung und erscheint nirgends im sichtbaren
    // Text - obwohl hier je ein Punkt beider Arten in der Liste steht.
    await expect(panel).not.toContainText('RAHMEN')
    await expect(panel).not.toContainText('ANTWORTFORM')

    await panel
      .getByRole('listitem')
      .filter({ hasText: WORKPLACE_NOTE })
      .getByRole('button', { name: 'Notizpunkt entfernen' })
      .click()
    await expect(toggle).toHaveText('Gesprächsnotiz · 1')
    await expect(panel.getByRole('listitem')).toHaveText([BREVITY_NOTE])

    await page.reload()

    // Nach dem Neuladen kommt die Notiz aus der Datenbank: der entfernte Punkt ist fort, der
    // andere da - und das Panel ist wieder zugeklappt, Aufklappen ist Sitzungszustand.
    await expect(noteToggle(page)).toHaveText('Gesprächsnotiz · 1')
    await expect(notePanel(page)).toHaveCount(0)
    await noteToggle(page).click()
    await expect(notePanel(page).getByRole('listitem')).toHaveText([BREVITY_NOTE])

    // Mit dem letzten Punkt verschwindet die Schaltfläche - eine leere Notiz hat keine Oberfläche.
    await notePanel(page).getByRole('button', { name: 'Notizpunkt entfernen' }).click()
    await expect(noteToggle(page)).toHaveCount(0)

    await page.reload()

    // Nicht vor dem geladenen Chat prüfen: eine Abwesenheit wäre sonst auch durch eine noch gar
    // nicht gerenderte Seite erfüllt.
    await expect(page.getByTestId('message-list').getByText(`(${id})`).last()).toBeVisible()
    await expect(noteToggle(page)).toHaveCount(0)
  })

  test('3. Drei Runden ohne Angabe über die Person: keine Schaltfläche', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    await startChatWithoutKnowledge(page)

    const chatId = await askTurn(page, `Was kostet ein Anwohnerparkausweis? (${id})`)
    await askTurn(page, `Und wie lange dauert die Bearbeitung? (${id})`)
    await askTurn(page, `Gibt es eine Ermäßigung für Schwerbehinderte? (${id})`)

    // Die Rundenuntergrenze ist erreicht, die Notiz bleibt trotzdem leer: Damit belegt die
    // Abwesenheit der Schaltfläche die leere Notiz und nicht die Rundenzahl.
    const detail = await fetchChat(page, chatId)
    expect(detail.messages.filter((message) => message.role === 'ASSISTANT')).toHaveLength(3)
    expect(detail.noteItems ?? []).toHaveLength(0)
    await expect(noteToggle(page)).toHaveCount(0)

    await page.reload()

    await expect(page.getByTestId('message-list').getByText(`(${id})`).last()).toBeVisible()
    await expect(noteToggle(page)).toHaveCount(0)
  })

  test('4. Ein sehr langer Notizpunkt bricht um und wird nicht gekürzt', async (
    { authenticatedPage: page },
    testInfo,
  ) => {
    const id = uniqueId(testInfo)
    await startChatWithoutKnowledge(page)

    const chatId = await askTurn(
      page,
      `${LONG_WORKPLACE_STATEMENT}. Was kostet ein Anwohnerparkausweis? (${id})`,
    )
    await askTurn(page, `Und wie lange dauert die Bearbeitung? (${id})`)
    await waitForNoteItems(page, chatId, 1)
    await askTurn(page, `Gibt es eine Ermäßigung für Schwerbehinderte? (${id})`)

    await noteToggle(page).click()
    await expect(notePanel(page).getByRole('listitem')).toHaveText([LONG_WORKPLACE_NOTE])

    // Nur ein echter Rendervorgang kann das zeigen: jsdom rechnet kein CSS, ein noWrap oder
    // textOverflow: ellipsis bliebe in den Unit-Tests der Komponente grün.
    const metrics = await page.evaluate(() => {
      interface MeasuredElement {
        textContent: string | null
        scrollWidth: number
        clientWidth: number
        querySelector(selector: string): MeasuredElement | null
      }
      const browser = globalThis as unknown as {
        document: {
          querySelector(selector: string): MeasuredElement | null
          createRange(): {
            selectNodeContents(node: MeasuredElement): void
            getClientRects(): { length: number }
          }
        }
        getComputedStyle(element: MeasuredElement): { whiteSpace: string; textOverflow: string }
      }
      const paragraph = browser.document
        .querySelector('[role="region"][aria-label="Gesprächsnotiz"]')
        ?.querySelector('li p')
      if (!paragraph) {
        return null
      }
      // Ein Range über den Textinhalt liefert je Zeile ein Rechteck; das Blockelement selbst
      // hätte immer genau eines, egal wie viele Zeilen es umbricht.
      const range = browser.document.createRange()
      range.selectNodeContents(paragraph)
      const style = browser.getComputedStyle(paragraph)
      return {
        text: paragraph.textContent ?? '',
        lineCount: range.getClientRects().length,
        scrollWidth: paragraph.scrollWidth,
        clientWidth: paragraph.clientWidth,
        whiteSpace: style.whiteSpace,
        textOverflow: style.textOverflow,
      }
    })
    if (metrics === null) {
      throw new Error('Im aufgeklappten Panel wurde kein Notizpunkt-Absatz gefunden.')
    }

    expect(metrics.text).toBe(LONG_WORKPLACE_NOTE)
    expect(metrics.lineCount).toBeGreaterThan(1)
    // Nichts ragt aus dem Absatz heraus - genau das wäre bei einer einzeiligen, abgeschnittenen
    // Darstellung der Fall.
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
    expect(metrics.whiteSpace).not.toBe('nowrap')
    expect(metrics.textOverflow).not.toBe('ellipsis')
  })
})
