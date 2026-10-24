import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { OPAA_BRANDING, useBrandingStore } from '@/shared/branding/brandingStore'
import ChatInput from './ChatInput'
import MessageBubble from './MessageBubble'
import MessageList from './MessageList'

function setNotice(aiNotice: string) {
  useBrandingStore.setState({ branding: { ...OPAA_BRANDING, aiNotice } })
}

describe('AI notice and answer mark', () => {
  afterEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

  it('shows the branding notice in the empty chat before the first question', () => {
    render(<MessageList messages={[]} isLoading={false} />)

    const notice = screen.getByRole('note', { name: 'Hinweis zur KI-Nutzung' })
    expect(notice).toHaveTextContent(OPAA_BRANDING.aiNotice)
  })

  it('shows a changed notice text from the branding', () => {
    setNotice('Dieser Assistent nutzt KI; bitte prüfen Sie jede Antwort.')

    render(<MessageList messages={[]} isLoading={false} />)

    expect(screen.getByRole('note', { name: 'Hinweis zur KI-Nutzung' })).toHaveTextContent(
      'Dieser Assistent nutzt KI; bitte prüfen Sie jede Antwort.',
    )
  })

  it('keeps the notice permanently under the input', () => {
    setNotice('Dieser Assistent nutzt KI; bitte prüfen Sie jede Antwort.')

    render(
      <MemoryRouter>
        <ChatInput onSend={() => {}} />
      </MemoryRouter>,
    )

    expect(screen.getByTestId('ai-notice-line')).toHaveTextContent(
      'Dieser Assistent nutzt KI; bitte prüfen Sie jede Antwort.',
    )
  })

  it('marks every answer, and only answers, with an accessible name', () => {
    const { rerender } = render(
      <MessageBubble
        message={{ id: '1', role: 'assistant', content: 'Antwort', timestamp: new Date() }}
      />,
    )
    const mark = screen.getByRole('note', { name: 'Diese Antwort wurde von einer KI erzeugt' })
    expect(mark).toHaveTextContent('KI-erzeugt')

    rerender(
      <MessageBubble
        message={{ id: '2', role: 'user', content: 'Frage', timestamp: new Date() }}
      />,
    )
    expect(screen.queryByTestId('ai-answer-mark')).not.toBeInTheDocument()
  })

  it('marks older answers loaded from a stored chat just the same', () => {
    render(
      <MessageList
        messages={[
          { id: '1', role: 'user', content: 'Frage', timestamp: new Date('2026-03-05T09:00:00Z') },
          {
            id: '2',
            role: 'assistant',
            content: 'Alte Antwort',
            timestamp: new Date('2026-03-05T09:00:05Z'),
          },
        ]}
        isLoading={false}
      />,
    )

    expect(screen.getAllByTestId('ai-answer-mark')).toHaveLength(1)
  })
})
