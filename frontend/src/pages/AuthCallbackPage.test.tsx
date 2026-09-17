import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { User, UserManager } from 'oidc-client-ts'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { clearHandoverInFlight, markHandoverInFlight } from '../stores/handoverFlow'
import AuthCallbackPage from './AuthCallbackPage'

function ChatAt() {
  const location = useLocation()
  return <div>{`Chat ${location.pathname}${location.search}${location.hash}`}</div>
}

/**
 * The return trip of a provider sign-in. The case this guards is the handover (#1563): the tab may
 * well hold a session when the callback lands - a local one restored at start-up, an OIDC one from
 * before - and carrying the person into the application then would leave the redemption unfinished
 * and the account half-way between two identities.
 */
describe('AuthCallbackPage', () => {
  const { handleOidcCallback } = useAuthStore.getState()

  function renderCallback() {
    return renderWithProviders(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="/chat" element={<div>Chat</div>} />
          <Route path="/spaces/:spaceId/chats/:chatId" element={<ChatAt />} />
          <Route path="/handover" element={<div>Übergabe</div>} />
        </Routes>
      </MemoryRouter>,
      { withNotificationHost: false },
    )
  }

  beforeEach(() => {
    clearHandoverInFlight()
    sessionStorage.clear()
    localStorage.clear()
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: true,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
      sessionKind: null,
      handleOidcCallback,
    })
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
      handleOidcCallback: vi.fn().mockResolvedValue({ kind: 'handover' }),
    })

    renderCallback()

    expect(await screen.findByText('Übergabe')).toBeInTheDocument()
  })

  it('stays put while a handover is in flight, even with a session in this tab', async () => {
    markHandoverInFlight()
    let resolveCallback: (outcome: { kind: 'handover' }) => void = () => {}
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      // the session a restore at start-up would have produced - it must not win the race
      isAuthenticated: true,
      error: null,
      handleOidcCallback: vi.fn(
        () =>
          new Promise<{ kind: 'handover' }>((resolve) => {
            resolveCallback = resolve
          }),
      ),
    })

    renderCallback()

    expect(screen.queryByText('Chat')).toBeNull()
    resolveCallback({ kind: 'handover' })
    expect(await screen.findByText('Übergabe')).toBeInTheDocument()
  })

  it('keeps a failed handover callback on its error, even with a session in this tab', async () => {
    markHandoverInFlight()
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      // the session a restore at start-up would have produced
      isAuthenticated: true,
      error: null,
      // the store's catch branch clears the handover flag before it reports the failure
      handleOidcCallback: vi.fn(async () => {
        await Promise.resolve()
        clearHandoverInFlight()
        useAuthStore.setState({ error: 'Die Übergabe ist fehlgeschlagen.' })
        return { kind: 'failed' as const }
      }),
    })

    renderCallback()

    expect(await screen.findByText('Die Übergabe ist fehlgeschlagen.')).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText('Chat')).toBeNull()
    expect(screen.getByText('Die Übergabe ist fehlgeschlagen.')).toBeInTheDocument()
  })

  it('falls back to the chat page when an ordinary callback fails with a session in this tab', async () => {
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      isAuthenticated: true,
      error: null,
      handleOidcCallback: vi.fn().mockResolvedValue({ kind: 'failed' }),
    })

    renderCallback()

    expect(await screen.findByText('Chat')).toBeInTheDocument()
  })

  it('takes an ordinary session into the application', async () => {
    useAuthStore.setState({
      mode: 'oidc',
      isLoading: false,
      isAuthenticated: true,
      error: null,
      handleOidcCallback: vi.fn().mockResolvedValue({ kind: 'session', returnTo: '/chat' }),
    })

    renderCallback()

    await waitFor(() => expect(screen.getByText('Chat')).toBeInTheDocument())
  })

  /**
   * #1685: the whole provider trip with the real store - only the provider's answer is scripted.
   * The route travels in the sign-in state oidc-client-ts hands back, and whatever arrives there is
   * treated as untrusted input.
   */
  describe('return to the route the sign-in was started for (#1685)', () => {
    const provider = {
      id: 'p-opaa',
      displayName: 'Verzeichnisdienst',
      issuerUri: 'https://idp.example.test/realms/opaa',
      clientId: 'opaa-frontend',
      isDefault: true,
      sortOrder: 0,
    }

    async function signInReturningWithState(userState: unknown) {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({ mode: 'oidc', providers: [provider] }),
        ),
      )
      sessionStorage.setItem('opaa.oidc.flowProvider', provider.id)
      const now = Math.floor(Date.now() / 1000)
      vi.spyOn(UserManager.prototype, 'signinRedirectCallback').mockResolvedValue(
        new User({
          access_token: 'provider-token',
          token_type: 'Bearer',
          profile: {
            sub: 'user-1',
            iss: provider.issuerUri,
            aud: provider.clientId,
            exp: now + 900,
            iat: now,
          },
          expires_at: now + 900,
          userState,
        }),
      )
      renderCallback()
      await act(() => useAuthStore.getState().initialize())
    }

    it('lands on the linked chat, query and fragment included', async () => {
      await signInReturningWithState({ returnTo: '/spaces/s-1/chats/c-1?q=1#m-2' })

      expect(await screen.findByText('Chat /spaces/s-1/chats/c-1?q=1#m-2')).toBeInTheDocument()
    })

    it('lands on the chat page when the sign-in carried no route', async () => {
      await signInReturningWithState(undefined)

      expect(await screen.findByText('Chat')).toBeInTheDocument()
    })

    it.each(['//evil.example', '/%2F%2Fevil.example', 'https://evil.example/spaces/s-1/chats/c-1'])(
      'lands on the chat page for a route off this origin (%s)',
      async (returnTo) => {
        await signInReturningWithState({ returnTo })

        expect(await screen.findByText('Chat')).toBeInTheDocument()
        expect(useAuthStore.getState().isAuthenticated).toBe(true)
      },
    )

    it('still hands a handover to its page first, whatever route travels along', async () => {
      markHandoverInFlight()
      await signInReturningWithState({
        handoverCode: 'code-1',
        returnTo: '/spaces/s-1/chats/c-1',
      })

      expect(await screen.findByText('Übergabe')).toBeInTheDocument()
      expect(screen.queryByText(/^Chat/)).toBeNull()
    })
  })
})
