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

  // #548 review, nit c: same for a failed chat list load of the (successfully resolved) default
  // space - it used to leave a blank box with neither spinner nor message.
  it('shows a German error with a retry action when loading the default space chats fails', async () => {
    server.use(
      http.get('/api/v1/spaces/:spaceId/chats', () => {
        return HttpResponse.json({ error: 'Chats konnten nicht geladen werden' }, { status: 500 })
      }),
    )

    renderWithProviders(<ChatRedirect />, { withRouter: true })

    expect(await screen.findByText('Chats konnten nicht geladen werden')).toBeInTheDocument()
    expect(mockNavigate).not.toHaveBeenCalled()

    server.use(
      http.get('/api/v1/spaces/:spaceId/chats', () => {
        return HttpResponse.json([])
      }),
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-personal/chats/new', {
        replace: true,
      })
    })
  })
})
