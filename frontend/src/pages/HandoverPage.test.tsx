import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { MOCK_HANDOVER_TOKEN, MOCK_RATE_LIMITED_TOKEN } from '../mocks/localAuthFixtures'
import { clearPendingHandover, setPendingHandover } from '../stores/handoverFlow'
import { useAuthStore } from '../stores/authStore'
import HandoverPage from './HandoverPage'

/**
 * ADR-0033, Entscheidung 12: the page names the provider the administration chose (nobody picks
 * one), shows what would move before the irreversible step, never leaves the code in the address
 * bar, and after the provider redirect redeems it with the token the callback handed over - the
 * only call it makes with that token.
 */
describe('HandoverPage', () => {
  const routes = (
    <Routes>
      <Route path="/handover" element={<HandoverPage />} />
      <Route path="/login" element={<div>Anmeldung</div>} />
      <Route path="/chat" element={<div>Chat</div>} />
    </Routes>
  )

  function renderAt(search: string) {
    window.history.replaceState({}, '', `/handover${search}`)
    return renderWithProviders(<BrowserRouter>{routes}</BrowserRouter>, {
      withNotificationHost: false,
    })
  }

  beforeEach(() => {
    clearPendingHandover()
  })

  afterEach(() => {
    clearPendingHandover()
    vi.restoreAllMocks()
  })

  it('shows what moves and names the provider the administration chose', async () => {
    renderAt(`?token=${MOCK_HANDOVER_TOKEN}`)

    expect(await screen.findByText('Das geht mit')).toBeInTheDocument()
    expect(screen.getByText('Mein Space')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('Benutzer')).toBeInTheDocument()
    expect(screen.getByText(/Umstellung auf den Identitätsanbieter der Stadt/)).toBeInTheDocument()
    // one button, naming the provider - there is no picker
    expect(screen.getByRole('button', { name: /anmelden und übergeben/i })).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  /**
   * ADR-0033, Entscheidung 9: no raw code in any log. The preview goes out during mount, so the URL
   * has to be clean before it - that is what the browser builds the referrer from.
   */
  it('has no code in the address bar when the first request starts', async () => {
    const seen: string[] = []
    server.events.on('request:start', () => seen.push(window.location.search))
    renderAt(`?token=${MOCK_HANDOVER_TOKEN}`)

    expect(await screen.findByText('Das geht mit')).toBeInTheDocument()
    expect(seen).not.toHaveLength(0)
    expect(seen.every((search) => !search.includes('token='))).toBe(true)
    expect(window.location.search).not.toContain('token=')
  })

  it('starts the provider sign-in with the code, not with a picked provider', async () => {
    const startHandoverSignIn = vi.fn().mockResolvedValue(true)
    useAuthStore.setState({ startHandoverSignIn })
    renderAt(`?token=${MOCK_HANDOVER_TOKEN}`)
    const button = await screen.findByRole('button', { name: /anmelden und übergeben/i })

    await userEvent.click(button)

    await waitFor(() => expect(startHandoverSignIn).toHaveBeenCalledTimes(1))
    expect(startHandoverSignIn.mock.calls[0][1]).toBe(MOCK_HANDOVER_TOKEN)
  })

  it('redeems with the provider token the callback handed over and lands in the session', async () => {
    const completeHandover = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ completeHandover })
    setPendingHandover({ code: MOCK_HANDOVER_TOKEN, providerToken: 'provider-access-token' })
    renderAt('')

    expect(
      await screen.findByText(/Ihr Zugang gehört jetzt zu Ihrer Anbieteridentität/),
    ).toBeInTheDocument()
    expect(completeHandover).toHaveBeenCalledWith('provider-access-token')
  })

  it('reads an unusable code as an invalid link and offers the way back', async () => {
    renderAt('?token=unbekannt')

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })

  it('reads a reload without a code as an invalid link', async () => {
    renderAt('')

    expect(await screen.findByText('Dieser Link ist nicht mehr gültig.')).toBeInTheDocument()
  })

  it('says how long to wait when the endpoint refuses the request', async () => {
    renderAt(`?token=${MOCK_RATE_LIMITED_TOKEN}`)

    expect(await screen.findByText(/zu viele Anfragen gestellt/i)).toBeInTheDocument()
    // the account is untouched, and the page says so rather than reading as a failure of the link
    expect(screen.getByText(/bisheriger Zugang besteht unverändert weiter/)).toBeInTheDocument()
  })
})
