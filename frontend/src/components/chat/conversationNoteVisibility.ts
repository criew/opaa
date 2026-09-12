import type { ChatNoteItem } from '../../types/api'

/**
 * Completed rounds a chat needs before its Gesprächsnotiz gets a surface (ADR-0031). A fixed
 * surface size, deliberately not a parameter: it buys quiet in short chats at the price of the
 * note working unseen for up to two rounds.
 */
export const NOTE_MIN_COMPLETED_ROUNDS = 3

/**
 * The note appears once *both* hold: it has at least one point and the chat has at least three
 * completed rounds. An empty note has no surface at all, so the button disappears again once the
 * last point is removed.
 */
export function isConversationNoteVisible(items: ChatNoteItem[], completedRounds: number): boolean {
  return items.length > 0 && completedRounds >= NOTE_MIN_COMPLETED_ROUNDS
}
