import { useSpaceStore } from './spaceStore'
import { useGroupStore } from './groupStore'
import { useLibraryStore } from './libraryStore'
import { useChatStore } from './chatStore'
import { useChatListStore } from './chatListStore'
import { useDocumentStore } from './documentStore'
import { useIndexingStore } from './indexingStore'
import { useGrantStore } from './grantStore'
import { useLlmModelStore } from './llmModelStore'
import { bumpSessionEpoch } from './sessionEpoch'

/**
 * Every store that caches data scoped to the signed-in user's session - space/group/library
 * lists, chat history, document lists, indexing status, grants. authStore's logout calls
 * `reset()` on each of these, so a subsequent sign-in by a different user in the same tab never
 * sees the previous user's data before its own load completes (#440).
 *
 * `useUiStore` is deliberately not included here: its theme/sidebar preference is a device
 * setting, not user-session data, and is meant to survive a logout.
 *
 * Adding a new store that caches session-scoped data means adding it here too - a single place
 * rather than a growing list of individual imports/calls in authStore.
 *
 * A function, not a top-level array constant (#575 review): chatStore.ts and chatListStore.ts
 * import from each other (chatStore needs useChatListStore to touch a space's chat list,
 * chatListStore needs dropChatSettingsCache to clean up a deleted chat's settings cache), and a
 * top-level `export const resettableStores = [...]` evaluates its array literal - including
 * whichever of the two circularly-imported store hooks resolves first - at *this* module's own
 * load time. Which of chatStore.ts/chatListStore.ts had already finished initializing by then
 * depended on which module some other file (e.g. a test) happened to import first, silently
 * dropping the not-yet-initialized one from the array as `undefined`. Building the array inside a
 * function instead defers that read until resetAllStores() actually runs, by which point the
 * whole module graph - circular or not - has always finished loading.
 */
function resettableStores() {
  return [
    useSpaceStore,
    useGroupStore,
    useLibraryStore,
    useChatStore,
    useChatListStore,
    useDocumentStore,
    useIndexingStore,
    useGrantStore,
    useLlmModelStore,
  ]
}

export function resetAllStores(): void {
  // Bumped first, before any store's own reset() runs (#575): every async action across the
  // stores below captures the epoch at its start and checks it again before writing back once its
  // await resolves, so an in-flight request that resolves after this call skips its write-back
  // instead of resurrecting the previous user's data into a store this function is about to empty.
  // See sessionEpoch.ts for the full rationale.
  bumpSessionEpoch()
  resettableStores().forEach((store) => store.getState().reset())
}
