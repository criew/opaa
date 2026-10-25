import { act, render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
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

describe('InstallationRedirect', () => {
  it('leads every route to the assistant while the installation is pending', async () => {
    setMockInstallationPending(true)
    renderAt('/login')
    expect(await screen.findByText('Assistent')).toBeInTheDocument()
  })

  it('leaves an installed instance where it is', async () => {
    let answered: () => void = () => {}
    const probed = new Promise<void>((resolve) => (answered = resolve))
    server.use(
      http.get('/api/v1/installation', () => {
        answered()
        return HttpResponse.json({ error: 'Nicht gefunden', status: 404 }, { status: 404 })
      }),
    )
    renderAt('/login')
    expect(screen.getByText('Anmeldung')).toBeInTheDocument()

    // Once the probe has its 404 and the state settled, the route must still be the same.
    await act(() => probed)
    expect(screen.getByText('Anmeldung')).toBeInTheDocument()
  })
})
