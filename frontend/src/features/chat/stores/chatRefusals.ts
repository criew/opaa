import type { ChatState, RefusedQuestion } from './chatState'

/** The longest question the server accepts (`QueryRequest.question`, `maxLength`). */
export const QUESTION_MAX_LENGTH = 2000

export const INVALID_QUESTION_MESSAGE = `Die Frage konnte nicht gesendet werden. Bitte prüfen Sie die Eingabe (höchstens ${QUESTION_MAX_LENGTH} Zeichen).`

export const DRAFT_RESTORED_NOTE = 'Die Frage steht wieder im Eingabefeld.'

export const DRAFT_RESTORED_WITHOUT_PROMPT_NOTE =
  'Die Frage steht wieder im Eingabefeld und lässt sich ohne Prompt senden.'

/** The server's bean validation names the rejected field first, e.g. `question: …`. */
export function isQuestionViolation(message: string): boolean {
  return message.startsWith('question:')
}

/** A refused question whose chat was not shown when the refusal arrived; keyed by chat. */
export interface ParkedRefusal {
  question: string
  reason: string
  note: string
}

/** The HTTP status of a failed request, or `null` when no response arrived. */
export function responseStatus(err: unknown): number | null {
  const cause = err instanceof Error ? err.cause : undefined
  const status = (cause as { response?: { status?: unknown } } | undefined)?.response?.status
  return typeof status === 'number' ? status : null
}

/** The server refused the question because the person may no longer use its prompt. */
export function isPromptNotUsable(err: unknown): boolean {
  const cause = err instanceof Error ? err.cause : undefined
  const data = (cause as { response?: { data?: { code?: unknown } } } | undefined)?.response?.data
  return data?.code === 'PROMPT_NOT_USABLE'
}

// Questions the server refused while another chat was shown; handed back when the person returns.
export const parkedRefusalsByChatId = new Map<string, ParkedRefusal>()
// Same for a question whose chat creation was refused: no chat id exists yet, so it is keyed by
// the space whose new-chat view it was sent from.
export const parkedRefusalsByNewChatSpaceId = new Map<string, ParkedRefusal>()

/** Parks a refusal; a second one for the same key is appended so neither question is lost. */
export function parkRefused(
  map: Map<string, ParkedRefusal>,
  key: string,
  refusal: ParkedRefusal,
): void {
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
export function parkedAsReturnedQuestion(
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
