import { act, render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '@/shared/session/authStore'
import { setMockInstallationPending } from '@/features/installation/mocks/installationFixtures'
import { server } from '@/test/server'
import InstallationRedirect from './InstallationRedirect'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <InstallationRedirect />
      <Routes>
        <Route path="/installation" element={<p>Assistent</p>} />
        <Route path="/login" element={<p>Anmeldung</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Every request to the installation API this test made; the redirect must make none. */
let installationRequests: string[] = []

function recordRequest({ request }: { request: Request }) {
  if (new URL(request.url).pathname.startsWith('/api/v1/installation')) {
    installationRequests = [...installationRequests, request.method]
  }
}

describe('InstallationRedirect', () => {
  beforeEach(() => {
    installationRequests = []
    server.events.on('request:start', recordRequest)
    useAuthStore.setState({ installationPending: false })
  })

  afterEach(() => {
    server.events.removeListener('request:start', recordRequest)
  })

  it('leads every route to the assistant while the configuration reports a pending installation', async () => {
    useAuthStore.setState({ installationPending: true })
    renderAt('/login')
    expect(await screen.findByText('Assistent')).toBeInTheDocument()
  })

  it('follows the configuration once it reports the pending installation', async () => {
    renderAt('/login')
    expect(screen.getByText('Anmeldung')).toBeInTheDocument()

    act(() => useAuthStore.setState({ installationPending: true }))

    expect(await screen.findByText('Assistent')).toBeInTheDocument()
  })

  // regression guard for the installation token budget: a request without token counts as a
  // refused token in the backend, so the redirect must never ask the installation API itself.
  it('never asks the installation API, whatever its state', async () => {
    setMockInstallationPending(true)
    renderAt('/login')

    await act(() => Promise.resolve())
    expect(installationRequests).toEqual([])
    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })

  it.each([
    ['the configuration lacks the field', () => HttpResponse.json({ mode: 'dev', providers: [] })],
    ['the configuration fails', () => HttpResponse.json({ error: 'kaputt' }, { status: 503 })],
    ['the network fails', () => HttpResponse.error()],
  ])('stays where it is when %s', async (_, answer) => {
    server.use(http.get('/api/v1/auth/config', answer))
    renderAt('/login')

    await act(() => useAuthStore.getState().initialize())

    expect(useAuthStore.getState().installationPending).toBe(false)
    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })
})
