import { afterEach, describe, expect, it } from 'vitest'
import { mockArchivedChatsForSpace, mockChatsForSpace } from './chatFixtures'
import { ensureScenarioChats } from './chatScenarioFixtures'
import { resetChatMockState } from './chatHandlers'

describe('chat scenario fixtures', () => {
  afterEach(() => {
    window.sessionStorage.clear()
    resetChatMockState()
  })

  it('fills the personal space with a hundred chats in scale, eight pinned and two archived', () => {
    window.sessionStorage.setItem('opaa.mockScenario', 'scale')
    ensureScenarioChats()

    const active = mockChatsForSpace('space-personal')
    expect(active.length + mockArchivedChatsForSpace('space-personal').length).toBe(102)
    expect(active.filter((chat) => chat.pinnedAt)).toHaveLength(8)
    expect(active.some((chat) => (chat.title?.length ?? 0) > 80)).toBe(true)
  })

  it('leaves every space without chats in empty', () => {
    window.sessionStorage.setItem('opaa.mockScenario', 'empty')
    ensureScenarioChats()

    expect(mockChatsForSpace('space-personal')).toHaveLength(0)
  })
})
