import { useSyncExternalStore } from 'react'

/**
 * Which spaces were used most recently — the storage behind the entry point after login
 * and behind the list in the space selection menu. One storage for both: two separate
 * lists could drift apart, and „zuletzt genutzt" means the same in both places.
 *
 * Deliberately only in the browser (`localStorage`, no server field). Three things follow from
 * that, and this file carries all three:
 *
 * - **Not tied to an account.** The list contains only space identifiers. Every reading side
 *   matches them against the spaces the service delivers to the signed-in account — an identifier
 *   from another session on the same machine thus drops out instead of revealing anything.
 * - **Every access may fail.** A browser with blocked storage or a foreign
 *   entry under the same key leads to the empty list, never to an error: entering the
 *   application must not fail on a convenience.
 * - **The storage is observable.** `localStorage` alone triggers no re-render; a menu that
 *   reads the order only when mounting shows a just opened space only after a
 *   reload. Hence {@link useRecentSpaceIds} via `useSyncExternalStore`.
 *
 * Logging out deliberately clears nothing here — unlike the session stores in
 * `resettableStores`. Exactly what was remembered should outlast the logout and still be there
 * on the next entry.
 */

const STORAGE_KEY = 'opaa.spaces.recent'

/**
 * More spaces are remembered than the menu shows: if someone loses access to a remembered
 * space, the next one moves up instead of the list getting shorter.
 */
const MAX_REMEMBERED = 10

/** How many entries the space selection menu shows at most. */
export const MAX_RECENT_SPACES_IN_MENU = 5

const listeners = new Set<() => void>()

/**
 * Cached are the **raw** value and what was parsed from it. `useSyncExternalStore` requires
 * the same reference for unchanged storage — an array freshly built on every call would lead
 * to an endless loop. The comparison is still against the actual storage content and not
 * only against what was last written here: so whatever another tab (or a test) writes
 * directly takes effect as well.
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

/** The remembered identifiers, most recent first. Empty when nothing (usable) is stored. */
export function recentSpaceIds(): string[] {
  return readSnapshot()
}

/**
 * The remembered identifiers as state: whoever reads them this way re-renders as soon as a space
 * is used — without this path the menu would show a space just opened via „Alle Spaces" only
 * after a reload.
 */
export function useRecentSpaceIds(): string[] {
  return useSyncExternalStore(subscribe, readSnapshot, readSnapshot)
}

/** Remembers the use of a space; one already remembered moves to the front. */
export function rememberSpaceUse(spaceId: string): void {
  if (!spaceId) return
  const previous = readSnapshot()
  if (previous[0] === spaceId) return
  const next = [spaceId, ...previous.filter((id) => id !== spaceId)].slice(0, MAX_REMEMBERED)
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Remembering is a convenience; blocked storage must not disturb the use.
    // The observers are notified anyway: they then read the same old state, which
    // is correct - nothing was remembered.
  }
  listeners.forEach((listener) => listener())
}

/**
 * The most recently used space from `spaces`, or `null` when none of the remembered ones is
 * (still) among them — the fallback to the personal space lies with the caller, who knows it
 * anyway.
 *
 * The match against the delivered list is the whole access check needed here:
 * whatever the service does not deliver — archived, membership revoked, another account —
 * cannot be hit here.
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
 * The spaces for the selection menu: first the most recently used in order of use,
 * then — until `limit` is reached — the others in the order in which they come in.
 *
 * Filling up is no by-product: whoever signs in for the first time has no order of use,
 * and an empty menu would be the worst answer to „show the most recently used".
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
