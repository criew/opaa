import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
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

  // regression guard for WCAG 2.5.3: the badge's text is the button's only visible text, so the
  // name carries it word for word and there is no hidden "0" when nothing is unread.
  it.each([
    [0, 'Benachrichtigungen, keine ungelesenen', null],
    [3, 'Benachrichtigungen, 3 ungelesen', '3'],
    [120, 'Benachrichtigungen, 99+ ungelesen', '99+'],
  ])('names the bell after its badge for %i unread', async (unread, name, badgeText) => {
    setMockAuthState()
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json(
          Array.from({ length: unread }, (_, i) => ({
            id: `n${i}`,
            type: 'ASSET_ASSOCIATED_TO_MIXED_SPACE',
            title: `Meldung ${i}`,
            readAt: null,
            createdAt: '2026-03-01T10:00:00Z',
          })),
        ),
      ),
    )
    renderWithProviders(<NotificationBell />, { withRouter: true })

    const bell = await screen.findByRole('button', { name })
    if (badgeText === null) {
      expect(bell).toHaveTextContent(/^$/)
    } else {
      expect(bell).toHaveTextContent(badgeText)
    }
  })

  it.each([
    ['CONNECTION_EXPIRED', 'Verbindung abgelaufen: Zugang „Nextcloud intern“'],
    ['CONNECTION_ENDED', 'Verbindung getrennt: Zugang „Nextcloud intern“'],
  ])('leads from %s to the page "Verbundene Konten"', async (type, title) => {
    setMockAuthState()
    server.use(
      http.get('/api/v1/notifications', () =>
        HttpResponse.json([
          {
            id: 'n-expired',
            type,
            title,
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
    await user.click(screen.getByText(title))

    expect(await screen.findByText('Seite Verbundene Konten')).toBeInTheDocument()
  })
})
