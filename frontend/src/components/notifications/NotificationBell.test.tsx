import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { server } from '../../mocks/server'
import { renderWithProviders, setMockAuthState } from '../../test/test-utils'
import { useAuthStore } from '../../stores/authStore'
import NotificationBell from './NotificationBell'

describe('NotificationBell (#203)', () => {
  it('renders nothing when the user is not authenticated', () => {
    useAuthStore.setState({ isAuthenticated: false })
    renderWithProviders(<NotificationBell />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the unread count and lists notifications when authenticated', async () => {
    setMockAuthState()
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json([
          {
            id: 'n1',
            type: 'LIBRARY_ASSOCIATED_TO_MIXED_SPACE',
            title: 'Ihre Bibliothek wurde in einem Space bereitgestellt',
            body: 'Die Bibliothek "Rechtsquellen" wurde im Space "Team A" bereitgestellt.',
            readAt: null,
            createdAt: '2026-03-01T10:00:00Z',
          },
        ]),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<NotificationBell />)

    await waitFor(() => {
      expect(screen.getByLabelText('Benachrichtigungen, 1 ungelesen')).toBeInTheDocument()
    })

    await user.click(screen.getByLabelText('Benachrichtigungen, 1 ungelesen'))

    expect(
      screen.getByText('Ihre Bibliothek wurde in einem Space bereitgestellt'),
    ).toBeInTheDocument()
  })

  it('marks a notification read on click', async () => {
    setMockAuthState()
    let markedRead = false
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json([
          {
            id: 'n1',
            type: 'LIBRARY_ASSOCIATED_TO_MIXED_SPACE',
            title: 'Ungelesene Benachrichtigung',
            readAt: null,
            createdAt: '2026-03-01T10:00:00Z',
          },
        ]),
      ),
      http.post('/api/v1/notifications/n1/read', () => {
        markedRead = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<NotificationBell />)

    await waitFor(() => {
      expect(screen.getByLabelText('Benachrichtigungen, 1 ungelesen')).toBeInTheDocument()
    })
    await user.click(screen.getByLabelText('Benachrichtigungen, 1 ungelesen'))
    await user.click(screen.getByText('Ungelesene Benachrichtigung'))

    await waitFor(() => {
      expect(markedRead).toBe(true)
    })
  })
})
