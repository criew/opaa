import type { ChatMessage } from '../types/chat'
import { createChat } from '../api/chatApi'
import { sendQuery } from '../api/queryApi'
import { useChatListStore } from './chatListStore'
import { notify } from '@/shared/stores/notificationStore'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { confirmedSettingsByChatId, settingsUpdateChains } from './chatSettingsPersistence'
import { visibleNoteItems } from './chatNoteRemovals'
import { scheduleTitleReload } from './chatTitleReload'
import { reloadChatTurns } from './chatDetail'
import {
  DRAFT_RESTORED_NOTE,
  DRAFT_RESTORED_WITHOUT_PROMPT_NOTE,
  INVALID_QUESTION_MESSAGE,
  isPromptNotUsable,
  isQuestionViolation,
  parkRefused,
  parkedRefusalsByChatId,
  parkedRefusalsByNewChatSpaceId,
  responseStatus,
} from './chatRefusals'
import {
  currentChatLoadSequence,
  inFlightSends,
  isNewChatViewOf,
  isViewAwaitingAnswer,
  persistedUserTurnsByChatId,
  recordCompletedTurn,
} from './chatTurnTracking'
import type { InFlightSend } from './chatTurnTracking'
import type { ChatGet, ChatSet, RefusedQuestion } from './chatState'

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

/** The store's `sendMessage`; see {@link ChatState.sendMessage} for its contract. */
export async function sendChatMessage(
  get: ChatGet,
  set: ChatSet,
  question: string,
  usedPrompt?: { id: string; title: string },
): Promise<RefusedQuestion | void> {
  // This call's token in the session epoch, captured before any await below. Checked
  // again before every set() that follows an await - a logout (resetAllStores) in the meantime
  // bumps the epoch, so a response arriving afterwards is recognized as stale and its write-back
  // is skipped instead of resurrecting the previous user's chatId/messages into the now-emptied
  // store (this applies to both the implicit-chat-creation write-back and the final
  // answer write-back below).
  const sessionEpoch = currentSessionEpoch()

  // Whether this is the chat's first-ever turn - captured before the optimistic user
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
    loadSequence: currentChatLoadSequence(),
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
  const isSendingViewShown = () =>
    isTargetChatShown() && currentChatLoadSequence() === send.loadSequence

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
    // chip bar shows.
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
      // A logout in between (e.g. a 401 elsewhere triggering authStore.logout()) must not
      // let this chat's id resurrect into the now-emptied store.
      if (isStaleSessionEpoch(sessionEpoch)) return
      // The question is sent to the created chat either way; only the view it was sent from
      // becomes that chat.
      const viewTakesChat = isSendingViewShown()
      send.chatId = created.id
      if (viewTakesChat) set({ chatId: created.id })
      // The settings this chat was just created with are the server's own record too - same
      // reasoning as loadChat above.
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
    // backend answers using the chat's persisted settings. Reading
    // settingsUpdateChains by chatId here, not the global pendingSettingsUpdate slot: the slot
    // only ever reflects the most recently *started* settings change across all chats - a fast
    // PATCH on another chat can already have cleared it back to null while this chat's own chain
    // is still running (e.g. slow change on chat A, switch to chat B, fast change on B, switch
    // back to A - pendingSettingsUpdate would be null even though A's chain has not settled yet).
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
    // The query answer arriving after a logout must not resurrect messages/chatId into the
    // now-emptied store - the second of the two write-back paths after the await.
    if (isStaleSessionEpoch(sessionEpoch)) return
    inFlightSends.delete(send)
    recordCompletedTurn()
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
      generatedByAi: response.generatedByAi,
      sources: response.sources,
      answeredWithoutKnowledge: response.metadata.answeredWithoutKnowledge ?? false,
      noKnowledgeAssignedToSpace: response.metadata.noKnowledgeAssignedToSpace ?? false,
      noKnowledgeAvailableInSpace: response.metadata.noKnowledgeAvailableInSpace ?? false,
      searchedLibraries: response.metadata.searchedLibraries ?? [],
      answerTruncated: response.metadata.answerTruncated ?? false,
      answerEmpty: response.metadata.answerEmpty ?? false,
      privateSourcesInContext: response.privateSourcesInContext,
      timestamp: new Date(),
    }
    set((state) => ({
      messages: [...state.messages, assistantMessage],
      isLoading: isViewAwaitingAnswer(state.chatId),
      chatId: response.chatId,
      // The chat's current title right after this turn - still the mechanical prefix
      // fallback on a first turn, see scheduleTitleReload above for how the LLM-derived title
      // eventually replaces it.
      title: response.chatTitle ?? state.title,
      // The note state that went into *this* answer, minus the points removed since it
      // was sent (see removedNoteItemIdsByChatId). Null only for an ephemeral query without a
      // persisted chat - the note then stays as it is rather than being emptied.
      noteItems: response.noteItems
        ? visibleNoteItems(response.chatId, response.noteItems)
        : state.noteItems,
    }))
  } catch (err) {
    // A failure arriving after a logout must not write isLoading/error into the
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
}
