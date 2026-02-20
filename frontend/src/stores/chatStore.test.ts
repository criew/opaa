import { describe, expect, it, beforeEach } from 'vitest'
import { useChatStore } from './chatStore'

describe('chatStore', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isLoading: false, error: null })
  })

  it('starts with empty state', () => {
    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeNull()
  })

  it('sends a message and receives a response', async () => {
    await useChatStore.getState().sendMessage('What is the architecture?')

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(2)
    expect(state.messages[0].role).toBe('user')
    expect(state.messages[0].content).toBe('What is the architecture?')
    expect(state.messages[1].role).toBe('assistant')
    expect(state.messages[1].sources!.length).toBeGreaterThanOrEqual(1)
    expect(state.isLoading).toBe(false)
  })

  it('clears messages', async () => {
    await useChatStore.getState().sendMessage('Hello')
    useChatStore.getState().clearMessages()

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.error).toBeNull()
  })
})
