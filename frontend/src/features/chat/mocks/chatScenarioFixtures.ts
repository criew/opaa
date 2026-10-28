import type { ChatDetail } from '@/shared/api/types'
import { currentMockScenario, LONG_COMPOUND, type MockScenario } from '@/shared/mocks/mockScenario'
import { ensureScenarioSpaces } from '@/features/space/mocks/spaceScenarioFixtures'
import { mockChatArchive, mockChatDetails, mockChatPins, replaceMockChats } from './chatFixtures'
import { mockQueryResponses } from './queryFixtures'

/**
 * The chat lists under the fixture scenarios of `@/shared/mocks/mockScenario`:
 *
 * - `scale`: 100 chats in the personal space (8 pinned, two archived), 60 in Engineering, 30 in
 *   Phoenix and 5 in each generated space - every ninth title 80-120 characters long, every
 *   23rd untitled
 * - `small`: the personal space's two fixture chats, one of them pinned
 * - `empty`: no chats at all
 *
 * The scenario is applied once per page load (the in-page handlers keep their state until the
 * next reload); `default` and the loading/failure scenarios keep the regular fixtures.
 */

const TOPICS = [
  'Bauantrag Lindenstraße 12 – Fristen',
  'Widerspruch Gebührenbescheid',
  'Anwohnerparkausweis Nebenstelle 3',
  'Kita-Platzvergabe Kriterien',
  'Dienstanweisung Homeoffice',
  'Reisekostenabrechnung Fortbildung',
  'Vergabe Reinigungsleistungen',
  'Datenschutz-Folgenabschätzung Fachverfahren',
  'Sitzungsvorlage Haushaltssatzung',
  'Wohngeld Einkommensgrenzen',
  'Sondernutzung Straßenfest',
  'Gewerbeanmeldung Online-Handel',
  'Hundesteuer Ermäßigung',
  'Personalratsbeteiligung Arbeitszeit',
  'Barrierefreiheit Internetauftritt',
  'Aktenplan Ordnungsamt',
  'Bescheidvorlage Elterngeld',
  'Zuständigkeit Fundsachen',
  'Brandschutzbegehung Rathaus',
  'Formular Meldebescheinigung',
  'Fördermittel Digitalpakt',
  'Ratsinformationssystem Zugang',
  'Mietspiegel Fortschreibung',
  'Baumschutzsatzung Ausnahmen',
  'Urlaubsregelung Jahreswechsel',
  'Satzung Friedhofsgebühren',
  'Einbürgerung Unterlagen',
  'Straßenreinigung Winterdienst',
  'Ausschreibung IT-Arbeitsplätze',
  'Bürgeranfrage Spielplatz',
]

const QUALIFIERS = ['', ' – Rückfrage', ' (Entwurf)', ' – Stand Oktober']

const LONG_TITLES = [
  'Zusammenfassung der Stellungnahmen zur Änderung des Bebauungsplans Nr. 47 „Am Mühlenbach“ – Teil 2',
  `${LONG_COMPOUND} Gemarkung Österfeld, Flur 3 – fehlende Unterlagen nachfordern`,
  'Vorbereitung der Ausschusssitzung Soziales, Jugend und Familie: Beschlussvorlage Eingliederungshilfe',
]

function titleFor(i: number): string | null {
  if (i % 23 === 0) return null
  if (i % 9 === 0) return LONG_TITLES[(i / 9) % LONG_TITLES.length]
  const topic = TOPICS[(i - 1) % TOPICS.length]
  return topic + QUALIFIERS[Math.floor((i - 1) / TOPICS.length) % QUALIFIERS.length]
}

/** 7 hours apart from the end of October 2026 backwards, so "Zuletzt verwendet" spans months. */
function timeFor(i: number): string {
  return new Date(Date.parse('2026-10-28T16:00:00Z') - i * 7 * 3_600_000).toISOString()
}

function generatedChat(spaceId: string, prefix: string, i: number): ChatDetail {
  const id = `chat-${prefix}-${i}`
  const title = titleFor(i)
  const at = timeFor(i)
  const answer = mockQueryResponses[i % mockQueryResponses.length]
  return {
    id,
    spaceId,
    authorId: 'mock-user-id',
    title,
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [
      {
        id: `${id}-1`,
        chatId: id,
        role: 'USER',
        generatedByAi: false,
        content: title ?? 'Kurze Frage ohne Titel',
        createdAt: at,
      },
      {
        id: `${id}-2`,
        chatId: id,
        role: 'ASSISTANT',
        generatedByAi: true,
        content: answer.answer,
        sources: answer.sources,
        createdAt: at,
      },
    ],
    noteItems: [],
    createdAt: at,
    updatedAt: at,
  }
}

const SCALE_COUNTS: [spaceId: string, prefix: string, count: number][] = [
  ['space-personal', 'scale-personal', 100],
  ['space-engineering', 'scale-engineering', 60],
  ['space-phoenix', 'scale-phoenix', 30],
]

/** Which chats of the personal space are pinned in `scale`, newest pin first. */
const SCALE_PINNED = [3, 11, 19, 27, 40, 55, 72, 88]

let appliedScenario: MockScenario | null = null

/** Applies the active scenario to the chat fixtures once; call before every chat handler. */
export function ensureScenarioChats(): void {
  const scenario = currentMockScenario()
  if (appliedScenario === scenario) return
  appliedScenario = scenario
  if (scenario === 'empty') {
    replaceMockChats({})
  } else if (scenario === 'small') {
    replaceMockChats(
      Object.fromEntries(
        Object.entries(mockChatDetails).filter(([, chat]) => chat.spaceId === 'space-personal'),
      ),
    )
    mockChatPins['chat-personal-1'] = '2026-10-20T08:00:00Z'
  } else if (scenario === 'scale') {
    ensureScenarioSpaces()
    const counts = [...SCALE_COUNTS]
    for (let s = 1; s <= 24; s++) counts.push([`space-scale-${s}`, `scale-s${s}`, 5])
    for (const [spaceId, prefix, count] of counts) {
      for (let i = 1; i <= count; i++) {
        const chat = generatedChat(spaceId, prefix, i)
        mockChatDetails[chat.id] = chat
      }
    }
    SCALE_PINNED.forEach((i, rank) => {
      mockChatPins[`chat-scale-personal-${i}`] = timeFor(rank)
    })
    mockChatArchive['chat-scale-personal-97'] = '2026-10-01T08:00:00Z'
    mockChatArchive['chat-scale-personal-98'] = '2026-10-01T08:00:00Z'
  }
}

/** Forgets the applied scenario, so the next handler call applies it to fresh fixtures. */
export function resetScenarioChats(): void {
  appliedScenario = null
}
