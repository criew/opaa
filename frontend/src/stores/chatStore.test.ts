import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../mocks/server'
import { useChatStore } from './chatStore'

describe('chatStore', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isLoading: false, error: null, conversationId: null })
  })

  it('starts with empty state', () => {
    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeNull()
    expect(state.conversationId).toBeNull()
  })

  it('sends a message and receives a response with conversationId', async () => {
    await useChatStore.getState().sendMessage('What is the architecture?')

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(2)
    expect(state.messages[0].role).toBe('user')
    expect(state.messages[0].content).toBe('What is the architecture?')
    expect(state.messages[1].role).toBe('assistant')
    expect(state.messages[1].sources!.length).toBeGreaterThanOrEqual(1)
    expect(state.isLoading).toBe(false)
    expect(state.conversationId).toBeTruthy()
  })

  it('preserves conversationId across messages', async () => {
    await useChatStore.getState().sendMessage('First question')
    const firstConvId = useChatStore.getState().conversationId

    await useChatStore.getState().sendMessage('Follow-up question')
    const secondConvId = useChatStore.getState().conversationId

    expect(firstConvId).toBeTruthy()
    expect(secondConvId).toBeTruthy()
    // The mock echoes back the conversationId we send, so it should be the same
    expect(secondConvId).toBe(firstConvId)
  })

  it('shows rate limit error when server returns 429', async () => {
    server.use(
      http.post('/api/v1/query', () => {
        return HttpResponse.json(
          {
            error: 'Rate limit exceeded. Please try again later.',
            status: 429,
            timestamp: new Date().toISOString(),
          },
          { status: 429 },
        )
      }),
    )

    await useChatStore.getState().sendMessage('Hello')

    const state = useChatStore.getState()
    expect(state.error).toBe('Rate limit exceeded. Please try again later.')
    expect(state.isLoading).toBe(false)
    expect(state.messages).toHaveLength(1)
    expect(state.messages[0].role).toBe('user')
  })

  it('clears messages and resets conversationId', async () => {
    await useChatStore.getState().sendMessage('Hello')
    expect(useChatStore.getState().conversationId).toBeTruthy()

    useChatStore.getState().clearMessages()

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.error).toBeNull()
    expect(state.conversationId).toBeNull()
  })
})
