import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../mocks/server'
import { useChatStore } from './chatStore'

describe('chatStore', () => {
  beforeEach(() => {
    useChatStore.setState({
      messages: [],
      isLoading: false,
      error: null,
      chatId: null,
      useKnowledge: true,
      referencedLibraryIds: [],
    })
  })

  it('starts with empty state', () => {
    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeNull()
    expect(state.chatId).toBeNull()
  })

  it('sends a message and receives a response with chatId', async () => {
    await useChatStore.getState().sendMessage('What is the architecture?')

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(2)
    expect(state.messages[0].role).toBe('user')
    expect(state.messages[0].content).toBe('What is the architecture?')
    expect(state.messages[1].role).toBe('assistant')
    expect(state.messages[1].sources!.length).toBeGreaterThanOrEqual(1)
    expect(state.isLoading).toBe(false)
    expect(state.chatId).toBeTruthy()
  })

  it('preserves chatId across messages', async () => {
    await useChatStore.getState().sendMessage('First question')
    const firstConvId = useChatStore.getState().chatId

    await useChatStore.getState().sendMessage('Follow-up question')
    const secondConvId = useChatStore.getState().chatId

    expect(firstConvId).toBeTruthy()
    expect(secondConvId).toBeTruthy()
    // The mock echoes back the chatId we send, so it should be the same
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

  it('clears messages and resets chatId', async () => {
    await useChatStore.getState().sendMessage('Hello')
    expect(useChatStore.getState().chatId).toBeTruthy()

    useChatStore.getState().clearMessages()

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.error).toBeNull()
    expect(state.chatId).toBeNull()
  })

  it('clearMessages also resets the sticky knowledge-scope controls', () => {
    useChatStore.setState({ useKnowledge: false, referencedLibraryIds: ['library-a'] })

    useChatStore.getState().clearMessages()

    const state = useChatStore.getState()
    expect(state.useKnowledge).toBe(true)
    expect(state.referencedLibraryIds).toEqual([])
  })

  it('adds and removes referenced libraries without duplicates', () => {
    useChatStore.getState().addReferencedLibrary('library-a')
    useChatStore.getState().addReferencedLibrary('library-b')
    useChatStore.getState().addReferencedLibrary('library-a')

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a', 'library-b'])

    useChatStore.getState().removeReferencedLibrary('library-a')

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-b'])
  })

  it('sends useKnowledge=true without libraryIds when the switch is on', async () => {
    let capturedBody: Record<string, unknown> | undefined
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          answer: 'Antwort',
          sources: [],
          metadata: {
            model: 'gpt-4o',
            tokenCount: 10,
            durationMs: 5,
            answeredWithoutKnowledge: false,
          },
          chatId: 'conv-1',
        })
      }),
    )
    useChatStore.setState({ useKnowledge: true, referencedLibraryIds: ['library-a'] })

    await useChatStore.getState().sendMessage('Frage')

    expect(capturedBody?.useKnowledge).toBe(true)
    expect(capturedBody?.libraryIds).toBeUndefined()
  })

  it('sends useKnowledge=false with the referenced libraryIds when the switch is off', async () => {
    let capturedBody: Record<string, unknown> | undefined
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          answer: 'Antwort',
          sources: [],
          metadata: {
            model: 'gpt-4o',
            tokenCount: 10,
            durationMs: 5,
            answeredWithoutKnowledge: true,
          },
          chatId: 'conv-1',
        })
      }),
    )
    useChatStore.setState({ useKnowledge: false, referencedLibraryIds: ['library-a', 'library-b'] })

    await useChatStore.getState().sendMessage('Frage')

    expect(capturedBody?.useKnowledge).toBe(false)
    expect(capturedBody?.libraryIds).toEqual(['library-a', 'library-b'])
    expect(useChatStore.getState().messages[1].answeredWithoutKnowledge).toBe(true)
  })
})
