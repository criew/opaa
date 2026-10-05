import { create } from 'zustand'
import type { ChatMessage } from '../types/chat'
import type {
  ChatDetail,
  ChatMessageResponse,
  ChatNoteItem,
  MetadataFilter,
  SourceReference,
} from '../types/api'
import { createChat, deleteChatNoteItem, getChat } from '../services/chatApi'
import { sendQuery } from '../services/queryApi'
import { useChatListStore } from './chatListStore'
import { notify } from './notificationStore'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'
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
import type { SearchScope } from './chatSettingsPersistence'

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
    ...(message.usedPromptTitle ? { usedPromptTitle: message.usedPromptTitle } : {}),
    ...(message.privateSourcesInContext ? { privateSourcesInContext: true } : {}),
    timestamp: new Date(message.createdAt),
  }
}

/** The longest question the server accepts (`QueryRequest.question`, `maxLength`). */
export const QUESTION_MAX_LENGTH = 2000

const INVALID_QUESTION_MESSAGE = `Die Frage konnte nicht gesendet werden. Bitte prüfen Sie die Eingabe (höchstens ${QUESTION_MAX_LENGTH} Zeichen).`

const DRAFT_RESTORED_NOTE = 'Die Frage steht wieder im Eingabefeld.'

const DRAFT_RESTORED_WITHOUT_PROMPT_NOTE =
  'Die Frage steht wieder im Eingabefeld und lässt sich ohne Prompt senden.'

/** The server's bean validation names the rejected field first, e.g. `question: …`. */
function isQuestionViolation(message: string): boolean {
  return message.startsWith('question:')
}

/**
 * What `sendMessage` resolves to for a question the server refused: the draft for the input, and
 * `onRestored`, which the input calls once the draft is actually back in it.
 */
export interface RefusedQuestion {
  restoreDraft: string
  onRestored: () => void
}

/** A refused question whose chat was not shown when the refusal arrived; keyed by chat. */
interface ParkedRefusal {
  question: string
  reason: string
  note: string
}

/** The HTTP status of a failed request, or `null` when no response arrived. */
function responseStatus(err: unknown): number | null {
  const cause = err instanceof Error ? err.cause : undefined
  const status = (cause as { response?: { status?: unknown } } | undefined)?.response?.status
  return typeof status === 'number' ? status : null
}

/** The server refused the question because the person may no longer use its prompt. */
function isPromptNotUsable(err: unknown): boolean {
  const cause = err instanceof Error ? err.cause : undefined
  const data = (cause as { response?: { data?: { code?: unknown } } } | undefined)?.response?.data
  return data?.code === 'PROMPT_NOT_USABLE'
}

// Monotonically increasing token guarding loadChat against two hazards (#548 review, finding d):
// a slower-arriving response from an earlier loadChat(A) call overwriting a faster one from a
// later loadChat(B), and a synchronous startNewChat() in between being clobbered once the
// in-flight loadChat eventually resolves. Deliberately module-level, not store state - it is
// never read by a component, only compared against itself across async gaps.
let chatLoadSequence = 0

/** A question whose answer is still outstanding. `chatId` is the chat it is answered in - null
 * until its implicit creation finished; `loadSequence` identifies the not-yet-created chat view it
 * was sent from until then. */
interface InFlightSend {
  chatId: string | null
  loadSequence: number
  userMessage: ChatMessage
  /** The chat's user messages known to be persisted when the question was sent. */
  persistedUserTurnsBefore: number
  /** Set once an applied server read of the chat already contained this question's turn. */
  persisted: boolean
}

// Every question still waiting for its answer, across chats - the source of each view's isLoading
// and of the questions a (re)loaded chat shows before the server has persisted them. Module state
// like chatLoadSequence; reset() clears it.
const inFlightSends = new Set<InFlightSend>()

// Per chat, the number of user messages the server is known to hold - the baseline a question
// records when it is sent. Module state like inFlightSends; reset() clears it.
const persistedUserTurnsByChatId = new Map<string, number>()

