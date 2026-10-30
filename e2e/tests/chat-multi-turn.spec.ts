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
import { assignToDefaultSpace } from '../fixtures/spaces'
import type { Locator, Page, TestInfo } from '@playwright/test'

/**
 * Ein Gespräch über mehrere Frage-Antwort-Runden: Reihenfolge, Belege je Antwort,
 * Kennzeichnung, Blickposition, Bestand nach Neuladen und Chatwechsel, Darstellung breit und
 * schmal in beiden Farbschemata - und ein Modellfehler mitten im Gespräch, nach dem es weitergeht.
 *
 * Vorbedingungen laufen über die API (zwei Upload-Bibliotheken mit je einem kleinen Dokument im
 * Standard-Space von dev-admin); die Oberfläche wird nur dort benutzt, wo sie geprüft wird. Jede
 * Runde schränkt den Suchbereich per @-Referenz auf eine Bibliothek ein, damit jede Antwort
 * nachweislich ihren eigenen Beleg trägt (gleiche Einbettung für alle Chunks im ai-stub).
 */

const ADMIN = 'dev-admin'

interface SeededLibrary {
  id: string
  name: string
  documentName: string
}

/** Bibliothek mit einem Dokument anlegen, auf „indiziert" warten und dem Standard-Space zuordnen. */
async function seedLibrary(label: string, suffix: string): Promise<SeededLibrary> {
  const name = `E2E-Mehrrunden-${label}-${suffix}`
  const documentName = `mehrrunden-${label.toLowerCase()}-${suffix}.txt`
  const api = await apiAs(ADMIN)
  try {
    const created = await api.post('/api/v1/libraries', { data: { name, sourceType: 'UPLOAD' } })
    expect(created.status(), await created.text()).toBe(201)
    const { id } = (await created.json()) as { id: string }
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

/** Alle Nachrichten des Verlaufs in Anzeigereihenfolge. */
function messages(page: Page): Locator {
  return messageList(page).locator('[data-message-id]')
}

/** Die Antworten: jede trägt das Kennzeichen „KI-erzeugt". */
function answers(page: Page): Locator {
  return messages(page).filter({ has: page.getByTestId('ai-answer-mark') })
}

/** Ersetzt den Suchbereich durch genau die Bibliothek `libraryName` (Chip-Leiste über der Eingabe). */
async function scopeTo(page: Page, libraryName: string, previous?: string): Promise<void> {
  if (previous) {
    await page
      .getByRole('button', { name: `Bibliotheksreferenz ${previous} entfernen` })
      .press('Backspace')
  }
  const input = page.getByRole('combobox', { name: 'Nachricht eingeben' })
  await input.fill(`@${libraryName}`)
  await page.getByRole('option', { name: libraryName }).click()
  await expect(page.getByLabel(`Bibliotheksreferenz ${libraryName} entfernen`)).toBeVisible()
}

/** Stellt eine Frage und wartet, bis ihre Antwort als `expectedAnswers`-te im Verlauf steht. */
async function askRound(page: Page, question: string, expectedAnswers: number): Promise<void> {
  await askQuestion(page, question)
  await expect(answers(page)).toHaveCount(expectedAnswers, { timeout: 15_000 })
  await expect(page.getByText('Denkt nach …')).toHaveCount(0)
}

/**
 * Öffnet das Belegfenster der `index`-ten Antwort und prüft: `cited` ist zitiert, `absent` kommt
 * auch unter den nicht zitierten Belegen nicht vor.
 */
async function expectAnswerEvidence(
  page: Page,
  index: number,
  cited: string,
  absent: string,
): Promise<void> {
  await answers(page).nth(index).getByRole('button', { name: 'Belege anzeigen' }).click()
  const drawer = page.getByRole('dialog', { name: 'Belege dieser Antwort' })
  await expect(drawer).toBeVisible()
  const rows = drawer.getByTestId('evidence-doc')
  await expect(rows.filter({ hasText: cited })).toHaveAttribute('data-cited', 'true')
  const uncited = drawer.getByRole('button', { name: 'Nicht zitierte anzeigen' })
  if ((await uncited.count()) > 0) await uncited.click()
  await expect(rows.filter({ hasText: absent })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
}

/** Reihenfolge F, A, F, A, … mit den Fragen in der gestellten Reihenfolge. */
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

/** Bildschirmfotos von Anfang und Ende des Verlaufs als Anhang am Report. */
async function attachHistoryShots(page: Page, testInfo: TestInfo, variant: string): Promise<void> {
  for (const position of ['top', 'bottom'] as const) {
    await scrollHistory(page, position)
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
  return page.evaluate(() => {
    const doc = (
      globalThis as unknown as {
        document: {
          documentElement: { scrollWidth: number; clientWidth: number }
          querySelector: (selector: string) => { scrollWidth: number; clientWidth: number } | null
        }
      }
    ).document
    const list = doc.querySelector('[data-testid="message-list"]')
    return (
      doc.documentElement.scrollWidth > doc.documentElement.clientWidth + 1 ||
      (list !== null && list.scrollWidth > list.clientWidth + 1)
    )
  })
}

test.describe.serial('Chat über mehrere Runden (#3467)', () => {
  const createdLibraryIds = cleanupLibraries()
  let libraryA: SeededLibrary
  let libraryB: SeededLibrary

  test.beforeAll(async () => {
    const suffix = `${Date.now()}`
    libraryA = await seedLibrary('A', suffix)
    libraryB = await seedLibrary('B', suffix)
    createdLibraryIds.push(libraryA.id, libraryB.id)
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
      ).toBeInViewport()
      await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeEnabled()
    }
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
    expect(await hasHorizontalOverflow(page)).toBe(false)
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
    await expect(answers(page)).toHaveCount(1)
    await expect(input).toBeEnabled()

    await askRound(page, after, 2)
    await expect(page.getByRole('alert').filter({ hasText: 'Fehler im KI-Dienst' })).toHaveCount(0)
    await expectAnswerEvidence(page, 1, libraryA.documentName, libraryB.documentName)

    // The failed round leaves no half answer behind, also after a reload.
    await page.reload()
    await expect(answers(page)).toHaveCount(2)
    await expect(messageList(page).getByText(before)).toBeVisible()
    await expect(messageList(page).getByText(after)).toBeVisible()
  })
})
