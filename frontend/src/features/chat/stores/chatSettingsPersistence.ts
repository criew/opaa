import type { ChatUpdateRequest, MetadataFilter } from '@/shared/api/types'
import { updateChat } from '../api/chatApi'
import { isEmptyMetadataFilter } from '../api/queryApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import type { ChatState } from './chatStore'

// Monotonically increasing token guarding applyScopeChange's PATCH failure handler: a
// settings PATCH for chat A that is still in flight when the user navigates to chat B must not
// roll chat B's state back on failure. Same pattern as chatStore's chatLoadSequence - module-level,
// never read by a component.
let settingsUpdateSequence = 0

// Serializes settings PATCHes per chat: each chat's queue is a promise chain, so a
// PATCH for a chat only reaches the server once the previous one for that same chat has settled.
// Without this, two rapid chip clicks fire two PATCHes in parallel and the network - not the order
// the user clicked in - decides which one the server (and thus the persisted chat) ends up
// applying last. Deliberately module-level, mirroring settingsUpdateSequence/chatLoadSequence.
export const settingsUpdateChains = new Map<string, Promise<void>>()

// The most recently server-confirmed useKnowledge/referencedLibraryIds pair per chat, used as the
// rollback base after a failed PATCH. Updated whenever a chat is loaded or implicitly created, and
// whenever a chained PATCH succeeds. Rolling back to this - rather than to "whatever the local
// state was right before this particular call" - matters once PATCHes are chained: if an earlier
// queued change for the same chat also failed, its own local snapshot is already stale, and rolling
// back to it would resurrect an optimistic value the server never saw either.
export const confirmedSettingsByChatId = new Map<string, ConfirmedSettings>()

/** The server-confirmed chat settings the chip bar and the filter mirror. */
interface ConfirmedSettings {
  scope: SearchScope
  referencedLibraryIds: string[]
  metadataFilter: MetadataFilter | null
}

// Monotonically increasing, per-chat counter incremented each time applyScopeChange starts a new
// settings change for that chat. loadChat captures this chat's value before firing its GET
// and compares it again once the response arrives - if it changed in the meantime, a settings
// change for this exact chat was initiated while the GET was in flight, and that change's own
// success/failure handler in applyScopeChange - not this now-stale GET response - is what must
// decide the chat's scope/referencedLibraryIds. Unlike settingsUpdateChains (removed once the
// chain settles), this map is never deleted while the chat exists, so the check still works even
// when the settings PATCH's response arrived - and its chain entry was already cleaned up - before
// the GET's own response does.
export const settingsChangeSequenceByChatId = new Map<string, number>()

/**
 * Clears both module-level settings-persistence maps. Used by the store's
 * own reset() action (logout) and exported for chatStore.test.ts's
 * beforeEach, since these maps are module state, not store state, and so survive across test
 * cases unless cleared explicitly.
 */
export function clearSettingsPersistenceCache(): void {
  settingsUpdateChains.clear()
  confirmedSettingsByChatId.clear()
  settingsChangeSequenceByChatId.clear()
}

/**
 * Test-only accessor for confirmedSettingsByChatId - lets chatStore.test.ts verify
 * that a settings PATCH resolving after a logout does not repopulate this module-level map,
 * which is otherwise entirely private to this module.
 */
export function getConfirmedSettingsForTesting(chatId: string): ConfirmedSettings | undefined {
  return confirmedSettingsByChatId.get(chatId)
}

/**
 * Normalizes a filter for state and comparison - an object without any condition is no
 * filter (null), and the type list is sorted so two equal filters compare equal.
 */
export function normalizeMetadataFilter(
  filter: MetadataFilter | null | undefined,
): MetadataFilter | null {
  if (isEmptyMetadataFilter(filter)) return null
  const documentTypes = [...(filter?.documentTypes ?? [])].sort()
  const libraryFields = (filter?.libraryFields ?? [])
    .map(normalizeCondition)
    .sort(byKey((condition) => `${condition.libraryId}:${condition.fieldKey}`))
  const formatFields = (filter?.formatFields ?? [])
    .map(normalizeCondition)
    .sort(byKey((condition) => condition.fieldKey))
  return {
    ...(documentTypes.length > 0 ? { documentTypes } : {}),
    ...(filter?.documentDateFrom ? { documentDateFrom: filter.documentDateFrom } : {}),
    ...(filter?.documentDateTo ? { documentDateTo: filter.documentDateTo } : {}),
    ...(libraryFields.length > 0 ? { libraryFields } : {}),
    ...(formatFields.length > 0 ? { formatFields } : {}),
  }
}

