import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
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

/** Every grant and revocation the page sends, in order. */
function recordCalls() {
  const calls: string[] = []
  server.use(
    http.post('/api/v1/admin/capabilities/:capability/grants', async ({ params, request }) => {
      const body = (await request.json()) as {
        subjectType: string
        subjectId?: string | null
        scope?: string | null
      }
      const subject = body.subjectId ? `${body.subjectType}:${body.subjectId}` : body.subjectType
      const scope = body.scope ? ` @${body.scope}` : ''
      calls.push(`GRANT ${String(params.capability)} ${subject}${scope}`)
      return HttpResponse.json({}, { status: 201 })
    }),
    http.delete('/api/v1/admin/capabilities/:capability/grants/:grantId', ({ params }) => {
      calls.push(`REVOKE ${String(params.capability)} ${String(params.grantId)}`)
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return calls
}

async function openPanel(user: UserEvent, title: string) {
  await user.click(await screen.findByRole('button', { name: `Ändern: ${title}` }))
  return screen.findByRole('dialog')
}

async function restrictSpacesTo(user: UserEvent, search: string, option: RegExp) {
  const panel = await openPanel(user, 'Spaces')
  await user.click(within(panel).getByLabelText('Nur bestimmte Gruppen und Personen'))
  await user.type(within(panel).getByLabelText('Gruppe oder Person hinzufügen'), search)
  await user.click(await screen.findByRole('option', { name: option }, { timeout: 3000 }))
  await user.click(within(panel).getByRole('button', { name: 'Speichern' }))
  return panel
}

describe('CapabilityManagementPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  it('shows who may create what without a single input field', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    const overview = await screen.findByRole('region', { name: 'Wer darf was anlegen?' })
    const spaces = await within(overview).findByRole('listitem', { name: 'Spaces' })
    expect(spaces).toHaveTextContent('Alle Konten')
    expect(within(overview).getByRole('listitem', { name: 'Interne Gruppen' })).toHaveTextContent(
      'Nur Systemverwaltung',
    )
    const connector = within(overview).getByRole('listitem', {
      name: 'Bibliotheken mit Anbindung',
    })
    expect(within(connector).getByRole('heading', { name: 'Quellen' })).toBeInTheDocument()
    expect(within(connector).getByRole('heading', { name: 'Zugänge' })).toBeInTheDocument()
    expect(connector).toHaveTextContent('RSS-Feed')
    expect(connector).toHaveTextContent('Nextcloud intern')
    expect(within(overview).queryAllByRole('textbox')).toHaveLength(0)
    expect(within(overview).queryAllByRole('combobox')).toHaveLength(0)
  })

  // ADR-0036, Entscheidung 5: the plain-text line of each state stays part of the row.
  it('keeps the plain-text line of the state for each scope', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    const connector = await screen.findByRole('listitem', { name: 'Bibliotheken mit Anbindung' })
    expect(connector).toHaveTextContent('Quellart RSS-Feed: frei für Alle Konten.')
    expect(connector).toHaveTextContent('Zugang Nextcloud intern: aus, nur die Systemverwaltung.')
  })

  // Restricting must never leave a moment in which fewer accounts may create than before and after.
  it('restricts a right by granting the group first and withdrawing all accounts last', async () => {
    const calls = recordCalls()
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await restrictSpacesTo(user, 'Referat 5 Projektteam', /Referat 5 Projektteam/)
    await answerConfirm(user, 'Nicht mehr für alle Konten?', 'Einschränken')

    await waitFor(() =>
      expect(calls).toEqual([
        'GRANT CREATE_SPACE GROUP:group-phoenix',
        'REVOKE CREATE_SPACE capability-grant-space-all',
      ]),
    )
  })

  it('sends nothing when the withdrawal from all accounts is not confirmed', async () => {
    const calls = recordCalls()
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await restrictSpacesTo(user, 'Referat 5 Projektteam', /Referat 5 Projektteam/)
    await answerConfirm(user, 'Nicht mehr für alle Konten?', 'Abbrechen')

    expect(calls).toEqual([])
  })

  // ADR-0036, Entscheidung 2: a group of an external provider needs an explicit second question.
  it('asks back before granting to a group of an external provider and sends nothing on abort', async () => {
    const calls = recordCalls()
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await restrictSpacesTo(user, 'Referat 50', /Verzeichnis Partner/)
    await answerConfirm(user, 'Nicht mehr für alle Konten?', 'Einschränken')
    await answerConfirm(
      user,
      'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?',
      'Abbrechen',
    )

    expect(calls).toEqual([])
  })

  // ADR-0036, Nachtrag vom 03.10.2026: connector libraries are released per source and per access.
  it('opens one access of the connector right to all accounts within its scope', async () => {
    const calls = recordCalls()
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Bibliotheken mit Anbindung')
    await user.click(within(panel).getByRole('button', { name: /Nextcloud intern/ }))
    expect(
      within(panel).getByRole('heading', {
        name: 'Wer darf Bibliotheken über den Zugang „Nextcloud intern“ anlegen?',
      }),
    ).toBeInTheDocument()
    await user.click(within(panel).getByLabelText('Alle Konten'))
    await user.click(within(panel).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(calls).toEqual([
        'GRANT CREATE_CONNECTOR_LIBRARY ALL_ACCOUNTS @PROFILE:connection-profile-nextcloud',
      ]),
    )
  })

  it('names how far a change got when a later step fails', async () => {
    recordCalls()
    server.use(
      http.delete('/api/v1/admin/capabilities/:capability/grants/:grantId', () =>
        HttpResponse.json({ message: 'Interner Fehler' }, { status: 500 }),
      ),
    )
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await restrictSpacesTo(user, 'Referat 5 Projektteam', /Referat 5 Projektteam/)
    await answerConfirm(user, 'Nicht mehr für alle Konten?', 'Einschränken')

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Ausgeführt wurden 1 von 2 Schritten',
    )
  })

  it('previews the result and keeps "Speichern" off until something changes', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Interne Gruppen')
    expect(within(panel).getByRole('status')).toHaveTextContent('Keine Änderung.')
    expect(within(panel).getByRole('button', { name: 'Speichern' })).toBeDisabled()

    await user.click(within(panel).getByLabelText('Alle Konten'))
    expect(within(panel).getByRole('status')).toHaveTextContent(
      'Alle Konten dürfen interne Gruppen anlegen.',
    )
    expect(within(panel).getByRole('button', { name: 'Speichern' })).toBeEnabled()
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
