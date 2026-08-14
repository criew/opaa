import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import Sidebar from './Sidebar'
import { useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '../stores/spaceStore'

describe('Sidebar', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isLoading: false, error: null, conversationId: null })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Meine Dokumente',
          description: 'Private',
          isDefault: true,
          visibility: 'PRIVATE',
          memberCount: 1,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
  })

  it('renders navigation items', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('Spaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('Meine Dokumente')).toBeInTheDocument()
    expect(screen.getByText('Einstellungen')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.getByText('KI-Projektassistent')).toBeInTheDocument()
  })

  it('renders New Chat button', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByRole('button', { name: /neuer chat/i })).toBeInTheDocument()
  })

  it('clears messages when New Chat button is clicked', async () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'Hello', timestamp: new Date() }],
      conversationId: 'conv-123',
    })

    renderWithProviders(<Sidebar />, { withRouter: true })

    await userEvent.click(screen.getByRole('button', { name: /neuer chat/i }))

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.conversationId).toBeNull()
  })
})
