import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MessageList, { ANSWER_ARRIVED_ANNOUNCEMENT } from './MessageList'
import type { ChatMessage } from '../../types/chat'

describe('MessageList', () => {
  it('renders empty state when no messages', () => {
    render(<MessageList messages={[]} isLoading={false} />)
    expect(screen.getByText('Womit kann ich Ihnen heute helfen?')).toBeInTheDocument()
  })

  // regression guard for #958: the empty-state heading follows the page's h1 directly, so it
  // must be a level-2 heading - anything deeper skips a level for screen-reader navigation.
  it('renders the empty-state heading as level 2', () => {
    render(<MessageList messages={[]} isLoading={false} />)
    expect(
      screen.getByRole('heading', { level: 2, name: 'Womit kann ich Ihnen heute helfen?' }),
    ).toBeInTheDocument()
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

  // #749: the live region (visuallyHidden -> position: absolute) sits at the end of the message
  // list. Without a positioned ancestor, its containing block is the initial containing block
  // (the viewport) rather than the scroll container, so its "static position" - which grows with
  // the message count - escapes the scroll container's clip and inflates
  // document.documentElement.scrollHeight, producing the outer page scrollbar described in #749
  // (verified in a real browser; jsdom has no layout engine, so this asserts the CSS containment
  // fix itself rather than the resulting page height).
  it('establishes a positioning context so the live region cannot escape the scroll container', () => {
    const messages: ChatMessage[] = [
      { id: '1', role: 'user', content: 'Hello', timestamp: new Date() },
    ]
    render(<MessageList messages={messages} isLoading={false} />)
    const list = screen.getByTestId('message-list')
    expect(getComputedStyle(list).position).toBe('relative')
  })

  describe('jump to a search hit', () => {
    const history: ChatMessage[] = [
      { id: 'm1', role: 'user', content: 'Welche Frist gilt?', timestamp: new Date() },
      {
        id: 'm2',
        role: 'assistant',
        content: 'Die Frist beträgt einen Monat.',
        timestamp: new Date(),
      },
      { id: 'm3', role: 'user', content: 'Danke', timestamp: new Date() },
    ]

    function stubReducedMotion(reduce: boolean) {
      vi.stubGlobal(
        'matchMedia',
        vi.fn((query: string) => ({
          matches: reduce && query.includes('prefers-reduced-motion: reduce'),
          media: query,
          onchange: null,
          addListener: vi.fn(),
          removeListener: vi.fn(),
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
          dispatchEvent: vi.fn(),
        })),
      )
    }

    afterEach(() => {
      vi.unstubAllGlobals()
      delete (Element.prototype as Partial<Element>).scrollIntoView
    })

    it('scrolls to the hit message, marks it and puts the focus on it', () => {
      const scrolled: Array<{ id: string | null; options: unknown }> = []
      Element.prototype.scrollIntoView = function (this: Element, options?: unknown) {
        scrolled.push({ id: this.getAttribute('data-message-id'), options })
      }
      stubReducedMotion(false)

      render(<MessageList messages={history} isLoading={false} targetMessageId="m2" />)

      const target = screen.getByRole('article', { name: 'Gefundene Nachricht: Antwort' })
      expect(target).toHaveTextContent('Die Frist beträgt einen Monat.')
      expect(target).toHaveFocus()
      expect(target).toHaveAttribute('data-highlighted', 'true')
      // The hit message is the last thing scrolled to - not the end of the list.
      expect(scrolled.at(-1)).toEqual({
        id: 'm2',
        options: { behavior: 'smooth', block: 'center' },
      })
    })

    it('jumps without a scroll animation when reduced motion is requested', () => {
      const scrolled: unknown[] = []
      Element.prototype.scrollIntoView = function (options?: unknown) {
        scrolled.push(options)
      }
      stubReducedMotion(true)

      render(<MessageList messages={history} isLoading={false} targetMessageId="m2" />)

      expect(scrolled.at(-1)).toEqual({ behavior: 'auto', block: 'center' })
    })

    it('ends the highlight once the focus leaves the message', () => {
      render(<MessageList messages={history} isLoading={false} targetMessageId="m1" />)
      const target = screen.getByRole('article', { name: 'Gefundene Nachricht: Frage' })
      expect(target).toHaveAttribute('data-highlighted', 'true')

      act(() => target.blur())

      expect(target).toHaveAttribute('data-highlighted', 'false')
    })

    it('opens the chat normally for an unknown message id', () => {
      render(<MessageList messages={history} isLoading={false} targetMessageId="does-not-exist" />)

      expect(screen.getByText('Danke')).toBeInTheDocument()
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
      expect(document.body).toHaveFocus()
    })
  })
})
