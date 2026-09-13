import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { clearHandoverInFlight, markHandoverInFlight } from '../stores/handoverFlow'
import AuthCallbackPage from './AuthCallbackPage'

/**
 * The return trip of a provider sign-in. The case this guards is the handover (#1563): the tab may
 * well hold a session when the callback lands - a local one restored at start-up, an OIDC one from
 * before - and carrying the person into the application then would leave the redemption unfinished
 * and the account half-way between two identities.
 */
describe('AuthCallbackPage', () => {
  function renderCallback() {
    return renderWithProviders(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="/chat" element={<div>Chat</div>} />
          <Route path="/handover" element={<div>Übergabe</div>} />
        </Routes>
      </MemoryRouter>,
      { withNotificationHost: false },
    )
  }

  beforeEach(() => {
    clearHandoverInFlight()
  })

  afterEach(() => {
    clearHandoverInFlight()
    vi.restoreAllMocks()
  })

  it('goes on to the handover page when the callback carried one', async () => {
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      isAuthenticated: false,
      error: null,
      handleOidcCallback: vi.fn().mockResolvedValue('handover'),
    })

    renderCallback()

    expect(await screen.findByText('Übergabe')).toBeInTheDocument()
  })

  it('stays put while a handover is in flight, even with a session in this tab', async () => {
    markHandoverInFlight()
    let resolveCallback: (outcome: 'handover') => void = () => {}
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      // the session a restore at start-up would have produced - it must not win the race
      isAuthenticated: true,
      error: null,
      handleOidcCallback: vi.fn(
        () =>
          new Promise<'handover'>((resolve) => {
            resolveCallback = resolve
          }),
      ),
    })

    renderCallback()

    expect(screen.queryByText('Chat')).toBeNull()
    resolveCallback('handover')
    expect(await screen.findByText('Übergabe')).toBeInTheDocument()
  })

  it('takes an ordinary session into the application', async () => {
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      isAuthenticated: true,
      error: null,
      handleOidcCallback: vi.fn().mockResolvedValue('session'),
    })

    renderCallback()

    await waitFor(() => expect(screen.getByText('Chat')).toBeInTheDocument())
  })
})
