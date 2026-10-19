import { create } from 'zustand'
import {
  settingsUpdateChains,
  settingsChangeSequenceByChatId,
  confirmedSettingsByChatId,
  normalizeMetadataFilter,
  applyScopeChange,
  sameMetadataFilter,
  applySettingsChange,
  clearSettingsPersistenceCache,
} from './chatSettingsPersistence'
import { registerSessionStore } from '@/shared/session/resettableStores'
import { getChat } from '../api/chatApi'
import type { MetadataFilter } from '@/shared/api/types'
import type { ChatState } from './chatState'
import { applyChatDetail, omitKeys, reloadChatTurns } from './chatDetail'
import { sendChatMessage } from './chatSendMessage'
import {
  clearRemovedNoteItemCache,
  confirmNoteItemRemovals,
  removedNoteItemIdsByChatId,
  removeNoteItem,
} from './chatNoteRemovals'
import { manuallyRenamedChatIds } from './chatTitleReload'
import {
  parkedAsReturnedQuestion,
  parkedRefusalsByChatId,
  parkedRefusalsByNewChatSpaceId,
} from './chatRefusals'
import {
  advanceChatLoadSequence,
  currentChatLoadSequence,
  currentCompletedTurnSequence,
  inFlightSends,
  persistedUserTurnsByChatId,
  recordPersistedTurns,
} from './chatTurnTracking'

export type { ChatState, RefusedQuestion } from './chatState'
export { QUESTION_MAX_LENGTH } from './chatRefusals'
export { clearRemovedNoteItemCache } from './chatNoteRemovals'
export { clearManualRenameMarks, markChatManuallyRenamed } from './chatTitleReload'

/**
 * Drops chatId's entries from both module-level settings-persistence maps: a deleted chat
 * can never again be the target of a queued PATCH or a rollback base, so leaving its entries
 * behind would just grow both maps for the rest of the session. settingsUpdateChains is always
 * dropped immediately - a later sendMessage for this (deleted) chat must not queue/await behind a
 * chain that will never again matter. confirmedSettingsByChatId is more delicate: if a PATCH for
 * this chat is still in flight at the moment of deletion, dropping it right away would rip out the
 * rollback/confirmation base that PATCH's own still-running success/failure handler may need (see
 * the warning on confirmedSettingsByChatId's declaration above) - e.g. the user deleted the chat
 * they were currently viewing without navigating away first, and its own pending settings change
 * then fails. `pendingChain` - this chat's most recently queued call at the time of deletion - is
 * chained behind every earlier one for the same chat, so attaching the drop to its own settlement
 * is safe: by the time it fires, every settings PATCH still outstanding for this chat has settled
 * too.
 */
export function dropChatSettingsCache(chatId: string): void {
  const pendingChain = settingsUpdateChains.get(chatId)
  settingsUpdateChains.delete(chatId)
  settingsChangeSequenceByChatId.delete(chatId)
  // A deleted chat is never loaded again, so its pending note removals would never be
  // confirmed - and nothing would ever filter against them either.
  removedNoteItemIdsByChatId.delete(chatId)
  persistedUserTurnsByChatId.delete(chatId)
  parkedRefusalsByChatId.delete(chatId)
  manuallyRenamedChatIds.delete(chatId)
  if (pendingChain) {
    void pendingChain.finally(() => confirmedSettingsByChatId.delete(chatId))
    return
  }
  confirmedSettingsByChatId.delete(chatId)
}