/** One condition without its null members and with sorted lists, for a stable comparison. */
function normalizeCondition<T extends object>(condition: T): T {
  return Object.fromEntries(
    Object.entries(condition)
      .filter(([, value]) => value !== null && value !== undefined)
      .map(([key, value]) => [key, Array.isArray(value) ? [...value].sort() : value]),
  ) as T
}

function byKey<T>(key: (item: T) => string) {
  return (a: T, b: T) => key(a).localeCompare(key(b))
}

export function sameMetadataFilter(a: MetadataFilter | null, b: MetadataFilter | null): boolean {
  return JSON.stringify(normalizeMetadataFilter(a)) === JSON.stringify(normalizeMetadataFilter(b))
}

// The chip bar is the only search-scope control: 'all' shows the special @Space-Wissen
// chip (backend useKnowledge=true), 'libraries' shows the sticky concrete-library chips
// (useKnowledge=false + referencedLibraryIds), 'none' is an emptied bar (useKnowledge=false, no
// ids) - "Durchsucht wird, was in der Leiste steht." @Space (space-associated libraries) is
// not a fourth state.
export type SearchScope = 'all' | 'libraries' | 'none'

/**
 * Applies a chip-bar scope change locally and persists it via a per-chat PATCH chain, if a chat
 * exists yet, and tracks the chain's tail as `pendingSettingsUpdate` so
 * sendMessage can await it. On failure, rolls the local state back to the last server-confirmed
 * settings and surfaces `error` - the server's chat settings otherwise silently diverge from what
 * the chip bar shows.
 * useKnowledge and referencedLibraryIds are always sent together, even when only one conceptually
 * changed, because a scope change - e.g. the first concrete chip replacing @Space-Wissen - flips
 * both fields atomically; splitting them into separate PATCHes could let a chat briefly sit with
 * useKnowledge=true and stale referencedLibraryIds server-side.
 *
 * Chained rather than fired in parallel: two PATCHes at once for rapid chip changes on the same
 * chat would let the network - not the order the user acted in -
 * decide which settings the server (and thus the persisted chat) ended up with. Queuing this
 * call's PATCH behind any still-in-flight one for the same chat guarantees the server sees them in
 * the order they were made, and that whichever one is last in the queue is also the last one the
 * server applies.
 */
export function applyScopeChange(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  nextScope: SearchScope,
  nextReferencedLibraryIds: string[],
): void {
  applySettingsChange(
    get,
    set,
    {
      scope: nextScope,
      referencedLibraryIds: nextReferencedLibraryIds,
      metadataFilter: get().metadataFilter,
    },
    {
      useKnowledge: nextScope === 'all',
      referencedLibraryIds: nextScope === 'libraries' ? nextReferencedLibraryIds : [],
    },
  )
}

/**
 * The shared persistence path of every chat setting, scope and filter alike: `next`
 * is the full local settings state applied optimistically and confirmed on success, `patch` only
 * what this change sends - a scope change leaves the filter untouched and vice versa.
 */
