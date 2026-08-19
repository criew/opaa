import { describe, expect, it, beforeEach } from 'vitest'
import { useChatListStore } from './chatListStore'

const SPACE_ID = 'space-personal'

describe('chatListStore', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('loads chats for a space, sorted by last use', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats).toHaveLength(2)
    expect(chats?.[0].id).toBe('chat-personal-2')
    expect(chats?.[1].id).toBe('chat-personal-1')
  })

  it('creates a new chat and adds it to the space list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    const created = await useChatListStore.getState().createChatInSpace(SPACE_ID)

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.some((chat) => chat.id === created.id)).toBe(true)
  })

  it('renames a chat in the list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    await useChatListStore.getState().renameChat(SPACE_ID, 'chat-personal-1', 'Neuer Titel')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.find((chat) => chat.id === 'chat-personal-1')?.title).toBe('Neuer Titel')
  })

  it('deletes a chat from the list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    await useChatListStore.getState().deleteChatFromList(SPACE_ID, 'chat-personal-1')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.some((chat) => chat.id === 'chat-personal-1')).toBe(false)
  })

  it('sets an error when loading fails', async () => {
    await useChatListStore.getState().loadChats('space-unknown')

    expect(useChatListStore.getState().error).toBeTruthy()
  })
})
