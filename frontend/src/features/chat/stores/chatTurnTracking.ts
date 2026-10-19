import type { ChatMessage } from '../types/chat'
import type { ChatMessageResponse } from '@/shared/api/types'

// Monotonically increasing token guarding loadChat against two hazards:
// a slower-arriving response from an earlier loadChat(A) call overwriting a faster one from a
// later loadChat(B), and a synchronous startNewChat() in between being clobbered once the
// in-flight loadChat eventually resolves. Deliberately module-level, not store state - it is
// never read by a component, only compared against itself across async gaps.
let chatLoadSequence = 0

export function currentChatLoadSequence(): number {
  return chatLoadSequence
}

/** Supersedes every load and not-yet-created chat view started so far; returns the new token. */
export function advanceChatLoadSequence(): number {
  return ++chatLoadSequence
}

/** A question whose answer is still outstanding. `chatId` is the chat it is answered in - null
 * until its implicit creation finished; `loadSequence` identifies the not-yet-created chat view it
 * was sent from until then. */
export interface InFlightSend {
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
export const inFlightSends = new Set<InFlightSend>()

// Per chat, the number of user messages the server is known to hold - the baseline a question
// records when it is sent. Module state like inFlightSends; reset() clears it.
export const persistedUserTurnsByChatId = new Map<string, number>()

/**
 * Takes note of a server read of `chatId` that is about to be applied. An outstanding question
 * counts as contained once a user message with its text follows the user messages persisted when
 * it was sent - the position tells a repeated question from the earlier one. A contained question
 * is neither appended again nor keeps the view loading; its answer then re-reads the chat.
 */
export function recordPersistedTurns(chatId: string, serverMessages: ChatMessageResponse[]): void {
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

export function currentCompletedTurnSequence(): number {
  return completedTurnSequence
}

export function recordCompletedTurn(): void {
  completedTurnSequence++
}

/** Whether the view showing `chatId` - or, for null, the current not-yet-created chat - waits for
 * an answer. */
export function isViewAwaitingAnswer(chatId: string | null): boolean {
  for (const send of inFlightSends) {
    if (chatId !== null ? send.chatId === chatId && !send.persisted : isNewChatViewOf(send)) {
      return true
    }
  }
  return false
}

export function isNewChatViewOf(send: InFlightSend): boolean {
  return send.chatId === null && send.loadSequence === chatLoadSequence
}

/** The server's messages plus the questions still outstanding for this chat that the read did not
 * contain yet - the server only persists a question together with its answer. */
export function withOutstandingQuestions(chatId: string, messages: ChatMessage[]): ChatMessage[] {
  const outstanding = [...inFlightSends]
    .filter((send) => send.chatId === chatId && !send.persisted)
    .map((send) => send.userMessage)
  return outstanding.length > 0 ? [...messages, ...outstanding] : messages
}
