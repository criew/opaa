import { useSpaceStore } from './spaceStore'
import { useGroupStore } from './groupStore'
import { useLibraryStore } from './libraryStore'
import { useChatStore } from './chatStore'
import { useChatListStore } from './chatListStore'
import { useDocumentStore } from './documentStore'
import { useIndexingStore } from './indexingStore'
import { useGrantStore } from './grantStore'
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
 */
export const resettableStores = [
  useSpaceStore,
  useGroupStore,
  useLibraryStore,
  useChatStore,
  useChatListStore,
  useDocumentStore,
  useIndexingStore,
  useGrantStore,
]

export function resetAllStores(): void {
  // Bumped first, before any store's own reset() runs (#575): every async action across the
  // stores below captures the epoch at its start and checks it again before writing back once its
  // await resolves, so an in-flight request that resolves after this call skips its write-back
  // instead of resurrecting the previous user's data into a store this function is about to empty.
  // See sessionEpoch.ts for the full rationale.
  bumpSessionEpoch()
  resettableStores.forEach((store) => store.getState().reset())
}
