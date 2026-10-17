import { getChat } from '../api/chatApi'
import { useChatListStore } from './chatListStore'
import type { ChatState } from './chatState'

// The backend generates an LLM title asynchronously, after the answer is already returned
// (see QueryResponse#chatTitle's Javadoc) - it is never present on the very turn that triggers it.
// This is the frontend half of "Zuschnitt frei: nachgeladen": a single delayed reload of the chat
// after a first turn's answer arrives, giving the backend's async generation a realistic window to
// finish. Best-effort only - if it is not done yet, or the reload fails, the fallback title already
// shown (from QueryResponse#chatTitle) simply stays.
const TITLE_RELOAD_DELAY_MS = 2500

// The server never overwrites a manually set title (ChatTitleGenerationService writes only
// while titleSource is GENERATED), but the delayed reload below would still *display* the
// generated one if the rename happened inside its window - and its PATCH was still in flight when
// the reload read the chat. A chat renamed by hand is therefore excluded from that reload for the
// rest of the session; every rename path goes through chatListStore#renameChat, which marks it.
export const manuallyRenamedChatIds = new Set<string>()

export function markChatManuallyRenamed(chatId: string): void {
  manuallyRenamedChatIds.add(chatId)
}

/** Test seam: the marks are module state and would otherwise leak between test cases. */
export function clearManualRenameMarks(): void {
  manuallyRenamedChatIds.clear()
}

export function scheduleTitleReload(
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
