import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'

describe('MessageBubble', () => {
  it('renders user message content', () => {
    const msg: ChatMessage = {
      id: '1',
      role: 'user',
      content: 'Hello there',
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Hello there')).toBeInTheDocument()
  })

  it('renders assistant message with feedback buttons', () => {
    const msg: ChatMessage = {
      id: '2',
      role: 'assistant',
      content: 'Here is the answer',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Here is the answer')).toBeInTheDocument()
    expect(screen.getByLabelText('thumbs up')).toBeInTheDocument()
  })

  it('renders assistant message with markdown', () => {
    const msg: ChatMessage = {
      id: '4',
      role: 'assistant',
      content: 'This is **bold** text',
      sources: [],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    const bold = screen.getByText('bold')
    expect(bold.tagName).toBe('STRONG')
  })

  it('renders user message as plain text without markdown parsing', () => {
    const msg: ChatMessage = {
      id: '5',
      role: 'user',
      content: 'This is **not bold**',
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('This is **not bold**')).toBeInTheDocument()
    expect(screen.queryByText('not bold')?.tagName).not.toBe('STRONG')
  })

  it('renders source cards for assistant messages', () => {
    const msg: ChatMessage = {
      id: '3',
      role: 'assistant',
      content: 'Answer',
      sources: [{ fileName: 'test.md', relevanceScore: 0.9, excerpt: 'Excerpt' }],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('test.md')).toBeInTheDocument()
    expect(screen.getByText('90% relevant')).toBeInTheDocument()
  })
})
