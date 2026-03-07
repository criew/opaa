import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import Sidebar from './Sidebar'
import { useChatStore } from '../stores/chatStore'
import { useWorkspaceStore } from '../stores/workspaceStore'

describe('Sidebar', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isLoading: false, error: null, conversationId: null })
    useWorkspaceStore.setState({
      workspaces: [
        {
          id: 'ws-personal',
          name: 'My Documents',
          description: 'Private',
          type: 'PERSONAL',
          memberCount: 1,
          userRole: 'OWNER',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
  })

  it('renders navigation items', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('Workspaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('My Documents')).toBeInTheDocument()
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.getByText('AI Project Assistant')).toBeInTheDocument()
  })

  it('renders New Chat button', () => {
    renderWithProviders(<Sidebar />, { withRouter: true })
    expect(screen.getByRole('button', { name: /new chat/i })).toBeInTheDocument()
  })

  it('clears messages when New Chat button is clicked', async () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'Hello', timestamp: new Date() }],
      conversationId: 'conv-123',
    })

    renderWithProviders(<Sidebar />, { withRouter: true })

    await userEvent.click(screen.getByRole('button', { name: /new chat/i }))

    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.conversationId).toBeNull()
  })
})
