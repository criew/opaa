import { describe, expect, it } from 'vitest'
import { chatTitle, splitChats } from './chatListSections'
import type { ChatSummary } from '../../types/api'

function chat(id: string, updatedAt: string, pinnedAt?: string): ChatSummary {
  return {
    id,
    spaceId: 'space-1',
    authorId: 'user-1',
    title: id,
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    createdAt: updatedAt,
    updatedAt,
    pinnedAt: pinnedAt ?? null,
  }
}

describe('chatTitle', () => {
  it('falls back to a placeholder for an empty or blank title', () => {
    expect(chatTitle({ title: null })).toBe('Unbenannter Chat')
    expect(chatTitle({ title: '   ' })).toBe('Unbenannter Chat')
    expect(chatTitle({ title: 'Fristen' })).toBe('Fristen')
  })
})

describe('splitChats', () => {
  it('puts pinned chats into their own section, most recently pinned first', () => {
    const sections = splitChats([
      chat('alt', '2026-09-20T10:00:00Z', '2026-09-01T08:00:00Z'),
      chat('neu', '2026-09-10T10:00:00Z', '2026-09-05T08:00:00Z'),
    ])

    expect(sections.pinned.map((c) => c.id)).toEqual(['neu', 'alt'])
    expect(sections.recent).toEqual([])
  })

  it('orders the remaining chats by last activity, newest first', () => {
    const sections = splitChats([
      chat('mittel', '2026-09-10T10:00:00Z'),
      chat('neu', '2026-09-22T10:00:00Z'),
      chat('alt', '2025-01-02T10:00:00Z'),
    ])

    expect(sections.recent.map((c) => c.id)).toEqual(['neu', 'mittel', 'alt'])
    expect(sections.pinned).toEqual([])
  })

  // The server omits trailing zero fractions, so ISO strings of the same second do not sort
  // lexicographically - they are compared as instants.
  it('compares timestamps as instants, not as strings', () => {
    const sections = splitChats([
      chat('ohne-bruch', '2026-09-22T10:00:01Z'),
      chat('mit-bruch', '2026-09-22T10:00:00.500Z'),
    ])

    expect(sections.recent.map((c) => c.id)).toEqual(['ohne-bruch', 'mit-bruch'])
  })

  it('leaves the input array untouched', () => {
    const chats = [chat('b', '2026-09-10T10:00:00Z'), chat('a', '2026-09-22T10:00:00Z')]

    splitChats(chats)

    expect(chats.map((c) => c.id)).toEqual(['b', 'a'])
  })
})
