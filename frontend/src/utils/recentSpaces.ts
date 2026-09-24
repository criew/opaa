/**
 * Welche Spaces zuletzt genutzt wurden — die Ablage hinter dem Einstieg nach der Anmeldung (#1911)
 * und hinter der Liste im Space-Auswahlmenü (#1912). Eine Ablage für beides: Zwei getrennte
 * Merklisten könnten auseinanderlaufen, und „zuletzt genutzt" bedeutet an beiden Stellen dasselbe.
 *
 * Bewusst nur im Browser (`localStorage`, #1911: „kein Server-Feld"). Daraus folgt zweierlei, und
 * beides trägt diese Datei:
 *
 * - **Kein Zuschnitt auf ein Konto.** Die Liste enthält nur Space-Kennungen. Jede Leseseite gleicht
 *   sie gegen die Spaces ab, die der Dienst dem angemeldeten Konto ausliefert — eine Kennung aus
 *   einer fremden Sitzung am selben Rechner fällt damit heraus, statt irgendetwas preiszugeben.
 * - **Jeder Zugriff darf fehlschlagen.** Ein Browser mit gesperrtem Speicher oder ein fremder
 *   Eintrag unter demselben Schlüssel führt zur leeren Liste, nie zu einem Fehler: Der Einstieg in
 *   die Anwendung darf an einer Bequemlichkeit nicht scheitern.
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

/** Die gemerkten Kennungen, aktuellste zuerst. Leer, wenn nichts (Brauchbares) gespeichert ist. */
export function recentSpaceIds(): string[] {
  let stored: string | null
  try {
    stored = window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return []
  }
  if (!stored) return []
  try {
    const parsed: unknown = JSON.parse(stored)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((entry): entry is string => typeof entry === 'string')
  } catch {
    return []
  }
}

/** Merkt sich die Nutzung eines Space; ein bereits gemerkter rückt nach vorn. */
export function rememberSpaceUse(spaceId: string): void {
  if (!spaceId) return
  const next = [spaceId, ...recentSpaceIds().filter((id) => id !== spaceId)].slice(
    0,
    MAX_REMEMBERED,
  )
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Das Merken ist eine Bequemlichkeit; ein gesperrter Speicher darf die Nutzung nicht stören.
  }
}

/**
 * Der zuletzt genutzte Space aus `spaces`, oder `null`, wenn keiner der gemerkten (noch) dabei ist
 * — der Rückfall auf den persönlichen Space liegt beim Aufrufer, der ihn ohnehin kennt (#1911).
 *
 * Der Abgleich gegen die ausgelieferte Liste ist die ganze Zugriffsprüfung, die es hier braucht:
 * Was der Dienst nicht ausliefert — archiviert, Mitgliedschaft entzogen, fremdes Konto —, kann
 * hier nicht getroffen werden.
 */
export function lastUsedSpace<T extends { id: string }>(spaces: readonly T[]): T | null {
  for (const id of recentSpaceIds()) {
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
  limit: number = MAX_RECENT_SPACES_IN_MENU,
): T[] {
  const recent = recentSpaceIds()
    .map((id) => spaces.find((space) => space.id === id))
    .filter((space): space is T => space !== undefined)
  const rest = spaces.filter((space) => !recent.some((used) => used.id === space.id))
  return [...recent, ...rest].slice(0, limit)
}
