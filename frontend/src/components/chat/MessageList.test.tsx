import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import MessageList from './MessageList'
import type { ChatMessage } from '../../types/chat'

describe('MessageList', () => {
  it('renders empty state when no messages', () => {
    render(<MessageList messages={[]} isLoading={false} />)
    expect(screen.getByText('How can I help you today?')).toBeInTheDocument()
  })

  it('renders messages', () => {
    const messages: ChatMessage[] = [
      { id: '1', role: 'user', content: 'Hello', timestamp: new Date() },
      { id: '2', role: 'assistant', content: 'Hi there', sources: [], timestamp: new Date() },
    ]
    render(<MessageList messages={messages} isLoading={false} />)
    expect(screen.getByText('Hello')).toBeInTheDocument()
    expect(screen.getByText('Hi there')).toBeInTheDocument()
  })

  it('shows loading indicator', () => {
    render(<MessageList messages={[]} isLoading={true} />)
    expect(screen.getByText('Thinking...')).toBeInTheDocument()
  })
})
