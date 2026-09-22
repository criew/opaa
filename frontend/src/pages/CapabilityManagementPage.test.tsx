import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { HttpResponse, http } from 'msw'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import CapabilityManagementPage from './CapabilityManagementPage'
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

describe('CapabilityManagementPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  // ADR-0036, Entscheidung 5: je Anlegerecht eine Klartextzeile des aktuellen Stands.
  it('shows one plain-text line per capability, including the one nobody holds', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    expect(
      await screen.findByText('Alle Konten dürfen Konnektorbibliotheken anlegen.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/Niemand darf interne Gruppen anlegen/)).toBeInTheDocument()
    expect(screen.getAllByText('Alle Konten').length).toBeGreaterThan(0)
  })

  // ADR-0036, Entscheidung 5: Der Entzug von „Alle Konten" ist ein Governance-Ereignis - die
  // Rückfrage nennt deshalb eine andere Tragweite als die einer einzelnen Gruppe, und geprüft wird
  // der abgesetzte Entzug, nicht die unveränderte Anzeige.
  it('withdraws a capability from all accounts after a confirmation naming its reach', async () => {
    const revoked: string[] = []
    server.use(
      http.delete('/api/v1/admin/capabilities/:capability/grants/:grantId', ({ params }) => {
        revoked.push(`${String(params.capability)}/${String(params.grantId)}`)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await screen.findByRole('region', { name: 'Konnektorbibliotheken anlegen' })
    await user.click(within(card).getByRole('button', { name: /entziehen/i }))

    const dialog = await screen.findByRole('dialog', {
      name: '„Konnektorbibliotheken anlegen" entziehen?',
    })
    expect(dialog).toHaveTextContent(/für jedes Konto der Organisation/)
    await user.click(within(dialog).getByRole('button', { name: 'Entziehen' }))

    await waitFor(() =>
      expect(revoked).toEqual(['CREATE_CONNECTOR_LIBRARY/capability-grant-connector-all']),
    )
  })

  // ADR-0036, Entscheidung 2: Ein Recht an eine Gruppe eines externen Anbieters verlangt eine
  // ausdrückliche Zwischenfrage - und ohne Bestätigung geht keine Anfrage hinaus.
  it('asks back before granting to a group of an external provider and sends nothing on abort', async () => {
    const granted: string[] = []
    server.use(
      http.post('/api/v1/admin/capabilities/:capability/grants', ({ params }) => {
        granted.push(String(params.capability))
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await screen.findByRole('region', { name: 'Spaces anlegen' })
    await user.type(within(card).getByLabelText('Gruppe'), 'Referat 50')
    const external = await screen.findByRole(
      'option',
      { name: /Verzeichnis Partner/ },
      { timeout: 3000 },
    )
    await user.click(external)
    await user.click(within(card).getByRole('button', { name: 'Erteilen' }))

    await answerConfirm(
      user,
      'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?',
      'Abbrechen',
    )
    expect(granted).toEqual([])
  })

  it('grants a capability to a group after the search', async () => {
    const granted: Array<{ capability: string; subjectId: string | null }> = []
    server.use(
      http.post('/api/v1/admin/capabilities/:capability/grants', async ({ params, request }) => {
        const body = (await request.json()) as { subjectId?: string | null }
        granted.push({
          capability: String(params.capability),
          subjectId: body.subjectId ?? null,
        })
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await screen.findByRole('region', { name: 'Spaces anlegen' })
    await user.type(within(card).getByLabelText('Gruppe'), 'Referat 5 Projektteam')
    const option = await screen.findByRole(
      'option',
      { name: /Referat 5 Projektteam/ },
      { timeout: 3000 },
    )
    await user.click(option)
    await user.click(within(card).getByRole('button', { name: 'Erteilen' }))

    await waitFor(() => expect(granted).toHaveLength(1))
    expect(granted[0].capability).toBe('CREATE_SPACE')
    expect(granted[0].subjectId).toBe('group-phoenix')
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
