import type { ChatMessage } from '../types/chat'
import type { ChatDetail, ChatMessageResponse, SourceReference } from '@/shared/api/types'
import { getChat } from '../api/chatApi'
import type { SearchScope } from './chatSettingsPersistence'
import { normalizeMetadataFilter } from './chatSettingsPersistence'
import { confirmNoteItemRemovals, visibleNoteItems } from './chatNoteRemovals'
import {
  currentChatLoadSequence,
  isViewAwaitingAnswer,
  recordPersistedTurns,
  withOutstandingQuestions,
} from './chatTurnTracking'
import type { ChatState } from './chatState'

export function toChatMessage(message: ChatMessageResponse): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    generatedByAi: message.generatedByAi,
    // Mirrors the QueryResponse#sources normalization in shared/api/types.ts: the generated schema
    // leaves indexedAt optional, the frontend's own SourceReference always carries it (null when
    // unknown) - persisted messages go through the same shape as a fresh query response.
    sources: message.sources?.map((source): SourceReference => ({
      ...source,
      indexedAt: source.indexedAt ?? null,
    })),
    ...(message.usedPromptTitle ? { usedPromptTitle: message.usedPromptTitle } : {}),
    ...(message.privateSourcesInContext ? { privateSourcesInContext: true } : {}),
    timestamp: new Date(message.createdAt),
  }
}

/**
 * Re-reads the active chat's messages, title and note without a spinner - for a view that was
 * (re)loaded while one of its answers was outstanding, whose snapshot may lack that turn. Applied
 * only while the chat is still active and no newer loadChat/startNewChat superseded the read.
 */
export function reloadChatTurns(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  chatId: string,
): void {
  const loadSequence = currentChatLoadSequence()
  getChat(chatId)
    .then((detail) => {
      if (loadSequence !== currentChatLoadSequence() || get().chatId !== chatId) return
      recordPersistedTurns(chatId, detail.messages)
      confirmNoteItemRemovals(chatId, detail.noteItems ?? [])
      set({
        title: detail.title ?? null,
        messages: withOutstandingQuestions(chatId, detail.messages.map(toChatMessage)),
        noteItems: visibleNoteItems(chatId, detail.noteItems ?? []),
      })
    })
    .catch(() => {
      // Best-effort: the view keeps what it shows; the next load of the chat catches up.
    })
}

/** Maps the backend's useKnowledge/referencedLibraryIds pair onto the chip bar's scope. */
function scopeFromChatDetail(useKnowledge: boolean, referencedLibraryIds: string[]): SearchScope {
  if (useKnowledge) return 'all'
  return referencedLibraryIds.length > 0 ? 'libraries' : 'none'
}

// loadChat's settings-race guard below needs every field applyChatDetail returns
// *except* scope/referencedLibraryIds - omitKeys(), not a hand-maintained field list, is what keeps
// that guarded write-back in sync with this function's return type. Adding a field here (e.g. a
// future ChatDetail property) is automatically picked up by that write-back without touching
// loadChat at all; only add a field to omitKeys' call site if the *new* field also needs the same
// settings-race protection as scope/referencedLibraryIds.
export function applyChatDetail(detail: ChatDetail) {
  const referencedLibraryIds = detail.referencedLibraryIds ?? []
  const scope = scopeFromChatDetail(detail.useKnowledge, referencedLibraryIds)
  return {
    spaceId: detail.spaceId,
    chatId: detail.id,
    title: detail.title ?? null,
    scope,
    // Only 'libraries' actually uses these ids as the search scope - dropping them for 'all'/
    // 'none' keeps the chip bar an exact mirror of what the server applies.
    referencedLibraryIds: scope === 'libraries' ? referencedLibraryIds : [],
    metadataFilter: normalizeMetadataFilter(detail.metadataFilter),
    messages: withOutstandingQuestions(detail.id, detail.messages.map(toChatMessage)),
    noteItems: visibleNoteItems(detail.id, detail.noteItems ?? []),
    archivedAt: detail.archivedAt ?? null,
    deletionDueAt: detail.deletionDueAt ?? null,
    isLoading: isViewAwaitingAnswer(detail.id),
  }
}

/** Shallow-omits `keys` from `obj`, typed so the result is exactly `Omit<T, K>`. Used
 * instead of a hand-picked field list so a write-back that means "everything but a few guarded
 * fields" stays correct as the source object's shape evolves, rather than silently dropping new
 * fields until someone remembers to update a parallel list. */
export function omitKeys<T extends object, K extends keyof T>(
  obj: T,
  keys: readonly K[],
): Omit<T, K> {
  const clone: Partial<T> = { ...obj }
  keys.forEach((key) => {
    delete clone[key]
  })
  return clone as Omit<T, K>
}
