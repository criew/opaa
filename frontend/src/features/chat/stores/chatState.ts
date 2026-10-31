import type { StoreApi } from 'zustand'
import type { ChatMessage } from '../types/chat'
import type { ChatNoteItem, MetadataFilter } from '@/shared/api/types'
import type { SearchScope } from './chatSettingsPersistence'

/**
 * What `sendMessage` resolves to for a question the server refused: the draft for the input, and
 * `onRestored`, which the input calls once the draft is actually back in it.
 */
export interface RefusedQuestion {
  restoreDraft: string
  onRestored: () => void
}

export interface ChatState {
  /** The space the active (or about-to-be-created) chat lives in - null before any space is
   * known, e.g. right after login before ChatRedirect has resolved a default space. */
  spaceId: string | null
  /** The persisted chat's id, or null for a not-yet-created chat: the first sent
   * message creates it implicitly in `spaceId` (see sendMessage). */
  chatId: string | null
  title: string | null
  messages: ChatMessage[]
  /** True while a question/answer round-trip (and, for the first message, chat creation) is in
   * flight. */
  isLoading: boolean
  /** True while an existing chat's history is being fetched via loadChat. */
  isLoadingChat: boolean
  error: string | null
  /** The provisional answer this view waits for while the model writes it - unvalidated, not to be
   * copied or exported - or null. The final answer replaces it in `messages`. */
  streamingAnswer: string | null
  /** A question refused while another chat was shown, to be put back into the input of this chat;
   * cleared by `clearReturnedQuestion` once the input has handled it. */
  returnedQuestion: RefusedQuestion | null
  /** The chip bar's state (backend default: 'all'). */
  scope: SearchScope
  // Sticky per-chat @-references, meaningful only while scope === 'libraries'.
  // Persisted via PATCH /api/v1/chats/{chatId} once a chat exists (see setScopeAll/
  // addReferencedLibrary/removeReferencedLibrary); before that, they only shape the first
  // message's implicit chat creation.
  referencedLibraryIds: string[]
  /** The chat's sticky core-field filter, null without one. Persisted like the scope via
   * PATCH once a chat exists; before that it shapes the first message's implicit chat creation. */
  metadataFilter: MetadataFilter | null
  /** The chat's Gesprächsnotiz, oldest point first, already filtered by the removals the
   * server has not confirmed yet - empty for a chat without one. */
  noteItems: ChatNoteItem[]
  /** When the person moved the active chat into their chat archive; null while it is active. */
  archivedAt: string | null
  /** When the automatic chat cleanup of the space deletes the archived chat; null otherwise. */
  deletionDueAt: string | null
  /** The in-flight PATCH (if any) from the most recently *started* setScopeAll/
   * addReferencedLibrary/removeReferencedLibrary call across all chats - never rejects (failures
   * are caught and turned into `error` + a local rollback). Exposed for tests/UI only; sendMessage
   * itself awaits the current chat's own settingsUpdateChains entry, not this global slot, since a
   * fast settings change on a *different* chat can already have cleared this back to null while
   * the active chat's own chain is still running. */
  pendingSettingsUpdate: Promise<void> | null
  loadChat: (chatId: string) => Promise<void>
  startNewChat: (spaceId: string) => void
  /**
   * `usedPrompt` names the prompt the question was built from. A question the server refuses (4xx,
   * including a prompt that is no longer usable) resolves to a {@link RefusedQuestion}: nothing of
   * it stays in the history, and the input gets it back to be corrected and sent again.
   */
  sendMessage: (
    question: string,
    usedPrompt?: { id: string; title: string },
  ) => Promise<RefusedQuestion | void>
  /** Sets the chip bar back to the special @Space-Wissen chip, replacing any concrete chips. */
  setScopeAll: () => void
  /** Adds a concrete library chip. The first concrete chip replaces @Space-Wissen (scope 'all' ->
   * 'libraries'); further chips are added to the existing selection. */
  addReferencedLibrary: (libraryId: string) => void
  /** Removes a concrete library chip. Removing the last one empties the bar (scope -> 'none'),
   * matching "leere Leiste = ohne Wissen". */
  removeReferencedLibrary: (libraryId: string) => void
  /** Removes the @Space-Wissen chip, emptying the bar (scope -> 'none'); the reverse of
   * setScopeAll. Every chip - including @Space-Wissen - is removable. */
  clearScope: () => void
  /** Sets or clears (null / no condition) the chat's core-field filter. */
  setMetadataFilter: (filter: MetadataFilter | null) => void
  /** Removes one point of the chat's Gesprächsnotiz - immediately and without a
   * confirmation step, optimistically with a rollback (and `error`) if the DELETE fails. */
  removeNoteItem: (itemId: string) => Promise<void>
  /** Records a changed archive mark of a chat - a no-op unless that chat is the active one. */
  applyArchivedAt: (
    chatId: string,
    archivedAt: string | null,
    deletionDueAt?: string | null,
  ) => void
  /** Records a renamed chat - a no-op unless that chat is the active one. */
  applyTitle: (chatId: string, title: string | null) => void
  /** Drops the active chat back to its initial, empty state - used on logout so a
   * subsequent sign-in by a different user never briefly sees the previous user's conversation. */
  reset: () => void
  clearReturnedQuestion: () => void
  /** Ends the answer the shown view waits for: the server stops generating, nothing of the turn is
   * kept, and the question goes back to the input. */
  cancelAnswer: () => void
}

export type ChatGet = () => ChatState
export type ChatSet = StoreApi<ChatState>['setState']