// Questions the server refused while another chat was shown; handed back when the person returns.
const parkedRefusalsByChatId = new Map<string, ParkedRefusal>()
// Same for a question whose chat creation was refused: no chat id exists yet, so it is keyed by
// the space whose new-chat view it was sent from.
const parkedRefusalsByNewChatSpaceId = new Map<string, ParkedRefusal>()

/** Parks a refusal; a second one for the same key is appended so neither question is lost. */
function parkRefused(map: Map<string, ParkedRefusal>, key: string, refusal: ParkedRefusal): void {
  const earlier = map.get(key)
  map.set(
    key,
    earlier
      ? {
          ...refusal,
          question: `${earlier.question}\n\n${refusal.question}`,
        }
      : refusal,
  )
}

/** The refusal as the input takes it back; `stillShown` guards the confirming note. */
function parkedAsReturnedQuestion(
  parked: ParkedRefusal,
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  stillShown: () => boolean,
): RefusedQuestion {
  return {
    restoreDraft: parked.question,
    onRestored: () => {
      if (stillShown() && get().error === parked.reason) {
        set({ error: `${parked.reason} ${parked.note}` })
      }
    },
  }
}

/**
 * Takes note of a server read of `chatId` that is about to be applied. An outstanding question
 * counts as contained once a user message with its text follows the user messages persisted when
 * it was sent - the position tells a repeated question from the earlier one. A contained question
 * is neither appended again nor keeps the view loading; its answer then re-reads the chat.
 */
function recordPersistedTurns(chatId: string, serverMessages: ChatMessageResponse[]): void {
  const userContents = serverMessages
    .filter((message) => message.role === 'USER')
    .map((message) => message.content)
  for (const send of inFlightSends) {
    if (send.chatId !== chatId || send.persisted) continue
    send.persisted = userContents
      .slice(send.persistedUserTurnsBefore)
      .includes(send.userMessage.content)
  }
  persistedUserTurnsByChatId.set(chatId, userContents.length)
}

// Bumped whenever an answer arrives. loadChat compares it across its GET: an answer that arrived in
// between may have been persisted after the GET read the chat, so the chat is read again.
let completedTurnSequence = 0

/** Whether the view showing `chatId` - or, for null, the current not-yet-created chat - waits for
 * an answer. */
function isViewAwaitingAnswer(chatId: string | null): boolean {
  for (const send of inFlightSends) {
    if (chatId !== null ? send.chatId === chatId && !send.persisted : isNewChatViewOf(send)) {
      return true
    }
  }
  return false
}

function isNewChatViewOf(send: InFlightSend): boolean {
  return send.chatId === null && send.loadSequence === chatLoadSequence
}

/** The server's messages plus the questions still outstanding for this chat that the read did not
 * contain yet - the server only persists a question together with its answer. */
function withOutstandingQuestions(chatId: string, messages: ChatMessage[]): ChatMessage[] {
  const outstanding = [...inFlightSends]
    .filter((send) => send.chatId === chatId && !send.persisted)
    .map((send) => send.userMessage)
  return outstanding.length > 0 ? [...messages, ...outstanding] : messages
}

// Per chat, the ids of Gesprächsnotiz points the person removed locally whose removal GET chat has
// not yet confirmed (#1488). Every note state the server delivers for a chat is filtered through
// that chat's entry, because an answer carries the note state that went into *it* - an answer that
// was already in flight when the point was removed still contains it, and applying it unfiltered
// would make a removed point reappear. Keyed by chat id, like the settings maps in chatSettingsPersistence.ts: loading
// *another* chat says nothing about this chat's server state, so it must not release anything here.
const removedNoteItemIdsByChatId = new Map<string, Set<string>>()

/** Releases the ids of `chatId` that the server no longer reports - that is the confirmation the
 * filter waits for. Only this chat's entry is touched; removals pending for another chat stay
 * pending until that chat is loaded. */
