import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import SuccessionPage from './SuccessionPage'
import { useAuthStore } from '../stores/authStore'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
  })
}

function renderPage(route = '/admin/succession/open') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/succession/:tab" element={<SuccessionPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

describe('SuccessionPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  // ADR-0036, Entscheidung 6: Objekt, Adressat und Alter je Zeile - der Einstieg ist das Objekt.
  it('lists object, addressee and age, and links to the object itself', async () => {
    renderPage()

    const entry = await screen.findByText('Bauakten Referat 50')
    expect(entry).toHaveAttribute('href', '/libraries/lib-1')
    expect(screen.getByText(/Zuständig: die Systemverwaltung/)).toBeInTheDocument()
    expect(screen.getAllByText(/Alter:/)).toHaveLength(2)
    expect(screen.getByText(/war Mitglied von Referat 50/)).toBeInTheDocument()
  })

  // Personalrat E1/Z7: keine Auswertungsachse Person - weder als Filterfeld noch als Sortierung.
  it('offers no filter or sort by previous owner or acting person', async () => {
    renderPage()
    await screen.findByText('Bauakten Referat 50')

    expect(screen.queryByRole('textbox', { name: /eigentümer|person|inhaber/i })).toBeNull()
    expect(screen.queryByRole('combobox', { name: /eigentümer|person|sortier/i })).toBeNull()
    expect(screen.queryByRole('columnheader')).toBeNull()
    // Der frühere Eigentümer steht als Text in der Zeile - lesbar, aber kein Schlüssel.
    expect(screen.getByText(/Andrea Vogt/)).toBeInTheDocument()
  })

  it('writes a Sichtungsvermerk with its reason and reloads the entry', async () => {
    const reviews: Array<{ caseId: string; reason: string }> = []
    server.use(
      http.post('/api/v1/admin/succession/:caseId/reviews', async ({ params, request }) => {
        const body = (await request.json()) as { reason: string }
        reviews.push({ caseId: String(params.caseId), reason: body.reason })
        return HttpResponse.json(
          {
            id: 'review-1',
            caseId: String(params.caseId),
            reviewedAt: '2026-09-22T08:00:00Z',
            reason: body.reason,
          },
          { status: 201 },
        )
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await screen.findByText('Bauakten Referat 50')
    await user.click(screen.getAllByRole('button', { name: 'Sichtungsvermerk setzen' })[0])
    await user.type(screen.getByLabelText(/Grund/), 'Nachfolge in Klärung')
    await user.click(screen.getByRole('button', { name: 'Vermerk setzen' }))

    await waitFor(() =>
      expect(reviews).toEqual([{ caseId: 'case-1', reason: 'Nachfolge in Klärung' }]),
    )
  })

  // Ohne Vorgang des Feststellungslaufs gibt es nichts, wogegen ein Vermerk geschrieben würde.
  it('offers no Sichtungsvermerk for an entry the detection run has not seen yet', async () => {
    renderPage()

    await screen.findByText('Projekt Phoenix')
    expect(screen.getAllByRole('button', { name: 'Sichtungsvermerk setzen' })).toHaveLength(1)
    expect(screen.getByText(/noch nicht vom Feststellungslauf erfasst/)).toBeInTheDocument()
  })

  it('shows the other two tabs with their own entries', async () => {
    renderPage('/admin/succession/grants')

    expect(await screen.findByText('Referat 49')).toBeInTheDocument()
    expect(screen.getByText(/betroffene Objekte: 7/)).toBeInTheDocument()
    expect(screen.getByText(/Reorganisation läuft/)).toBeInTheDocument()
  })

  it('opens the takeover for a group entry', async () => {
    renderPage('/admin/succession/groups')
    const user = userEvent.setup()

    await screen.findByText('Arbeitskreis Digitalisierung')
    await user.click(screen.getByRole('button', { name: /übernahme vorbereiten/i }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('button', { name: /vorschau erstellen/i })).toBeInTheDocument()
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderPage()

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
