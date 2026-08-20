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

// Monotonically increasing token guarding applyScopeChange's PATCH failure handler against two
// hazards found in review of #548/#564 (#565): a settings PATCH for chat A that is still in
// flight when the user navigates to chat B must not roll chat B's state back on failure, and two
// rapid scope changes on the same chat (e.g. fast chip clicks) must let the *last requested*
// change win, not whichever PATCH response happens to arrive last over the network. Same pattern
// as chatLoadSequence above - module-level, never read by a component.
let settingsUpdateSequence = 0

// The chip bar is the only search-scope control (#560): 'all' shows the special @Alles-Wissen
// chip (backend useKnowledge=true), 'libraries' shows the sticky concrete-library chips
// (useKnowledge=false + referencedLibraryIds), 'none' is an emptied bar (useKnowledge=false, no
// ids) - "Durchsucht wird, was in der Leiste steht." @Space (space-associated libraries) is
// intentionally not a fourth state yet - it lands with #203.
export type SearchScope = 'all' | 'libraries' | 'none'

// #557: the backend generates an LLM title asynchronously, after the answer is already returned
// (see QueryResponse#chatTitle's Javadoc) - it is never present on the very turn that triggers it.
// This is the frontend half of "Zuschnitt frei: nachgeladen": a single delayed reload of the chat
// after a first turn's answer arrives, giving the backend's async generation a realistic window to
// finish. Best-effort only - if it is not done yet, or the reload fails, the fallback title already
// shown (from QueryResponse#chatTitle) simply stays.
const TITLE_RELOAD_DELAY_MS = 2500

function scheduleTitleReload(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  chatId: string,
  spaceId: string | null,
): void {
  setTimeout(() => {
    // The user may have navigated to a different chat by the time this fires - applying a reload
    // for a chat that is no longer active would silently resurrect stale state.
    if (get().chatId !== chatId) return
    getChat(chatId)
      .then((detail) => {
        if (get().chatId !== chatId) return
        set({ title: detail.title ?? null })
        if (spaceId) {
          useChatListStore.getState().updateChatTitle(spaceId, chatId, detail.title ?? null)
        }
      })
      .catch(() => {
        // Best-effort refresh only - the fallback title already shown is left as is.
      })
  }, TITLE_RELOAD_DELAY_MS)
}

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
  /** The chip bar's state (#560, backend default: 'all'). */
  scope: SearchScope
  // Sticky per-chat @-references (#523/#528/#560), meaningful only while scope === 'libraries'.
  // Persisted via PATCH /api/v1/chats/{chatId} once a chat exists (see setScopeAll/
  // addReferencedLibrary/removeReferencedLibrary); before that, they only shape the first
  // message's implicit chat creation.
  referencedLibraryIds: string[]
  /** The in-flight PATCH (if any) from the most recent setScopeAll/addReferencedLibrary/
   * removeReferencedLibrary call - never rejects (failures are caught and turned into `error` +
   * a local rollback), so sendMessage can safely await it to avoid racing a PATCH that has not
   * reached the server yet against the query that reads the chat's persisted settings (#548
   * review, finding 3). */
  pendingSettingsUpdate: Promise<void> | null
  loadChat: (chatId: string) => Promise<void>
  startNewChat: (spaceId: string) => void
  sendMessage: (question: string) => Promise<void>
  /** Sets the chip bar back to the special @Alles-Wissen chip, replacing any concrete chips. */
  setScopeAll: () => void
  /** Adds a concrete library chip. The first concrete chip replaces @Alles-Wissen (scope 'all' ->
   * 'libraries'); further chips are added to the existing selection. */
  addReferencedLibrary: (libraryId: string) => void
  /** Removes a concrete library chip. Removing the last one empties the bar (scope -> 'none'),
   * matching "leere Leiste = ohne Wissen". */
  removeReferencedLibrary: (libraryId: string) => void
  /** Removes the @Alles-Wissen chip, emptying the bar (scope -> 'none'); the reverse of
   * setScopeAll. Every chip - including @Alles-Wissen - is removable (#560). */
  clearScope: () => void
}

/** Maps the backend's useKnowledge/referencedLibraryIds pair onto the chip bar's scope. */
function scopeFromChatDetail(useKnowledge: boolean, referencedLibraryIds: string[]): SearchScope {
  if (useKnowledge) return 'all'
  return referencedLibraryIds.length > 0 ? 'libraries' : 'none'
}

