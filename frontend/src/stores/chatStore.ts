import { create } from 'zustand'
import type { ChatMessage } from '../types/chat'
import type {
  ChatDetail,
  ChatMessageResponse,
  ChatUpdateRequest,
  SourceReference,
} from '../types/api'
import { createChat, getChat, sendQuery, updateChat } from '../services/api'
import { useChatListStore } from './chatListStore'

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

function toChatMessage(message: ChatMessageResponse): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    // Mirrors the QueryResponse#sources normalization in types/api.ts: the generated schema
    // leaves indexedAt optional, the frontend's own SourceReference always carries it (null when
    // unknown) - persisted messages go through the same shape as a fresh query response.
    sources: message.sources?.map((source): SourceReference => ({
      ...source,
      indexedAt: source.indexedAt ?? null,
    })),
    timestamp: new Date(message.createdAt),
  }
}

// Monotonically increasing token guarding loadChat against two hazards (#548 review, finding d):
// a slower-arriving response from an earlier loadChat(A) call overwriting a faster one from a
// later loadChat(B), and a synchronous startNewChat() in between being clobbered once the
// in-flight loadChat eventually resolves. Deliberately module-level, not store state - it is
// never read by a component, only compared against itself across async gaps.
let chatLoadSequence = 0

interface ChatState {
  /** The space the active (or about-to-be-created) chat lives in - null before any space is
   * known, e.g. right after login before ChatRedirect has resolved a default space. */
  spaceId: string | null
  /** The persisted chat's id (#525/#527), or null for a not-yet-created chat: the first sent
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
  /** Whether the search scope includes the knowledge base at all (#528, backend default: true). */
  useKnowledge: boolean
  // Sticky per-chat @-references (#523/#528). Persisted via PATCH /api/v1/chats/{chatId} once a
  // chat exists (see setUseKnowledge/addReferencedLibrary/removeReferencedLibrary); before that,
  // they only shape the first message's implicit chat creation.
  referencedLibraryIds: string[]
  /** The in-flight PATCH (if any) from the most recent setUseKnowledge/addReferencedLibrary/
   * removeReferencedLibrary call - never rejects (failures are caught and turned into `error` +
   * a local rollback), so sendMessage can safely await it to avoid racing a PATCH that has not
   * reached the server yet against the query that reads the chat's persisted settings (#548
   * review, finding 3). */
  pendingSettingsUpdate: Promise<void> | null
  loadChat: (chatId: string) => Promise<void>
  startNewChat: (spaceId: string) => void
  sendMessage: (question: string) => Promise<void>
  setUseKnowledge: (useKnowledge: boolean) => void
  addReferencedLibrary: (libraryId: string) => void
  removeReferencedLibrary: (libraryId: string) => void
}

function applyChatDetail(detail: ChatDetail) {
  return {
    spaceId: detail.spaceId,
    chatId: detail.id,
    title: detail.title ?? null,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds ?? [],
    messages: detail.messages.map(toChatMessage),
  }
}

