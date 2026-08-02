import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ChatPage from './ChatPage'
import { useChatStore } from '../stores/chatStore'

describe('ChatPage', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isLoading: false, error: null })
  })

  it('renders empty state', () => {
    renderWithProviders(<ChatPage />)
    expect(screen.getByText('Womit kann ich Ihnen heute helfen?')).toBeInTheDocument()
  })

  it('sends a message and displays response with sources', async () => {
    renderWithProviders(<ChatPage />)

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'What is the architecture?' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    // User message should appear immediately
    expect(screen.getByText('What is the architecture?')).toBeInTheDocument()

    // Wait for MSW response — loading indicator should disappear and assistant reply should appear
    await waitFor(
      () => {
        expect(screen.queryByText('Denkt nach …')).not.toBeInTheDocument()
      },
      { timeout: 10000 },
    )

    // At least one source should be visible (all mock responses have sources)
    expect(screen.getAllByText(/% relevant/).length).toBeGreaterThanOrEqual(1)
  }, 15000)

  it('shows error alert when present', () => {
    useChatStore.setState({ error: 'Etwas ist schiefgelaufen' })
    renderWithProviders(<ChatPage />)
    expect(screen.getByText('Etwas ist schiefgelaufen')).toBeInTheDocument()
  })
})
