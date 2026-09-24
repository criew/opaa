import { useSyncExternalStore } from 'react'

/**
 * Welche Spaces zuletzt genutzt wurden — die Ablage hinter dem Einstieg nach der Anmeldung (#1911)
 * und hinter der Liste im Space-Auswahlmenü (#1912). Eine Ablage für beides: Zwei getrennte
 * Merklisten könnten auseinanderlaufen, und „zuletzt genutzt" bedeutet an beiden Stellen dasselbe.
 *
 * Bewusst nur im Browser (`localStorage`, #1911: „kein Server-Feld"). Daraus folgt dreierlei, und
 * alles drei trägt diese Datei:
 *
 * - **Kein Zuschnitt auf ein Konto.** Die Liste enthält nur Space-Kennungen. Jede Leseseite gleicht
 *   sie gegen die Spaces ab, die der Dienst dem angemeldeten Konto ausliefert — eine Kennung aus
 *   einer fremden Sitzung am selben Rechner fällt damit heraus, statt irgendetwas preiszugeben.
 * - **Jeder Zugriff darf fehlschlagen.** Ein Browser mit gesperrtem Speicher oder ein fremder
 *   Eintrag unter demselben Schlüssel führt zur leeren Liste, nie zu einem Fehler: Der Einstieg in
 *   die Anwendung darf an einer Bequemlichkeit nicht scheitern.
 * - **Die Ablage ist beobachtbar.** `localStorage` allein löst kein Neuzeichnen aus; ein Menü, das
 *   die Reihenfolge nur beim Aufbau liest, zeigt einen gerade geöffneten Space erst nach einem
 *   Neuladen. Deshalb {@link useRecentSpaceIds} über `useSyncExternalStore`.
 *
 * Die Abmeldung räumt hier bewusst nichts weg — anders als bei den Sitzungsspeichern in
 * `resettableStores`. Genau das Gemerkte soll die Abmeldung überdauern, sonst beantwortet #1911
 * („beim nächsten Einstieg") sich selbst mit Nein.
 */

const STORAGE_KEY = 'opaa.spaces.recent'

/**
 * Gemerkt werden mehr Spaces, als das Menü zeigt: Verliert jemand den Zugang zu einem gemerkten
 * Space, rückt der nächste nach, statt dass die Liste kürzer wird.
 */
const MAX_REMEMBERED = 10

/** Wie viele Einträge das Space-Auswahlmenü höchstens zeigt (#1912). */
export const MAX_RECENT_SPACES_IN_MENU = 5

const listeners = new Set<() => void>()

/**
 * Zwischengespeichert wird das **Rohe** und das daraus Geparste. `useSyncExternalStore` verlangt
 * bei unveränderter Ablage dieselbe Referenz — ein bei jedem Aufruf frisch gebautes Array führte
 * zur Endlosschleife. Verglichen wird trotzdem gegen den tatsächlichen Speicherinhalt und nicht
 * nur gegen das zuletzt selbst Geschriebene: So wirkt auch, was ein anderer Tab (oder ein Test)
 * direkt hineinschreibt.
 */
let cachedRaw: string | null = null
let cachedIds: string[] = []

function readSnapshot(): string[] {
  let raw: string | null
  try {
    raw = window.localStorage.getItem(STORAGE_KEY)
  } catch {
    raw = null
  }
  if (raw === cachedRaw) return cachedIds
  cachedRaw = raw
  cachedIds = parse(raw)
  return cachedIds
}

function parse(raw: string | null): string[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((entry): entry is string => typeof entry === 'string')
  } catch {
    return []
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Die gemerkten Kennungen, aktuellste zuerst. Leer, wenn nichts (Brauchbares) gespeichert ist. */
export function recentSpaceIds(): string[] {
  return readSnapshot()
}

/**
 * Die gemerkten Kennungen als Zustand: Wer sie so liest, zeichnet neu, sobald ein Space genutzt
 * wird — ohne diesen Weg zeigte das Menü einen gerade über „Alle Spaces" geöffneten Space erst
 * nach einem Neuladen.
 */
export function useRecentSpaceIds(): string[] {
  return useSyncExternalStore(subscribe, readSnapshot, readSnapshot)
}

/** Merkt sich die Nutzung eines Space; ein bereits gemerkter rückt nach vorn. */
export function rememberSpaceUse(spaceId: string): void {
  if (!spaceId) return
  const previous = readSnapshot()
  if (previous[0] === spaceId) return
  const next = [spaceId, ...previous.filter((id) => id !== spaceId)].slice(0, MAX_REMEMBERED)
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Das Merken ist eine Bequemlichkeit; ein gesperrter Speicher darf die Nutzung nicht stören.
    // Die Beobachter werden trotzdem benachrichtigt: Sie lesen dann denselben alten Stand, was
    // richtig ist - gemerkt wurde nichts.
  }
  listeners.forEach((listener) => listener())
}

/**
 * Der zuletzt genutzte Space aus `spaces`, oder `null`, wenn keiner der gemerkten (noch) dabei ist
 * — der Rückfall auf den persönlichen Space liegt beim Aufrufer, der ihn ohnehin kennt (#1911).
 *
 * Der Abgleich gegen die ausgelieferte Liste ist die ganze Zugriffsprüfung, die es hier braucht:
 * Was der Dienst nicht ausliefert — archiviert, Mitgliedschaft entzogen, fremdes Konto —, kann
 * hier nicht getroffen werden.
 */
export function lastUsedSpace<T extends { id: string }>(
  spaces: readonly T[],
  recentIds: readonly string[] = recentSpaceIds(),
): T | null {
  for (const id of recentIds) {
    const match = spaces.find((space) => space.id === id)
    if (match) return match
  }
  return null
}

/**
 * Die Spaces für das Auswahlmenü (#1912): zuerst die zuletzt genutzten in Nutzungsreihenfolge,
 * danach — bis `limit` erreicht ist — die übrigen in der Reihenfolge, in der sie hereinkommen.
 *
 * Das Auffüllen ist kein Beiwerk: Wer sich zum ersten Mal anmeldet, hat keine Nutzungsreihenfolge,
 * und ein leeres Menü wäre die schlechteste Antwort auf „zeige die zuletzt genutzten".
 */
export function spacesByRecentUse<T extends { id: string }>(
  spaces: readonly T[],
  recentIds: readonly string[] = recentSpaceIds(),
  limit: number = MAX_RECENT_SPACES_IN_MENU,
): T[] {
  const recent = recentIds
    .map((id) => spaces.find((space) => space.id === id))
    .filter((space): space is T => space !== undefined)
  const rest = spaces.filter((space) => !recent.some((used) => used.id === space.id))
  return [...recent, ...rest].slice(0, limit)
}
