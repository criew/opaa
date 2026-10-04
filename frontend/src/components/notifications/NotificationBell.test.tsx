import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
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
    renderWithProviders(<NotificationBell />, { withRouter: true })

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the unread count and lists notifications when authenticated', async () => {
    setMockAuthState()
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json([
          {
            id: 'n1',
            type: 'ASSET_ASSOCIATED_TO_MIXED_SPACE',
            title: 'Ihre Bibliothek wurde in einem Space bereitgestellt',
            body: 'Die Bibliothek "Rechtsquellen" wurde im Space "Team A" bereitgestellt.',
            readAt: null,
            createdAt: '2026-03-01T10:00:00Z',
          },
        ]),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<NotificationBell />, { withRouter: true })

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
            type: 'ASSET_ASSOCIATED_TO_MIXED_SPACE',
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
    renderWithProviders(<NotificationBell />, { withRouter: true })

    await waitFor(() => {
      expect(screen.getByLabelText('Benachrichtigungen, 1 ungelesen')).toBeInTheDocument()
    })
    await user.click(screen.getByLabelText('Benachrichtigungen, 1 ungelesen'))
    await user.click(screen.getByText('Ungelesene Benachrichtigung'))

    await waitFor(() => {
      expect(markedRead).toBe(true)
    })
  })

  it('leads from an expired connection to the page "Verbundene Konten"', async () => {
    setMockAuthState()
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json([
          {
            id: 'n-expired',
            type: 'CONNECTION_EXPIRED',
            title: 'Verbindung abgelaufen: Zugang „Nextcloud intern“',
            readAt: null,
            createdAt: '2026-10-01T10:00:00Z',
          },
        ]),
      ),
      http.post(
        '/api/v1/notifications/n-expired/read',
        () => new HttpResponse(null, { status: 204 }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/" element={<NotificationBell />} />
        <Route path="/settings/accounts" element={<p>Seite Verbundene Konten</p>} />
      </Routes>,
      { withRouter: true },
    )

    await user.click(await screen.findByLabelText('Benachrichtigungen, 1 ungelesen'))
    await user.click(screen.getByText('Verbindung abgelaufen: Zugang „Nextcloud intern“'))

    expect(await screen.findByText('Seite Verbundene Konten')).toBeInTheDocument()
  })
})
