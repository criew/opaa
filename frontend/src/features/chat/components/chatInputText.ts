import { QUESTION_MAX_LENGTH } from '../stores/chatStore'

export interface ActiveMention {
  /** Index of the '@' character that opened the mention in the current text value. */
  start: number
  /** Text typed after '@', used to filter suggestions. */
  query: string
}

/**
 * The chip bar's special entry: every knowledge library associated with the chat's space that the
 * person may read - never more, always offered first.
 */
export const SPACE_KNOWLEDGE_LABEL = 'Space-Wissen'

/** A knowledge library associated with the chat's space and readable by the person. */
export interface SpaceLibrary {
  id: string
  name: string
}

export type MentionSuggestion = { kind: 'all' } | { kind: 'library'; library: SpaceLibrary }

/** The input's accessible name; the placeholder only adds the prefix hint to it. */
export const INPUT_LABEL = 'Nachricht eingeben'
const INPUT_PROMPT = `${INPUT_LABEL} …`

/**
 * The neutral prefix hint: the input triggers more than a question - prompts, later agents and
 * skills - so it names the prefixes instead of counting libraries. '/' is named only while some
 * action source is active. What the next question searches is stated by the chip bar.
 */
const INPUT_HINT = '@ für Quellen'
const ACTIONS_HINT = '/ für Aktionen'
const SHORT_INPUT_HINT = '@ Quellen'
const SHORT_ACTIONS_HINT = '/ Aktionen'

/**
 * The hint as the field's description; while an answer is pending '@' changes the search scope
 * and stays closed, so only '/' is named.
 */
export function inputHint(answerPending: boolean, actions: boolean): string {
  if (answerPending) return actions ? ACTIONS_HINT : ''
  return actions ? `${INPUT_HINT}, ${ACTIONS_HINT}` : INPUT_HINT
}

/**
 * The placeholder carries the same hint after the prompt. `compact` (narrow screens, down to
 * 320 px) leaves out the prompt and the "für" so it fits the field in one line.
 */
export function inputPlaceholder(
  answerPending: boolean,
  actions: boolean,
  compact: boolean,
): string {
  if (!compact) {
    const hint = inputHint(answerPending, actions)
    return hint ? `${INPUT_PROMPT} ${hint}` : INPUT_PROMPT
  }
  const parts = [answerPending ? null : SHORT_INPUT_HINT, actions ? SHORT_ACTIONS_HINT : null]
  const hint = parts.filter((part) => part !== null).join(', ')
  return hint || INPUT_PROMPT
}

/** A refused question goes back before whatever the person has typed since. */
export function withRestoredQuestion(question: string, draft: string): string {
  return draft.trim() === '' ? question : `${question}\n\n${draft}`
}

/** From this share of {@link QUESTION_MAX_LENGTH} on, the input counts the characters. */
const LENGTH_COUNTER_THRESHOLD = 0.9

/**
 * The counter under the input, measured on the trimmed text that is actually sent; `null` while
 * the question is well below the limit.
 */
export function lengthCounterText(length: number): string | null {
  if (length < QUESTION_MAX_LENGTH * LENGTH_COUNTER_THRESHOLD) return null
  const counted = `${length} von ${QUESTION_MAX_LENGTH} Zeichen`
  if (length <= QUESTION_MAX_LENGTH) return counted
  return `${counted} – bitte um ${length - QUESTION_MAX_LENGTH} Zeichen kürzen, damit sich die Frage senden lässt.`
}

/**
 * The screen reader announcement for the length: one fixed sentence per threshold state, so it
 * changes - and is announced - only when the question crosses 90 % or the limit, not per keystroke.
 */
export function lengthAnnouncement(length: number): string {
  if (length > QUESTION_MAX_LENGTH) {
    return `Die Frage ist länger als ${QUESTION_MAX_LENGTH} Zeichen und lässt sich nicht senden.`
  }
  if (length >= QUESTION_MAX_LENGTH * LENGTH_COUNTER_THRESHOLD) {
    return `Die Frage nähert sich der Grenze von ${QUESTION_MAX_LENGTH} Zeichen und lässt sich senden.`
  }
  return ''
}

/**
 * Finds an in-progress '@' mention ending at the cursor, or null if none is active. Only
 * triggers when '@' starts a word (start of text or preceded by whitespace) and the fragment
 * typed so far contains no whitespace - typing a space closes the mention.
 */
export function findActiveMention(text: string, cursor: number): ActiveMention | null {
  const upToCursor = text.slice(0, cursor)
  const atIndex = upToCursor.lastIndexOf('@')
  if (atIndex === -1) return null
  const charBefore = atIndex === 0 ? '' : text[atIndex - 1]
  if (charBefore && !/\s/.test(charBefore)) return null
  const query = upToCursor.slice(atIndex + 1)
  if (/\s/.test(query)) return null
  return { start: atIndex, query }
}