function applyChatDetail(detail: ChatDetail) {
  const referencedLibraryIds = detail.referencedLibraryIds ?? []
  const scope = scopeFromChatDetail(detail.useKnowledge, referencedLibraryIds)
  return {
    spaceId: detail.spaceId,
    chatId: detail.id,
    title: detail.title ?? null,
    scope,
    // Only 'libraries' actually uses these ids as the search scope - dropping them for 'all'/
    // 'none' keeps the chip bar an exact mirror of what the server applies (#560).
    referencedLibraryIds: scope === 'libraries' ? referencedLibraryIds : [],
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
  scope: 'all',
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
    // this synchronous reset (#548 review, finding d). The superseded loadChat handler then
    // returns early (its requestId no longer matches chatLoadSequence) without ever reaching its
    // own set() call, so isLoadingChat must be cleared here too - otherwise ChatPage's spinner
    // never clears and the chat input never reappears (#559).
    chatLoadSequence++
    set({
      spaceId,
      chatId: null,
      title: null,
      messages: [],
      error: null,
      scope: 'all',
      referencedLibraryIds: [],
      isLoadingChat: false,
    })
  },

  sendMessage: async (question: string) => {
    // #557: whether this is the chat's first-ever turn - captured before the optimistic user
    // message below is pushed, since that would make messages.length always >= 1. Only a first
    // turn triggers the backend's asynchronous LLM title generation, so only a first turn
    // schedules the delayed reload that picks it up.
    const isFirstTurn = get().messages.length === 0

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
      const { spaceId, scope, referencedLibraryIds } = get()
      const useKnowledge = scope === 'all'
      // Only 'libraries' actually names a scope - 'none' sends an empty array, matching what the
      // chip bar shows (#560).
      const libraryIds = scope === 'libraries' ? referencedLibraryIds : []
      if (!chatId) {
        if (!spaceId) {
          throw new Error('Kein Space für den neuen Chat ausgewählt')
        }
        const created = await createChat(spaceId, {
          useKnowledge,
          referencedLibraryIds: libraryIds,
        })
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

      // A PATCH from setScopeAll/addReferencedLibrary/removeReferencedLibrary may still be in
      // flight - awaiting it first avoids racing it against this query, which the backend answers
      // using the chat's persisted settings (#548 review, finding 3).
      const pendingSettingsUpdate = get().pendingSettingsUpdate
      if (pendingSettingsUpdate) {
        await pendingSettingsUpdate
      }

      const response = await sendQuery(question, chatId, useKnowledge, libraryIds)
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
        // #557: the chat's current title right after this turn - still the mechanical prefix
        // fallback on a first turn, see scheduleTitleReload above for how the LLM-derived title
        // eventually replaces it.
        title: response.chatTitle ?? state.title,
      }))
      if (spaceId) {
        // Moves the chat to the top of its space's list after every turn, mirroring the backend's
        // own updatedAt bump (#548 review, finding 4).
        useChatListStore.getState().touchChat(spaceId, response.chatId, new Date().toISOString())
        if (response.chatTitle) {
          useChatListStore.getState().updateChatTitle(spaceId, response.chatId, response.chatTitle)
        }
        if (isFirstTurn) {
          scheduleTitleReload(get, set, response.chatId, spaceId)
        }
      }
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'Ein unerwarteter Fehler ist aufgetreten'
      set({ error: message, isLoading: false })
    }
  },

  setScopeAll: () => {
    // Already showing @Alles-Wissen - nothing to replace. Short-circuiting here avoids a PATCH
    // that would just re-send the chat's current settings (#564 review).
    if (get().scope === 'all') return
    // Re-adding @Alles-Wissen replaces any concrete chips (#560) - the two are mutually
    // exclusive states of the same bar, never shown together.
    applyScopeChange(get, set, 'all', [])
  },

  addReferencedLibrary: (libraryId: string) => {
    const { scope, referencedLibraryIds } = get()
    // The first concrete chip replaces @Alles-Wissen; from 'libraries' or 'none' it simply
    // extends/starts the selection (#560).
    const previousIds = scope === 'libraries' ? referencedLibraryIds : []
    if (previousIds.includes(libraryId)) return
    applyScopeChange(get, set, 'libraries', [...previousIds, libraryId])
  },

  removeReferencedLibrary: (libraryId: string) => {
    const next = get().referencedLibraryIds.filter((id) => id !== libraryId)
    // Removing the last concrete chip empties the bar rather than falling back to @Alles-Wissen -
    // "leere Leiste = ohne Wissen" (#560), with an explicit one-click way back via setScopeAll.
    applyScopeChange(get, set, next.length > 0 ? 'libraries' : 'none', next)
  },

  clearScope: () => {
    // Already empty - same reasoning as setScopeAll's short-circuit above.
    if (get().scope === 'none') return
    applyScopeChange(get, set, 'none', [])
  },
}))

/**
 * Applies a chip-bar scope change locally and persists it via PATCH, if a chat exists yet, and
 * tracks it as `pendingSettingsUpdate` so sendMessage can await it. On failure, rolls the
 * optimistically-applied local state back and surfaces `error` - the server's chat settings
 * otherwise silently diverge from what the chip bar shows (#548 review, finding 3; carried over to
 * the chip-only model in #560). useKnowledge and referencedLibraryIds are always sent together,
 * even when only one conceptually changed, because a scope change - e.g. the first concrete chip
 * replacing @Alles-Wissen - flips both fields atomically; splitting them into separate PATCHes
 * could let a chat briefly sit with useKnowledge=true and stale referencedLibraryIds server-side.
 */
function applyScopeChange(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  nextScope: SearchScope,
  nextReferencedLibraryIds: string[],
): void {
  // Captured before the optimistic set() below: the chat this change applies to, and this call's
  // token in the settings-update sequence (#565). Both are checked in the failure handler below,
  // once the PATCH's response - possibly stale - actually arrives.
  const requestChatId = get().chatId
  const requestId = ++settingsUpdateSequence

  const rollbackState: Partial<ChatState> = {
    scope: get().scope,
    referencedLibraryIds: get().referencedLibraryIds,
  }
  set({ scope: nextScope, referencedLibraryIds: nextReferencedLibraryIds })

  const { chatId } = get()
  if (!chatId) return

  const patch: ChatUpdateRequest = {
    useKnowledge: nextScope === 'all',
    referencedLibraryIds: nextScope === 'libraries' ? nextReferencedLibraryIds : [],
  }

  const promise: Promise<void> = updateChat(chatId, patch).then(
    () => undefined,
    (err: unknown) => {
      // A stale failure must not roll back a chat the user has since navigated away from (#565) -
      // without this guard, a late-arriving PATCH failure for chat A silently resurrects chat A's
      // pre-change state on top of chat B, which is now active.
      if (get().chatId !== requestChatId) return
      // Nor may it roll back over a *newer* change to the same chat that has already applied its
      // own optimistic state (or even already succeeded) - only the most recently requested change
      // may still roll back on failure, so the last action wins rather than the last response
      // (#565).
      if (requestId !== settingsUpdateSequence) return
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