export function applySettingsChange(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  next: ConfirmedSettings,
  patch: ChatUpdateRequest,
): void {
  // Captured before the optimistic set() below: the chat this change applies to, and this call's
  // token in the settings-update sequence. Both are checked in the failure handler below,
  // once the PATCH's response - possibly stale - actually arrives.
  const requestChatId = get().chatId
  const requestId = ++settingsUpdateSequence
  // This call's token in the session epoch, checked in the success handler below
  // before confirmedSettingsByChatId.set() - a logout in the meantime clears that module-level
  // map (see chatStore's own reset()), and without this guard a PATCH that was already in flight
  // at that point still repopulates it once it resolves, resurrecting a rollback base for a chat
  // the reset just discarded.
  const sessionEpoch = currentSessionEpoch()

  set({ ...next })

  const chatId = requestChatId
  if (!chatId) return

  // Bumps this chat's settings-change counter before the PATCH below is even queued, so any
  // loadChat GET already in flight for this chat - captured its baseline earlier - recognizes on
  // arrival that this change now, not its own stale snapshot, decides scope/referencedLibraryIds.
  settingsChangeSequenceByChatId.set(chatId, (settingsChangeSequenceByChatId.get(chatId) ?? 0) + 1)

  // Queues this call's PATCH behind whatever is already queued for this chat - `.catch(() =>
  // undefined)` keeps the chain alive across an earlier queued PATCH's failure, so this call's own
  // request still gets sent (and its own outcome handled independently below) instead of being
  // silently skipped.
  const previousChainTail = settingsUpdateChains.get(chatId) ?? Promise.resolve()
  const promise: Promise<void> = previousChainTail
    .catch(() => undefined)
    .then(() => updateChat(chatId, patch))
    .then(
      () => {
        // A logout in the meantime already cleared confirmedSettingsByChatId (see
        // reset()'s clearSettingsPersistenceCache() call) - writing to it here regardless would
        // resurrect a rollback base for a chat that reset just discarded.
        if (isStaleSessionEpoch(sessionEpoch)) return
        // This request's settings are now the server's own record - the rollback base for any
        // *later* PATCH on this chat that fails.
        confirmedSettingsByChatId.set(chatId, { ...next })
        // A late-succeeding PATCH is, at the moment it resolves, the most authoritative
        // source for this chat's settings - more so than a loadChat that raced it and read the
        // server before this PATCH committed (Chat A, slow PATCH -> navigate to B -> back to A,
        // loadChat still sees the old value -> this PATCH then lands). Without applying it here
        // too, the chip bar is left showing loadChat's stale snapshot even though the server (and
        // confirmedSettingsByChatId above) has already moved on. Same two guards as the failure
        // handler below: only the chat this call was made for, and only the most recently
        // requested change for it, may still update local state once its response arrives.
        if (get().chatId !== requestChatId) return
        if (requestId !== settingsUpdateSequence) return
        set({ ...next })
      },
      (err: unknown) => {
        // A stale failure must not roll back a chat the user has since navigated away from -
        // without this guard, a late-arriving PATCH failure for chat A silently
        // resurrects chat A's pre-change state on top of chat B, which is now active.
        if (get().chatId !== requestChatId) return
        // Nor may it roll back over a *newer* change to the same chat that has already applied
        // its own optimistic state (or even already succeeded) - only the most recently
        // requested change may still roll back on failure, so the last action wins rather than
        // the last response.
        if (requestId !== settingsUpdateSequence) return
        const message =
          err instanceof Error ? err.message : 'Änderung konnte nicht gespeichert werden'
        // Rolls back to the last state the server actually confirmed for this chat, not to
        // whatever was locally applied right before this call - if an earlier queued change for
        // the same chat also failed, that snapshot would itself already be stale.
        const rollback: ConfirmedSettings = confirmedSettingsByChatId.get(chatId) ?? {
          scope: 'all',
          referencedLibraryIds: [],
          metadataFilter: null,
        }
        set({ ...rollback, error: message })
      },
    )

  settingsUpdateChains.set(chatId, promise)
  set({ pendingSettingsUpdate: promise })
  void promise.finally(() => {
    if (get().pendingSettingsUpdate === promise) {
      set({ pendingSettingsUpdate: null })
    }
    // Deterministic cleanup of settingsUpdateChains: only drop this chat's entry if it is
    // still the tail, i.e. no newer applyScopeChange call for the same chat has already queued
    // behind (and thus overwritten) it - deleting unconditionally here could rip out a newer
    // call's own chain entry out from under it. confirmedSettingsByChatId is deliberately left
    // untouched: it must survive this chain settling, see the warning on its declaration above.
    if (settingsUpdateChains.get(chatId) === promise) {
      settingsUpdateChains.delete(chatId)
    }
  })
}
