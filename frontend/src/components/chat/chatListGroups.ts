import type { ChatSummary } from '../../types/api'

export type ChatGroupKey = 'pinned' | 'today' | 'yesterday' | 'last7Days' | 'last30Days' | 'older'

export interface ChatGroup {
  key: ChatGroupKey
  label: string
  chats: ChatSummary[]
}

const GROUP_LABELS: Record<ChatGroupKey, string> = {
  pinned: 'Angeheftet',
  today: 'Heute',
  yesterday: 'Gestern',
  last7Days: 'Letzte 7 Tage',
  last30Days: 'Letzte 30 Tage',
  older: 'Älter',
}

const DAY_MS = 24 * 60 * 60 * 1000

export function chatTitle(chat: ChatSummary): string {
  return chat.title?.trim() || 'Unbenannter Chat'
}

/**
 * Calendar days between the local day of `instant` and the local day of `now`, in the browser's
 * time zone - independent of the time of day and of daylight saving shifts in between.
 */
function calendarDaysBefore(instant: Date, now: Date): number {
  const day = Date.UTC(instant.getFullYear(), instant.getMonth(), instant.getDate())
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((today - day) / DAY_MS)
}

function timeGroupOf(updatedAt: string, now: Date): ChatGroupKey {
  const days = calendarDaysBefore(new Date(updatedAt), now)
  if (days <= 0) return 'today'
  if (days === 1) return 'yesterday'
  if (days <= 7) return 'last7Days'
  if (days <= 30) return 'last30Days'
  return 'older'
}

/** Case-insensitive title match on the title as the list shows it; an empty query matches all. */
export function matchesTitle(chat: ChatSummary, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('de')
  return needle === '' || chatTitle(chat).toLocaleLowerCase('de').includes(needle)
}

/**
 * Orders a person's chats into the sidebar groups (docs/features/chat-list.md, "Zeitgruppen"):
 * pinned chats first, most recently pinned on top, and every other chat by its last activity into
 * Heute, Gestern, Letzte 7 Tage, Letzte 30 Tage and Älter, newest first. Empty groups are omitted.
 */
export function groupChats(chats: ChatSummary[], now: Date): ChatGroup[] {
  const byKey = new Map<ChatGroupKey, ChatSummary[]>()
  for (const chat of chats) {
    const key = chat.pinnedAt ? 'pinned' : timeGroupOf(chat.updatedAt, now)
    const members = byKey.get(key) ?? []
    members.push(chat)
    byKey.set(key, members)
  }
  const order: ChatGroupKey[] = ['pinned', 'today', 'yesterday', 'last7Days', 'last30Days', 'older']
  return order
    .filter((key) => byKey.has(key))
    .map((key) => {
      const members = [...(byKey.get(key) ?? [])]
      if (key === 'pinned') {
        members.sort((a, b) => Date.parse(b.pinnedAt ?? '') - Date.parse(a.pinnedAt ?? ''))
      } else {
        members.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
      }
      return { key, label: GROUP_LABELS[key], chats: members }
    })
}
