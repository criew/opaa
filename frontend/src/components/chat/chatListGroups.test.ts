import { describe, expect, it } from 'vitest'
import type { ChatSummary } from '../../types/api'
import { groupChats, matchesTitle } from './chatListGroups'

// Every date is built from local components, so the day boundaries under test are the browser's
// own, whatever time zone the test runs in.
const NOW = new Date(2026, 8, 18, 0, 30) // 18.09.2026, 00:30 local time

let nextId = 0
function chat(updatedAt: Date, overrides: Partial<ChatSummary> = {}): ChatSummary {
  nextId += 1
  return {
    id: `chat-${nextId}`,
    spaceId: 'space',
    authorId: 'me',
    title: `Chat ${nextId}`,
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    createdAt: updatedAt.toISOString(),
    updatedAt: updatedAt.toISOString(),
    ...overrides,
  }
}

function groupOf(summary: ChatSummary, now: Date = NOW): string {
  const group = groupChats([summary], now)[0]
  return group.label
}

describe('groupChats', () => {
  it('puts a chat active right after midnight into Heute and one just before into Gestern', () => {
    expect(groupOf(chat(new Date(2026, 8, 18, 0, 0)))).toBe('Heute')
    expect(groupOf(chat(new Date(2026, 8, 17, 23, 59, 59)))).toBe('Gestern')
  })

  it('keeps the whole previous calendar day in Gestern', () => {
    expect(groupOf(chat(new Date(2026, 8, 17, 0, 0)))).toBe('Gestern')
    expect(groupOf(chat(new Date(2026, 8, 16, 23, 59, 59)))).toBe('Letzte 7 Tage')
  })

  it('ends Letzte 7 Tage seven calendar days back', () => {
    expect(groupOf(chat(new Date(2026, 8, 11, 0, 0)))).toBe('Letzte 7 Tage')
    expect(groupOf(chat(new Date(2026, 8, 10, 23, 59, 59)))).toBe('Letzte 30 Tage')
  })

  it('ends Letzte 30 Tage thirty calendar days back', () => {
    expect(groupOf(chat(new Date(2026, 7, 19, 0, 0)))).toBe('Letzte 30 Tage')
    expect(groupOf(chat(new Date(2026, 7, 18, 23, 59, 59)))).toBe('Älter')
  })

  it('judges by the calendar day, not by 24-hour spans', () => {
    const lateEvening = new Date(2026, 8, 18, 23, 50)
    expect(groupOf(chat(new Date(2026, 8, 18, 0, 5)), lateEvening)).toBe('Heute')
    expect(groupOf(chat(new Date(2026, 8, 17, 23, 55)), lateEvening)).toBe('Gestern')
  })

  it('counts calendar days across the end of daylight saving time', () => {
    const afterSwitch = new Date(2026, 9, 26, 0, 30)
    expect(groupOf(chat(new Date(2026, 9, 25, 0, 0)), afterSwitch)).toBe('Gestern')
    expect(groupOf(chat(new Date(2026, 9, 19, 0, 0)), afterSwitch)).toBe('Letzte 7 Tage')
  })

  it('treats a timestamp slightly in the future as Heute', () => {
    expect(groupOf(chat(new Date(2026, 8, 18, 0, 31)))).toBe('Heute')
  })

  it('omits empty groups and keeps the fixed group order', () => {
    const groups = groupChats(
      [
        chat(new Date(2026, 5, 1)),
        chat(new Date(2026, 8, 18, 0, 10)),
        chat(new Date(2026, 8, 17, 12, 0), { pinnedAt: '2026-09-17T12:00:00Z' }),
      ],
      NOW,
    )

    expect(groups.map((group) => group.label)).toEqual(['Angeheftet', 'Heute', 'Älter'])
  })

  it('lists pinned chats only under Angeheftet, most recently pinned first', () => {
    const pinnedEarlier = chat(new Date(2026, 8, 18, 0, 20), {
      pinnedAt: '2026-09-01T08:00:00Z',
    })
    const pinnedLater = chat(new Date(2026, 0, 5), { pinnedAt: '2026-09-10T08:00:00.5Z' })
    const unpinned = chat(new Date(2026, 8, 18, 0, 25))

    const groups = groupChats([pinnedEarlier, unpinned, pinnedLater], NOW)

    expect(groups[0].chats.map((c) => c.id)).toEqual([pinnedLater.id, pinnedEarlier.id])
    expect(groups[1].label).toBe('Heute')
    expect(groups[1].chats.map((c) => c.id)).toEqual([unpinned.id])
  })

  it('orders each time group by last activity, newest first', () => {
    const older = chat(new Date(2026, 8, 18, 0, 1))
    const newer = chat(new Date(2026, 8, 18, 0, 2))

    expect(groupChats([older, newer], NOW)[0].chats.map((c) => c.id)).toEqual([newer.id, older.id])
  })
})

describe('matchesTitle', () => {
  it('matches case-insensitively on the shown title', () => {
    const summary = chat(NOW, { title: 'Rückfrage Kämmerei' })

    expect(matchesTitle(summary, 'KÄMMER')).toBe(true)
    expect(matchesTitle(summary, 'rückfrage')).toBe(true)
    expect(matchesTitle(summary, 'Erlass')).toBe(false)
  })

  it('matches the fallback title of an untitled chat and everything for an empty query', () => {
    const untitled = chat(NOW, { title: null })

    expect(matchesTitle(untitled, 'unbenannt')).toBe(true)
    expect(matchesTitle(untitled, '   ')).toBe(true)
  })
})
