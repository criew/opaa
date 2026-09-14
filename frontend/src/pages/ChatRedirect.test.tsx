import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
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

  // Regression guard for #1647: existing chats in the default space must not change the target -
  // this entry point used to reopen the most recently used one, so typing straight away silently
  // continued an old conversation.
  it('redirects to an empty chat in the default space, even when that space already has chats', async () => {
    renderWithProviders(<ChatRedirect />, { withRouter: true })

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/new', {
        replace: true,
      })
    })
  })

  it('picks the space flagged as default, not the first one listed', async () => {
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-engineering',
          name: 'Engineering',
          description: 'Dokumente der Entwicklung',
          isDefault: false,
          archived: false,
          visibility: 'PRIVATE',
          memberCount: 3,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
        {
          id: 'space-phoenix',
          name: 'Phoenix',
          description: 'Projektdokumente',
          isDefault: true,
          archived: false,
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

  // The target no longer depends on the chat list, so this entry point must not fetch it - the
  // sidebar loads it on its own once the chat page renders.
  it('does not load the chat list to decide where to go', async () => {
    const chatListRequests = vi.fn()
    server.use(
      http.get('/api/v1/spaces/:spaceId/chats', () => {
        chatListRequests()
        return HttpResponse.json([])
      }),
    )

    renderWithProviders(<ChatRedirect />, { withRouter: true })

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/new', {
        replace: true,
      })
    })
    expect(chatListRequests).not.toHaveBeenCalled()
  })

  // #548 review, nit c: a failed space list load used to leave a spinner spinning forever, with no
  // way out for the user.
  it('shows a German error with a retry action when loading spaces fails, instead of spinning forever', async () => {
    server.use(
      http.get('/api/v1/spaces', () => {
        return HttpResponse.json({ error: 'Spaces konnten nicht geladen werden' }, { status: 500 })
      }),
    )

    renderWithProviders(<ChatRedirect />, { withRouter: true })

    expect(await screen.findByText('Spaces konnten nicht geladen werden')).toBeInTheDocument()
    expect(mockNavigate).not.toHaveBeenCalled()

    // Escape route: a retry button, not a dead end.
    server.use(
      http.get('/api/v1/spaces', () => {
        return HttpResponse.json([])
      }),
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }))

    await waitFor(() => {
      expect(screen.getByText('Kein Arbeitsraum verfügbar.')).toBeInTheDocument()
    })
  })
})
