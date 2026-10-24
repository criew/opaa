import type { ChatNoteItem } from '@/shared/api/types'
import { deleteChatNoteItem } from '../api/chatApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import type { ChatGet, ChatSet } from './chatState'

// Per chat, the ids of Gesprächsnotiz points the person removed locally whose removal GET chat has
// not yet confirmed. Every note state the server delivers for a chat is filtered through
// that chat's entry, because an answer carries the note state that went into *it* - an answer that
// was already in flight when the point was removed still contains it, and applying it unfiltered
// would make a removed point reappear. Keyed by chat id, like the settings maps in chatSettingsPersistence.ts: loading
// *another* chat says nothing about this chat's server state, so it must not release anything here.
export const removedNoteItemIdsByChatId = new Map<string, Set<string>>()

/** Releases the ids of `chatId` that the server no longer reports - that is the confirmation the
 * filter waits for. Only this chat's entry is touched; removals pending for another chat stay
 * pending until that chat is loaded. */
export function confirmNoteItemRemovals(chatId: string, serverItems: ChatNoteItem[]): void {
  const pending = removedNoteItemIdsByChatId.get(chatId)
  if (!pending) return
  const stillPresent = new Set(serverItems.map((item) => item.id))
  pending.forEach((id) => {
    if (!stillPresent.has(id)) pending.delete(id)
  })
  if (pending.size === 0) removedNoteItemIdsByChatId.delete(chatId)
}

export function visibleNoteItems(chatId: string, serverItems: ChatNoteItem[]): ChatNoteItem[] {
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

/** The store's `removeNoteItem`: optimistic, rolled back with `error` when the DELETE fails. */
export async function removeNoteItem(get: ChatGet, set: ChatSet, itemId: string): Promise<void> {
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
}