export const useChatStore = create<ChatState>((set, get) => ({
  spaceId: null,
  chatId: null,
  title: null,
  messages: [],
  isLoading: false,
  isLoadingChat: false,
  error: null,
  useKnowledge: true,
  referencedLibraryIds: [],
  pendingSettingsUpdate: null,

  loadChat: async (chatId: string) => {
    const requestId = ++chatLoadSequence
    set({ isLoadingChat: true, error: null })
    try {
      const detail = await getChat(chatId)
      // A newer loadChat/startNewChat call superseded this one while the request was in flight -
      // applying this response now would resurrect a chat the user already navigated away from
      // (#548 review, finding d).
      if (requestId !== chatLoadSequence) return
      set({ ...applyChatDetail(detail), isLoadingChat: false })
    } catch (err) {
      if (requestId !== chatLoadSequence) return
      const message = err instanceof Error ? err.message : 'Chat konnte nicht geladen werden'
      // Also drop the stale chat/space out of state (#548 review, finding 2): leaving the
      // previous chat active after a failed load would silently send the next message to a chat
      // the user is no longer looking at.
      set({
        error: message,
        isLoadingChat: false,
        chatId: null,
        spaceId: null,
        messages: [],
        title: null,
      })
    }
  },

  // A new chat resets the sticky knowledge-scope controls too - they belong to the conversation
  // that gets started here, not to whichever chat was open before. No API call yet: the chat is
  // only persisted once the first message is sent (see sendMessage).
  startNewChat: (spaceId: string) => {
    // Invalidates any loadChat still in flight - otherwise its eventual response could overwrite
    // this synchronous reset (#548 review, finding d).
    chatLoadSequence++
    set({
      spaceId,
      chatId: null,
      title: null,
      messages: [],
      error: null,
      useKnowledge: true,
      referencedLibraryIds: [],
    })
  },

  sendMessage: async (question: string) => {
    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: question,
      timestamp: new Date(),
    }

    set((state) => ({
      messages: [...state.messages, userMessage],
      isLoading: true,
      error: null,
    }))

    try {
      let { chatId } = get()
      const { spaceId, useKnowledge, referencedLibraryIds } = get()
      if (!chatId) {
        if (!spaceId) {
          throw new Error('Kein Space für den neuen Chat ausgewählt')
        }
        const created = await createChat(spaceId, { useKnowledge, referencedLibraryIds })
        chatId = created.id
        set({ chatId })
        // Makes the implicitly created chat show up in its space's chat list immediately,
        // instead of only after a manual reload (#548 review, finding 4).
        useChatListStore.getState().upsertChat(spaceId, {
          id: created.id,
          spaceId: created.spaceId,
          authorId: created.authorId,
          title: created.title,
          useKnowledge: created.useKnowledge,
          referencedLibraryIds: created.referencedLibraryIds,
          status: created.status,
          createdAt: created.createdAt,
          updatedAt: created.updatedAt,
        })
      }

      // A PATCH from setUseKnowledge/addReferencedLibrary/removeReferencedLibrary may still be in
      // flight - awaiting it first avoids racing it against this query, which the backend answers
      // using the chat's persisted settings (#548 review, finding 3).
      const pendingSettingsUpdate = get().pendingSettingsUpdate
      if (pendingSettingsUpdate) {
        await pendingSettingsUpdate
      }

      const response = await sendQuery(question, chatId, useKnowledge, referencedLibraryIds)
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        sources: response.sources,
        answeredWithoutKnowledge: response.metadata.answeredWithoutKnowledge ?? false,
        timestamp: new Date(),
      }
      set((state) => ({
        messages: [...state.messages, assistantMessage],
        isLoading: false,
        chatId: response.chatId,
      }))
      if (spaceId) {
        // Moves the chat to the top of its space's list after every turn, mirroring the backend's
        // own updatedAt bump (#548 review, finding 4).
        useChatListStore.getState().touchChat(spaceId, response.chatId, new Date().toISOString())
      }
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'Ein unerwarteter Fehler ist aufgetreten'
      set({ error: message, isLoading: false })
    }
  },

  setUseKnowledge: (useKnowledge: boolean) => {
    const previous = get().useKnowledge
    set({ useKnowledge })
    persistChatSettings(get, set, { useKnowledge }, { useKnowledge: previous })
  },

  addReferencedLibrary: (libraryId: string) => {
    const previous = get().referencedLibraryIds
    if (previous.includes(libraryId)) return
    const next = [...previous, libraryId]
    set({ referencedLibraryIds: next })
    persistChatSettings(
      get,
      set,
      { referencedLibraryIds: next },
      { referencedLibraryIds: previous },
    )
  },

  removeReferencedLibrary: (libraryId: string) => {
    const previous = get().referencedLibraryIds
    const next = previous.filter((id) => id !== libraryId)
    set({ referencedLibraryIds: next })
    persistChatSettings(
      get,
      set,
      { referencedLibraryIds: next },
      { referencedLibraryIds: previous },
    )
  },
}))

/**
 * Persists a chat-settings change (useKnowledge/referencedLibraryIds) via PATCH, if a chat exists
 * yet, and tracks it as `pendingSettingsUpdate` so sendMessage can await it. On failure, rolls the
 * optimistically-applied local state back to `rollbackState` and surfaces `error` - the server's
 * chat settings otherwise silently diverge from what the UI shows (#548 review, finding 3).
 */
function persistChatSettings(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  patch: ChatUpdateRequest,
  rollbackState: Partial<ChatState>,
): void {
  const { chatId } = get()
  if (!chatId) return

  const promise: Promise<void> = updateChat(chatId, patch).then(
    () => undefined,
    (err: unknown) => {
      const message =
        err instanceof Error ? err.message : 'Änderung konnte nicht gespeichert werden'
      set({ ...rollbackState, error: message })
    },
  )

  set({ pendingSettingsUpdate: promise })
  void promise.finally(() => {
    if (get().pendingSettingsUpdate === promise) {
      set({ pendingSettingsUpdate: null })
    }
  })
}
