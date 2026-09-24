import type { ChatSummary } from '../../types/api'

/** How many recent chats the list shows at once, and how many each "weitere anzeigen" adds. */
export const RECENT_PAGE_SIZE = 15

export interface ChatSections {
  /** Every pinned chat, most recently pinned first. */
  pinned: ChatSummary[]
  /** Every other chat, by last activity, newest first. */
  recent: ChatSummary[]
}

export function chatTitle(chat: Pick<ChatSummary, 'title'>): string {
  return chat.title?.trim() || 'Unbenannter Chat'
}

/**
 * Splits a person's chats into the two sections of the sidebar: pinned chats, most recently
 * pinned on top, and everything else under "Zuletzt verwendet", by last activity.
 */
export function splitChats(chats: ChatSummary[]): ChatSections {
  const pinned = chats.filter((chat) => chat.pinnedAt)
  const recent = chats.filter((chat) => !chat.pinnedAt)
  pinned.sort((a, b) => Date.parse(b.pinnedAt ?? '') - Date.parse(a.pinnedAt ?? ''))
  recent.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
  return { pinned, recent }
}
