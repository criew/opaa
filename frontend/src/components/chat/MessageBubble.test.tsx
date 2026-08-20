import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble'
import type { ChatMessage } from '../../types/chat'

const citedSource = {
  fileName: 'test.md',
  relevanceScore: 0.9,
  matchCount: 1,
  indexedAt: '2025-01-15T10:30:00Z',
  cited: true,
}

const uncitedSource = {
  fileName: 'other.pdf',
  relevanceScore: 0.7,
  matchCount: 1,
  indexedAt: null,
  cited: false,
}

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
    expect(screen.getByLabelText('Daumen hoch')).toBeInTheDocument()
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

  it('lists cited sources in the Fundstellen block (#590)', () => {
    const msg: ChatMessage = {
      id: '3',
      role: 'assistant',
      content: 'Answer【source: aa#0 | test.md】',
      sources: [citedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Fundstellen')).toBeInTheDocument()
    expect(screen.getByText('1 Stelle in 1 Dokument')).toBeInTheDocument()
    expect(screen.getByText('test.md')).toBeInTheDocument()
  })

  it('hides uncited sources behind collapsible section', () => {
    const msg: ChatMessage = {
      id: '6',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource, uncitedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('test.md')).toBeInTheDocument()
    expect(
      screen.getByText(/Weitere geprüfte, nicht zitierte Treffer \(1\) anzeigen/),
    ).toBeInTheDocument()
    expect(screen.queryByText('other.pdf')).not.toBeVisible()
  })

  it('shows a hint when the answer was generated without knowledge', () => {
    const msg: ChatMessage = {
      id: '8',
      role: 'assistant',
      content: 'Answer',
      sources: [],
      answeredWithoutKnowledge: true,
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(screen.getByText('Diese Antwort wurde ohne Wissensbasis erstellt.')).toBeInTheDocument()
  })

  it('does not show the hint when the answer used the knowledge base', () => {
    const msg: ChatMessage = {
      id: '9',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource],
      answeredWithoutKnowledge: false,
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    expect(
      screen.queryByText('Diese Antwort wurde ohne Wissensbasis erstellt.'),
    ).not.toBeInTheDocument()
  })

  it('expands uncited sources on click', async () => {
    const user = userEvent.setup()
    const msg: ChatMessage = {
      id: '7',
      role: 'assistant',
      content: 'Answer',
      sources: [citedSource, uncitedSource],
      timestamp: new Date(),
    }
    render(<MessageBubble message={msg} />)
    await user.click(screen.getByText(/Weitere geprüfte, nicht zitierte Treffer \(1\) anzeigen/))
    expect(await screen.findByText('other.pdf')).toBeVisible()
  })
})
