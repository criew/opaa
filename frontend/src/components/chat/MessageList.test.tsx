import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import MessageList, { ANSWER_ARRIVED_ANNOUNCEMENT } from './MessageList'
import type { ChatMessage } from '../../types/chat'

describe('MessageList', () => {
  it('renders empty state when no messages', () => {
    render(<MessageList messages={[]} isLoading={false} />)
    expect(screen.getByText('Womit kann ich Ihnen heute helfen?')).toBeInTheDocument()
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
    expect(screen.getByText('Denkt nach …')).toBeInTheDocument()
  })

  it('exposes the loading indicator as a polite status', () => {
    render(<MessageList messages={[]} isLoading={true} />)
    const indicator = screen.getAllByRole('status').find((r) => r.textContent?.includes('Denkt'))
    expect(indicator).toHaveAttribute('aria-live', 'polite')
  })

  it('announces an arrived answer in the live region and clears it again', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<MessageList messages={[]} isLoading={true} />)
      rerender(<MessageList messages={[]} isLoading={false} />)

      const regions = screen.getAllByRole('status')
      expect(regions.some((r) => r.textContent === ANSWER_ARRIVED_ANNOUNCEMENT)).toBe(true)

      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(screen.queryByText(ANSWER_ARRIVED_ANNOUNCEMENT)).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not announce anything on first render without a pending answer', () => {
    render(<MessageList messages={[]} isLoading={false} />)
    expect(screen.queryByText(ANSWER_ARRIVED_ANNOUNCEMENT)).not.toBeInTheDocument()
  })
})
