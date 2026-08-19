import { waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ChatRedirect from './ChatRedirect'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

describe('ChatRedirect', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    useSpaceStore.setState({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('redirects to the default space and its most recently used chat', async () => {
    renderWithProviders(<ChatRedirect />, { withRouter: true })

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/chat-personal-2', {
        replace: true,
      })
    })
  })

  it('redirects to a not-yet-created chat when the default space has none', async () => {
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-phoenix',
          name: 'Phoenix',
          description: 'Projektdokumente',
          isDefault: true,
          visibility: 'PRIVATE',
          memberCount: 2,
          userRole: 'CURATOR',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
    })

    renderWithProviders(<ChatRedirect />, { withRouter: true })

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-phoenix/chats/new', {
        replace: true,
      })
    })
  })
})