function confirmNoteItemRemovals(chatId: string, serverItems: ChatNoteItem[]): void {
  const pending = removedNoteItemIdsByChatId.get(chatId)
  if (!pending) return
  const stillPresent = new Set(serverItems.map((item) => item.id))
  pending.forEach((id) => {
    if (!stillPresent.has(id)) pending.delete(id)
  })
  if (pending.size === 0) removedNoteItemIdsByChatId.delete(chatId)
}

function visibleNoteItems(chatId: string, serverItems: ChatNoteItem[]): ChatNoteItem[] {
  const pending = removedNoteItemIdsByChatId.get(chatId)
  if (!pending) return serverItems
  return serverItems.filter((item) => !pending.has(item.id))
}

/** Clears the module-level map of not-yet-confirmed note removals - used by the store's own reset()
 * (logout) and exported for chatStore.test.ts's beforeEach, since module state survives across
 * test cases unless cleared explicitly. */
export function clearRemovedNoteItemCache(): void {
  removedNoteItemIdsByChatId.clear()
  noteRemovalError = null
}

// The message the last failed note removal showed. A new removal attempt clears the store's error
// only while it is still this one - an error from another action (sending, saving the chat's
// settings) stays until that action deals with it.
let noteRemovalError: string | null = null

/**
 * Drops chatId's entries from both module-level settings-persistence maps (#573): a deleted chat
 * can never again be the target of a queued PATCH or a rollback base, so leaving its entries
 * behind would just grow both maps for the rest of the session. settingsUpdateChains is always
 * dropped immediately - a later sendMessage for this (deleted) chat must not queue/await behind a
 * chain that will never again matter. confirmedSettingsByChatId is more delicate (#573 review,
 * second round): if a PATCH for this chat is still in flight at the moment of deletion, dropping
 * it right away would rip out the rollback/confirmation base that PATCH's own still-running
 * success/failure handler may need (see the warning on confirmedSettingsByChatId's declaration
 * above) - e.g. the user deleted the chat they were currently viewing without navigating away
 * first, and its own pending settings change then fails. `pendingChain` - this chat's most
 * recently queued call at the time of deletion - is chained behind every earlier one for the same
 * chat, so attaching the drop to its own settlement is safe: by the time it fires, every settings
 * PATCH still outstanding for this chat has settled too.
 */