export const useChatStore = create<ChatState>((set, get) => ({
  spaceId: null,
  chatId: null,
  title: null,
  messages: [],
  isLoading: false,
  isLoadingChat: false,
  error: null,
  returnedQuestion: null,
  scope: 'all',
  referencedLibraryIds: [],
  metadataFilter: null,
  noteItems: [],
  archivedAt: null,
  deletionDueAt: null,
  pendingSettingsUpdate: null,
  loadChat: async (chatId: string) => {
    const requestId = advanceChatLoadSequence()
    // This chat's settings-change counter, captured before the GET below is sent. Compared
    // again once the response arrives - see settingsChangeSequenceByChatId's declaration for
    // why a per-chat counter, rather than settingsUpdateChains, is needed to catch this ordering.
    const settingsSequenceAtStart = settingsChangeSequenceByChatId.get(chatId) ?? 0
    const completedTurnsAtStart = currentCompletedTurnSequence()
    set({ isLoadingChat: true, error: null, returnedQuestion: null })
    try {
      const detail = await getChat(chatId)
      // A newer loadChat/startNewChat call superseded this one while the request was in flight -
      // applying this response now would resurrect a chat the user already navigated away from.
      if (requestId !== currentChatLoadSequence()) return
      recordPersistedTurns(chatId, detail.messages)
      // Loading the chat is what confirms a local removal - a point the server no longer
      // reports leaves the filter set, a point it still reports keeps being filtered out.
      confirmNoteItemRemovals(chatId, detail.noteItems ?? [])
      const detailState = applyChatDetail(detail)
      // A settings change for this exact chat was started while this GET was in flight - its
      // own success/failure handler in applyScopeChange is the authoritative source for scope/
      // referencedLibraryIds now, not this response's snapshot, read before that change committed.
      // Everything else this response carries (messages, title, ...) is unaffected and still
      // applied.
      const settingsRacedByPatch =
        (settingsChangeSequenceByChatId.get(chatId) ?? 0) !== settingsSequenceAtStart
      if (settingsRacedByPatch) {
        set({
          ...omitKeys(detailState, ['scope', 'referencedLibraryIds', 'metadataFilter']),
          isLoadingChat: false,
        })
      } else {
        set({ ...detailState, isLoadingChat: false })
        // The just-loaded settings are the server's own record - the rollback base for any PATCH
        // failure while this chat stays active.
        confirmedSettingsByChatId.set(detailState.chatId, {
          scope: detailState.scope,
          referencedLibraryIds: detailState.referencedLibraryIds,
          metadataFilter: detailState.metadataFilter,
        })
      }
      // Superseded loads returned above; ChatPage hands the question on only to the matching route.
      const parked = get().chatId === chatId ? parkedRefusalsByChatId.get(chatId) : undefined
      if (parked) {
        parkedRefusalsByChatId.delete(chatId)
        set({
          error: parked.reason,
          returnedQuestion: parkedAsReturnedQuestion(
            parked,
            get,
            set,
            () => get().chatId === chatId,
          ),
        })
      }
      // An answer that arrived while this GET was in flight may be missing from its snapshot.
      if (currentCompletedTurnSequence() !== completedTurnsAtStart)
        reloadChatTurns(get, set, chatId)
    } catch (err) {
      if (requestId !== currentChatLoadSequence()) return
      const message = err instanceof Error ? err.message : 'Chat konnte nicht geladen werden'
      // Also drop the stale chat/space out of state: leaving the
      // previous chat active after a failed load would silently send the next message to a chat
      // the user is no longer looking at.
      set({
        error: message,
        isLoadingChat: false,
        chatId: null,
        spaceId: null,
        messages: [],
        title: null,
        noteItems: [],
        archivedAt: null,
        deletionDueAt: null,
        isLoading: false,
      })
    }
  },

  // A new chat resets the sticky knowledge-scope controls too - they belong to the conversation
  // that gets started here, not to whichever chat was open before. No API call yet: the chat is
  // only persisted once the first message is sent (see sendMessage).
  startNewChat: (spaceId: string) => {
    // Invalidates any loadChat still in flight - otherwise its eventual response could overwrite
    // this synchronous reset. The superseded loadChat handler then
    // returns early (its requestId no longer matches chatLoadSequence) without ever reaching its
    // own set() call, so isLoadingChat must be cleared here too - otherwise ChatPage's spinner
    // never clears and the chat input never reappears.
    advanceChatLoadSequence()
    set({
      spaceId,
      chatId: null,
      title: null,
      messages: [],
      error: null,
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: null,
      noteItems: [],
      archivedAt: null,
      deletionDueAt: null,
      isLoadingChat: false,
      isLoading: false,
      returnedQuestion: null,
    })
    const parked = parkedRefusalsByNewChatSpaceId.get(spaceId)
    if (parked) {
      parkedRefusalsByNewChatSpaceId.delete(spaceId)
      set({
        error: parked.reason,
        returnedQuestion: parkedAsReturnedQuestion(
          parked,
          get,
          set,
          () => get().chatId === null && get().spaceId === spaceId,
        ),
      })
    }
  },

  sendMessage: (question: string, usedPrompt?: { id: string; title: string }) =>
    sendChatMessage(get, set, question, usedPrompt),

  setScopeAll: () => {
    // Already showing @Space-Wissen - nothing to replace. Short-circuiting here avoids a PATCH
    // that would just re-send the chat's current settings.
    if (get().scope === 'all') return
    // Re-adding @Space-Wissen replaces any concrete chips - the two are mutually
    // exclusive states of the same bar, never shown together.
    applyScopeChange(get, set, 'all', [])
  },

  addReferencedLibrary: (libraryId: string) => {
    const { scope, referencedLibraryIds } = get()
    // The first concrete chip replaces @Space-Wissen; from 'libraries' or 'none' it simply
    // extends/starts the selection.
    const previousIds = scope === 'libraries' ? referencedLibraryIds : []
    if (previousIds.includes(libraryId)) return
    applyScopeChange(get, set, 'libraries', [...previousIds, libraryId])
  },

  removeReferencedLibrary: (libraryId: string) => {
    const next = get().referencedLibraryIds.filter((id) => id !== libraryId)
    // Removing the last concrete chip empties the bar rather than falling back to @Space-Wissen -
    // "leere Leiste = ohne Wissen", with an explicit one-click way back via setScopeAll.
    applyScopeChange(get, set, next.length > 0 ? 'libraries' : 'none', next)
  },

  clearScope: () => {
    // Already empty - same reasoning as setScopeAll's short-circuit above.
    if (get().scope === 'none') return
    applyScopeChange(get, set, 'none', [])
  },

  setMetadataFilter: (filter: MetadataFilter | null) => {
    const next = normalizeMetadataFilter(filter)
    if (sameMetadataFilter(get().metadataFilter, next)) return
    const { scope, referencedLibraryIds } = get()
    // An object without any condition clears the chat's filter server-side (PATCH semantics:
    // null would leave it unchanged).
    applySettingsChange(
      get,
      set,
      { scope, referencedLibraryIds, metadataFilter: next },
      { metadataFilter: next ?? {} },
    )
  },

  removeNoteItem: (itemId: string) => removeNoteItem(get, set, itemId),

  clearReturnedQuestion: () => set({ returnedQuestion: null }),

  reset: () => {
    // Invalidates any loadChat still in flight, matching startNewChat above - otherwise a
    // response arriving after reset() could resurrect the previous user's chat.
    advanceChatLoadSequence()
    inFlightSends.clear()
    persistedUserTurnsByChatId.clear()
    parkedRefusalsByChatId.clear()
    parkedRefusalsByNewChatSpaceId.clear()
    // The pending removals belong to the chat the previous user had open - keeping them
    // would filter points out of the next user's chats until some load confirmed them.
    clearRemovedNoteItemCache()
    // Both module-level maps are keyed by chatId, not scoped to any
    // particular user - a stale entry for a chat the previous user had open would otherwise
    // survive into the next user's session in the same tab, e.g. letting a late PATCH failure
    // for that old chat roll back to settings the new user never saw.
    clearSettingsPersistenceCache()
    set({
      spaceId: null,
      chatId: null,
      title: null,
      messages: [],
      isLoading: false,
      isLoadingChat: false,
      error: null,
      returnedQuestion: null,
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: null,
      noteItems: [],
      archivedAt: null,
      deletionDueAt: null,
      pendingSettingsUpdate: null,
    })
  },

  applyArchivedAt: (chatId: string, archivedAt: string | null, deletionDueAt = null) => {
    if (get().chatId === chatId) set({ archivedAt, deletionDueAt })
  },

  applyTitle: (chatId: string, title: string | null) => {
    if (get().chatId === chatId) set({ title })
  },
}))

registerSessionStore(useChatStore)