export function dropChatSettingsCache(chatId: string): void {
  const pendingChain = settingsUpdateChains.get(chatId)
  settingsUpdateChains.delete(chatId)
  settingsChangeSequenceByChatId.delete(chatId)
  // #1488: a deleted chat is never loaded again, so its pending note removals would never be
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

// #557: the backend generates an LLM title asynchronously, after the answer is already returned
// (see QueryResponse#chatTitle's Javadoc) - it is never present on the very turn that triggers it.
// This is the frontend half of "Zuschnitt frei: nachgeladen": a single delayed reload of the chat
// after a first turn's answer arrives, giving the backend's async generation a realistic window to
// finish. Best-effort only - if it is not done yet, or the reload fails, the fallback title already
// shown (from QueryResponse#chatTitle) simply stays.
const TITLE_RELOAD_DELAY_MS = 2500

// #1919: the server never overwrites a manually set title (ChatTitleGenerationService writes only
// while titleSource is GENERATED), but the delayed reload below would still *display* the
// generated one if the rename happened inside its window - and its PATCH was still in flight when
// the reload read the chat. A chat renamed by hand is therefore excluded from that reload for the
// rest of the session; every rename path goes through chatListStore#renameChat, which marks it.
const manuallyRenamedChatIds = new Set<string>()

export function markChatManuallyRenamed(chatId: string): void {
  manuallyRenamedChatIds.add(chatId)
}

/** Test seam: the marks are module state and would otherwise leak between test cases. */
export function clearManualRenameMarks(): void {
  manuallyRenamedChatIds.clear()
}

function scheduleTitleReload(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  chatId: string,
  spaceId: string | null,
): void {
  setTimeout(() => {
    // The user may have navigated to a different chat by the time this fires - applying a reload
    // for a chat that is no longer active would silently resurrect stale state.
    if (get().chatId !== chatId || manuallyRenamedChatIds.has(chatId)) return
    getChat(chatId)
      .then((detail) => {
        if (get().chatId !== chatId || manuallyRenamedChatIds.has(chatId)) return
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

/**
 * Re-reads the active chat's messages, title and note without a spinner - for a view that was
 * (re)loaded while one of its answers was outstanding, whose snapshot may lack that turn. Applied
 * only while the chat is still active and no newer loadChat/startNewChat superseded the read.
 */
function reloadChatTurns(
  get: () => ChatState,
  set: (partial: Partial<ChatState>) => void,
  chatId: string,
): void {
  const loadSequence = chatLoadSequence
  getChat(chatId)
    .then((detail) => {
      if (loadSequence !== chatLoadSequence || get().chatId !== chatId) return
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

export interface ChatState {
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
  /** A question refused while another chat was shown, to be put back into the input of this chat;
   * cleared by `clearReturnedQuestion` once the input has handled it. */
  returnedQuestion: RefusedQuestion | null
  /** The chip bar's state (#560, backend default: 'all'). */
  scope: SearchScope
  // Sticky per-chat @-references (#523/#528/#560), meaningful only while scope === 'libraries'.
  // Persisted via PATCH /api/v1/chats/{chatId} once a chat exists (see setScopeAll/
  // addReferencedLibrary/removeReferencedLibrary); before that, they only shape the first
  // message's implicit chat creation.
  referencedLibraryIds: string[]
  /** The chat's sticky core-field filter (#1070), null without one. Persisted like the scope via
   * PATCH once a chat exists; before that it shapes the first message's implicit chat creation. */
  metadataFilter: MetadataFilter | null
  /** The chat's Gesprächsnotiz (#1488), oldest point first, already filtered by the removals the
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
   * the active chat's own chain is still running (#570 review, second round). */
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
   * setScopeAll. Every chip - including @Space-Wissen - is removable (#560). */
  clearScope: () => void
  /** Sets or clears (null / no condition) the chat's core-field filter (#1070). */
  setMetadataFilter: (filter: MetadataFilter | null) => void
  /** Removes one point of the chat's Gesprächsnotiz (#1488) - immediately and without a
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
  /** Drops the active chat back to its initial, empty state (#440) - used on logout so a
   * subsequent sign-in by a different user never briefly sees the previous user's conversation. */
  reset: () => void
  clearReturnedQuestion: () => void
}

/** Maps the backend's useKnowledge/referencedLibraryIds pair onto the chip bar's scope. */
function scopeFromChatDetail(useKnowledge: boolean, referencedLibraryIds: string[]): SearchScope {
  if (useKnowledge) return 'all'
  return referencedLibraryIds.length > 0 ? 'libraries' : 'none'
}

// #619 review: loadChat's settings-race guard below needs every field applyChatDetail returns
// *except* scope/referencedLibraryIds - omitKeys(), not a hand-maintained field list, is what keeps
// that guarded write-back in sync with this function's return type. Adding a field here (e.g. a
// future ChatDetail property) is automatically picked up by that write-back without touching
// loadChat at all; only add a field to omitKeys' call site if the *new* field also needs the same
// settings-race protection as scope/referencedLibraryIds.
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
    metadataFilter: normalizeMetadataFilter(detail.metadataFilter),
    messages: withOutstandingQuestions(detail.id, detail.messages.map(toChatMessage)),
    noteItems: visibleNoteItems(detail.id, detail.noteItems ?? []),
    archivedAt: detail.archivedAt ?? null,
    deletionDueAt: detail.deletionDueAt ?? null,
    isLoading: isViewAwaitingAnswer(detail.id),
  }
}

/** Shallow-omits `keys` from `obj`, typed so the result is exactly `Omit<T, K>` (#619 review). Used
 * instead of a hand-picked field list so a write-back that means "everything but a few guarded
 * fields" stays correct as the source object's shape evolves, rather than silently dropping new
 * fields until someone remembers to update a parallel list. */
function omitKeys<T extends object, K extends keyof T>(obj: T, keys: readonly K[]): Omit<T, K> {
  const clone: Partial<T> = { ...obj }
  keys.forEach((key) => {
    delete clone[key]
  })
  return clone as Omit<T, K>
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
    const requestId = ++chatLoadSequence
    // #619: this chat's settings-change counter, captured before the GET below is sent. Compared
    // again once the response arrives - see settingsChangeSequenceByChatId's declaration for
    // why a per-chat counter, rather than settingsUpdateChains, is needed to catch this ordering.
    const settingsSequenceAtStart = settingsChangeSequenceByChatId.get(chatId) ?? 0
    const completedTurnsAtStart = completedTurnSequence
    set({ isLoadingChat: true, error: null, returnedQuestion: null })
    try {
      const detail = await getChat(chatId)
      // A newer loadChat/startNewChat call superseded this one while the request was in flight -
      // applying this response now would resurrect a chat the user already navigated away from
      // (#548 review, finding d).
      if (requestId !== chatLoadSequence) return
      recordPersistedTurns(chatId, detail.messages)
      // #1488: loading the chat is what confirms a local removal - a point the server no longer
      // reports leaves the filter set, a point it still reports keeps being filtered out.
      confirmNoteItemRemovals(chatId, detail.noteItems ?? [])
      const detailState = applyChatDetail(detail)
      // #619: a settings change for this exact chat was started while this GET was in flight - its
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
        // failure while this chat stays active (#565 review).
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
      if (completedTurnSequence !== completedTurnsAtStart) reloadChatTurns(get, set, chatId)
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

  sendMessage: async (question: string, usedPrompt?: { id: string; title: string }) => {
    // #575: this call's token in the session epoch, captured before any await below. Checked
    // again before every set() that follows an await - a logout (resetAllStores) in the meantime
    // bumps the epoch, so a response arriving afterwards is recognized as stale and its write-back
    // is skipped instead of resurrecting the previous user's chatId/messages into the now-emptied
    // store (#618 review: this applies to both the implicit-chat-creation write-back and the final
    // answer write-back below).
    const sessionEpoch = currentSessionEpoch()

    // #557: whether this is the chat's first-ever turn - captured before the optimistic user
    // message below is pushed, since that would make messages.length always >= 1. Only a first
    // turn triggers the backend's asynchronous LLM title generation, so only a first turn
    // schedules the delayed reload that picks it up.
    const isFirstTurn = get().messages.length === 0

    // The chat this question is answered in, compared again after every await like
    // removeNoteItem's rollback compares its chatId: the chat active when it was sent, or - for a
    // not-yet-created chat - the one its implicit creation produced. Until that id exists, the
    // not-yet-created chat view is identified by chatLoadSequence, which every loadChat/startNewChat
    // bumps.
    const sendingChatId = get().chatId
    const sendingSpaceId = get().spaceId
    const archivedWhenSent = sendingChatId !== null && get().archivedAt !== null
    const send: InFlightSend = {
      chatId: sendingChatId,
      loadSequence: chatLoadSequence,
      userMessage: {
        id: generateId(),
        role: 'user',
        content: question,
        ...(usedPrompt ? { usedPromptTitle: usedPrompt.title } : {}),
        timestamp: new Date(),
      },
      persistedUserTurnsBefore: sendingChatId
        ? (persistedUserTurnsByChatId.get(sendingChatId) ?? 0)
        : 0,
      persisted: false,
    }
    // The person looks at this question's chat, possibly reloaded since it was sent.
    const isTargetChatShown = () =>
      send.chatId !== null
        ? get().chatId === send.chatId
        : get().chatId === null && isNewChatViewOf(send)
    // The person still looks at the very view the question was sent from.
    const isSendingViewShown = () => isTargetChatShown() && chatLoadSequence === send.loadSequence

    inFlightSends.add(send)
    set((state) => ({
      messages: [...state.messages, send.userMessage],
      isLoading: true,
      error: null,
    }))

    // A 4xx means the question was not persisted: it is handed back instead of lost.
    const classifyRefusal = (failure: unknown): { reason: string; note: string } | null => {
      const status = responseStatus(failure)
      if (status === null || status < 400 || status >= 500) return null
      const message =
        failure instanceof Error ? failure.message : 'Ein unerwarteter Fehler ist aufgetreten'
      const promptNotUsable = usedPrompt !== undefined && isPromptNotUsable(failure)
      return {
        reason: status === 400 && isQuestionViolation(message) ? INVALID_QUESTION_MESSAGE : message,
        note: promptNotUsable ? DRAFT_RESTORED_WITHOUT_PROMPT_NOTE : DRAFT_RESTORED_NOTE,
      }
    }
    const parkRefusal = (failure: unknown) => {
      const refusal = classifyRefusal(failure)
      if (!refusal) return
      const parked = { question, ...refusal }
      if (send.chatId) parkRefused(parkedRefusalsByChatId, send.chatId, parked)
      else if (sendingSpaceId) parkRefused(parkedRefusalsByNewChatSpaceId, sendingSpaceId, parked)
    }

    try {
      const { spaceId, scope, referencedLibraryIds, metadataFilter } = get()
      const useKnowledge = scope === 'all'
      // Only 'libraries' actually names a scope - 'none' sends an empty array, matching what the
      // chip bar shows (#560).
      const libraryIds = scope === 'libraries' ? referencedLibraryIds : []
      if (!send.chatId) {
        if (!spaceId) {
          throw new Error('Kein Space für den neuen Chat ausgewählt')
        }
        const created = await createChat(spaceId, {
          useKnowledge,
          referencedLibraryIds: libraryIds,
          ...(metadataFilter ? { metadataFilter } : {}),
        })
        // #575: a logout in between (e.g. a 401 elsewhere triggering authStore.logout()) must not
        // let this chat's id resurrect into the now-emptied store.
        if (isStaleSessionEpoch(sessionEpoch)) return
        // The question is sent to the created chat either way; only the view it was sent from
        // becomes that chat.
        const viewTakesChat = isSendingViewShown()
        send.chatId = created.id
        if (viewTakesChat) set({ chatId: created.id })
        // The settings this chat was just created with are the server's own record too (#565
        // review) - same reasoning as loadChat above.
        confirmedSettingsByChatId.set(created.id, {
          scope,
          referencedLibraryIds: libraryIds,
          metadataFilter,
        })
        // Makes the implicitly created chat show up in its space's chat list immediately,
        // instead of only after a manual reload.
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
      const chatId = send.chatId

      // A PATCH from setScopeAll/addReferencedLibrary/removeReferencedLibrary may still be in
      // flight for *this* chat - awaiting it first avoids racing it against this query, which the
      // backend answers using the chat's persisted settings (#548 review, finding 3). Reading
      // settingsUpdateChains by chatId here, not the global pendingSettingsUpdate slot (#570
      // review, second round): the slot only ever reflects the most recently *started* settings
      // change across all chats - a fast PATCH on another chat can already have cleared it back to
      // null while this chat's own chain is still running (e.g. slow change on chat A, switch to
      // chat B, fast change on B, switch back to A - pendingSettingsUpdate would be null even
      // though A's chain has not settled yet).
      const pendingChainForChat = settingsUpdateChains.get(chatId)
      if (pendingChainForChat) {
        await pendingChainForChat
      }

      const response = await sendQuery(
        question,
        chatId,
        useKnowledge,
        libraryIds,
        metadataFilter,
        usedPrompt?.id,
      )
      // #575: the query answer arriving after a logout must not resurrect messages/chatId into the
      // now-emptied store - this is the second of the two write-back paths the #618 review flagged.
      if (isStaleSessionEpoch(sessionEpoch)) return
      inFlightSends.delete(send)
      completedTurnSequence++
      persistedUserTurnsByChatId.set(
        chatId,
        Math.max(persistedUserTurnsByChatId.get(chatId) ?? 0, send.persistedUserTurnsBefore + 1),
      )
      if (spaceId) {
        // Moves the chat to the top of its space's list after every turn, mirroring the backend's
        // own updatedAt bump. Keyed by chat, so it applies whichever chat is shown now.
        useChatListStore.getState().touchChat(spaceId, response.chatId, new Date().toISOString())
        if (response.chatTitle) {
          useChatListStore.getState().updateChatTitle(spaceId, response.chatId, response.chatTitle)
        }
        if (isFirstTurn) {
          scheduleTitleReload(get, set, response.chatId, spaceId)
        }
        // The server brings a chat back from the person's archive with every own turn - also one
        // archived while this answer was still being generated.
        const listed = useChatListStore.getState().chatsBySpaceId[spaceId]
        const archivedMeanwhile =
          (isTargetChatShown() && get().archivedAt !== null) ||
          (listed !== undefined && !listed.some((chat) => chat.id === response.chatId))
        if (archivedWhenSent || archivedMeanwhile) {
          get().applyArchivedAt(chatId, null)
          notify('Chat aus dem Archiv zurückgeholt', 'info')
          void useChatListStore.getState().chatReturnedFromArchive(spaceId)
        }
      }
      if (!isSendingViewShown() || send.persisted) {
        // Another chat shown now keeps its own state. The own chat, reloaded in the meantime, may
        // lack this turn in its snapshot - or already show it - and is read again instead of
        // appended to.
        set({ isLoading: isViewAwaitingAnswer(get().chatId) })
        if (isTargetChatShown()) reloadChatTurns(get, set, chatId)
        return
      }
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        sources: response.sources,
        answeredWithoutKnowledge: response.metadata.answeredWithoutKnowledge ?? false,
        noKnowledgeAssignedToSpace: response.metadata.noKnowledgeAssignedToSpace ?? false,
        noKnowledgeAvailableInSpace: response.metadata.noKnowledgeAvailableInSpace ?? false,
        searchedLibraries: response.metadata.searchedLibraries ?? [],
        privateSourcesInContext: response.privateSourcesInContext,
        timestamp: new Date(),
      }
      set((state) => ({
        messages: [...state.messages, assistantMessage],
        isLoading: isViewAwaitingAnswer(state.chatId),
        chatId: response.chatId,
        // #557: the chat's current title right after this turn - still the mechanical prefix
        // fallback on a first turn, see scheduleTitleReload above for how the LLM-derived title
        // eventually replaces it.
        title: response.chatTitle ?? state.title,
        // #1488: the note state that went into *this* answer, minus the points removed since it
        // was sent (see removedNoteItemIdsByChatId). Null only for an ephemeral query without a
        // persisted chat - the note then stays as it is rather than being emptied.
        noteItems: response.noteItems
          ? visibleNoteItems(response.chatId, response.noteItems)
          : state.noteItems,
      }))
    } catch (err) {
      // #575: a failure arriving after a logout must not write isLoading/error into the
      // now-emptied store either - same reasoning as the two success write-backs above.
      if (isStaleSessionEpoch(sessionEpoch)) return
      inFlightSends.delete(send)
      const isLoading = isViewAwaitingAnswer(get().chatId)
      // A failure is shown in its own chat only, not in one the person switched to.
      if (!isTargetChatShown()) {
        set({ isLoading })
        parkRefusal(err)
        return
      }
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'Ein unerwarteter Fehler ist aufgetreten'
      // A question the server rejected (4xx) was not persisted: it leaves the history and goes
      // back to the input. The error only promises that once the input confirms it.
      const refusal = classifyRefusal(err)
      if (refusal) {
        const { reason, note } = refusal
        set((state) => ({
          messages: state.messages.filter((m) => m !== send.userMessage),
          error: reason,
          isLoading,
        }))
        return {
          restoreDraft: question,
          onRestored: () => {
            if (isTargetChatShown() && get().error === reason) set({ error: `${reason} ${note}` })
          },
        }
      }
      set({ error: message, isLoading })
    } finally {
      inFlightSends.delete(send)
    }
  },

  setScopeAll: () => {
    // Already showing @Space-Wissen - nothing to replace. Short-circuiting here avoids a PATCH
    // that would just re-send the chat's current settings (#564 review).
    if (get().scope === 'all') return
    // Re-adding @Space-Wissen replaces any concrete chips (#560) - the two are mutually
    // exclusive states of the same bar, never shown together.
    applyScopeChange(get, set, 'all', [])
  },

  addReferencedLibrary: (libraryId: string) => {
    const { scope, referencedLibraryIds } = get()
    // The first concrete chip replaces @Space-Wissen; from 'libraries' or 'none' it simply
    // extends/starts the selection (#560).
    const previousIds = scope === 'libraries' ? referencedLibraryIds : []
    if (previousIds.includes(libraryId)) return
    applyScopeChange(get, set, 'libraries', [...previousIds, libraryId])
  },

  removeReferencedLibrary: (libraryId: string) => {
    const next = get().referencedLibraryIds.filter((id) => id !== libraryId)
    // Removing the last concrete chip empties the bar rather than falling back to @Space-Wissen -
    // "leere Leiste = ohne Wissen" (#560), with an explicit one-click way back via setScopeAll.
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

  removeNoteItem: async (itemId: string) => {
    const { chatId, noteItems } = get()
    if (!chatId) return
    const index = noteItems.findIndex((item) => item.id === itemId)
    if (index < 0) return
    const removed = noteItems[index]
    const sessionEpoch = currentSessionEpoch()

    // Optimistic: the point is gone from the list and from every note state the server delivers
    // for this chat until a GET chat confirms the removal (see removedNoteItemIdsByChatId).
    const pending = removedNoteItemIdsByChatId.get(chatId) ?? new Set<string>()
    pending.add(itemId)
    removedNoteItemIdsByChatId.set(chatId, pending)
    // A previous removal failure is cleared with the new attempt: a repeated failure must render as
    // a new alert, not leave an unchanged one standing that assistive technology does not announce
    // again. Any other error is not this action's to clear.
    const { error } = get()
    set({
      noteItems: noteItems.filter((item) => item.id !== itemId),
      error: error !== null && error === noteRemovalError ? null : error,
    })

    try {
      await deleteChatNoteItem(chatId, itemId)
    } catch (err) {
      const stillPending = removedNoteItemIdsByChatId.get(chatId)
      stillPending?.delete(itemId)
      if (stillPending?.size === 0) removedNoteItemIdsByChatId.delete(chatId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      // A failure arriving after the user switched chats must not push this point into the chat
      // they are looking at now - the note is per chat.
      if (get().chatId !== chatId) return
      const message = err instanceof Error ? err.message : 'Notizpunkt konnte nicht entfernt werden'
      const current = get().noteItems
      // A note state that arrived in the meantime may already carry the point again now that the
      // filter is gone; only put it back when it is actually missing, at the position it had.
      noteRemovalError = message
      if (current.some((item) => item.id === itemId)) {
        set({ error: message })
        return
      }
      const restored = [...current]
      restored.splice(Math.min(index, restored.length), 0, removed)
      set({ noteItems: restored, error: message })
    }
  },

  clearReturnedQuestion: () => set({ returnedQuestion: null }),

  reset: () => {
    // Invalidates any loadChat still in flight, matching startNewChat above - otherwise a
    // response arriving after reset() could resurrect the previous user's chat.
    chatLoadSequence++
    inFlightSends.clear()
    persistedUserTurnsByChatId.clear()
    parkedRefusalsByChatId.clear()
    parkedRefusalsByNewChatSpaceId.clear()
    // #1488: the pending removals belong to the chat the previous user had open - keeping them
    // would filter points out of the next user's chats until some load confirmed them.
    clearRemovedNoteItemCache()
    // #440 review, point 3: both module-level maps are keyed by chatId, not scoped to any
    // particular user - a stale entry for a chat the previous user had open would otherwise
    // survive into the next user's session in the same tab, e.g. letting a late PATCH failure
    // for that old chat roll back to settings the new user never saw (#565's rollback base).
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
