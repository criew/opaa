import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { mockDirectorySyncStatus, mockPendingPlan } from '@/features/group/mocks/groupAdminFixtures'
import { mockOidcProviders } from '@/features/identity-provider/mocks/identityProviderFixtures'
import {
  manyDirectoryProviders,
  manyDirectoryStatuses,
} from '../mocks/directorySyncScenarioFixtures'
import { renderWithProviders } from '@/test/test-utils'
import DirectorySyncPage from './DirectorySyncPage'
import { useAuthStore } from '@/shared/session/authStore'
import { useOidcProviderStore } from '@/features/identity-provider'

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

/** jsdom has no matchMedia; the table renders only on a desktop viewport (guidelines 5.3). */
function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList
}

const overview = () => screen.findByRole('table', { name: 'Verzeichnisabgleich je Anbieter' })

/** The overview row of a provider, found by its name cell. */
async function rowOf(name: string) {
  const table = await overview()
  const cell = await within(table).findByText(name)
  return cell.closest('tr') as HTMLElement
}

async function openPanel(user: ReturnType<typeof userEvent.setup>, name: string) {
  const row = await rowOf(name)
  await user.click(within(row).getByRole('button', { name: new RegExp(`: ${name}$`) }))
  return screen.findByRole('dialog', { name })
}

describe('DirectorySyncPage', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
    useOidcProviderStore.getState().reset()
  })

  it('shows every provider with its mechanism, last run and next run at a glance', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    const directory = await rowOf('Verzeichnisdienst')
    expect(within(directory).getByText('Eingeschaltet')).toBeInTheDocument()
    expect(within(directory).getByText('alle 6 Stunden')).toBeInTheDocument()
    expect(within(directory).getByText('Wartet auf Bestätigung')).toBeInTheDocument()

    // ADR-0036, Entscheidung 2: a provider with a groups claim has its one mechanism already.
    const partner = await rowOf('Partnerportal')
    expect(within(partner).getByText('Gruppen aus dem Token')).toBeInTheDocument()
    expect(within(partner).getByText('kein Lauf')).toBeInTheDocument()
  })

  // ADR-0036, Entscheidung 3: Ein ausstehender Plan ist ein lauter Zustand - mit seinem Alter,
  // schon in der Übersicht, ohne dass etwas aufgeklappt werden muss.
  it('names the age of a pending plan and what it would do in the overview', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    const row = await rowOf('Verzeichnisdienst')
    expect(within(row).getByText('Entscheidung nötig')).toBeInTheDocument()
    expect(within(row).getByText(/Plan wartet seit/)).toBeInTheDocument()
    expect(
      within(row).getByText(/23 Mitgliedschaften entziehen und 1 Konto sperren/),
    ).toBeInTheDocument()
    expect(
      within(row).getByRole('button', { name: 'Entscheiden: Verzeichnisdienst' }),
    ).toBeVisible()
    expect(screen.getByText(/1 Anbieter braucht Ihre Entscheidung/)).toBeInTheDocument()
  })

  // ADR-0036, Entscheidung 9: Jeder Abruf des Plans ist ein Protokolleintrag - die Übersicht allein
  // ruft ihn deshalb nicht ab, erst das Öffnen des Anbieters.
  it('fetches a pending plan only once the provider is opened', async () => {
    let planCalls = 0
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.endsWith('/directory-sync/pending-plan')) planCalls++
    })
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    await rowOf('Verzeichnisdienst')
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(planCalls).toBe(0)

    const panel = await openPanel(user, 'Verzeichnisdienst')
    await within(panel).findByRole('button', { name: 'Plan bestätigen' })
    expect(planCalls).toBe(1)
    server.events.removeAllListeners()
  })

  // Der Differenzbericht nennt die Mitgliederzahl je Gruppe, bevor etwas angewendet
  // wird - und die Konten, die der Lauf sperren würde.
  it('shows the diff report of a dry run with the member count per group', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    await user.click(within(panel).getByRole('button', { name: 'Trockenlauf' }))

    const result = await within(panel).findByRole('region', { name: 'Ergebnis des Trockenlaufs' })
    expect(within(result).getByText(/Referat 52.*9 Mitglieder/)).toBeInTheDocument()
    expect(within(result).getByText(/Referat 50.*23 Mitglieder/)).toBeInTheDocument()
    expect(within(result).getByText(/Bernd Bauer/)).toBeInTheDocument()
  })

  it('applies a pending plan only with a reason', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    const confirm = await within(panel).findByRole('button', { name: 'Plan bestätigen' })
    expect(confirm).toBeDisabled()

    await user.type(within(panel).getByLabelText(/grund/i), 'Reorganisation zum 01.10.')
    expect(within(panel).getByRole('button', { name: 'Plan bestätigen' })).toBeEnabled()
    await user.click(within(panel).getByRole('button', { name: 'Plan bestätigen' }))

    expect(await within(panel).findByText(/bestätigte Plan wurde angewendet/)).toBeInTheDocument()
  })

  // ADR-0036, Entscheidung 9: Ein neu geladener Status, der denselben Plan meldet, darf ihn nicht
  // erneut abrufen - auch nicht, wenn das Seitenpanel geschlossen und wieder geöffnet wird.
  it('fetches a pending plan once per plan id, not on every status reload', async () => {
    let statusCalls = 0
    let planCalls = 0
    server.events.on('request:start', ({ request }) => {
      const path = new URL(request.url).pathname
      if (path === '/api/v1/admin/directory-sync/status') statusCalls++
      if (path.endsWith('/directory-sync/pending-plan')) planCalls++
    })
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    let panel = await openPanel(user, 'Verzeichnisdienst')
    await within(panel).findByRole('button', { name: 'Plan bestätigen' })
    const statusCallsBefore = statusCalls
    await user.click(within(panel).getByRole('button', { name: 'Jetzt abgleichen' }))

    await waitFor(() => expect(statusCalls).toBeGreaterThan(statusCallsBefore))
    await waitFor(() =>
      expect(within(panel).getByRole('button', { name: 'Jetzt abgleichen' })).toBeEnabled(),
    )
    await user.click(within(panel).getByRole('button', { name: 'Schließen' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    panel = await openPanel(user, 'Verzeichnisdienst')
    // Gives a fetch triggered by the re-render the chance to start before counting.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(within(panel).getByRole('button', { name: 'Plan bestätigen' })).toBeInTheDocument()
    expect(planCalls).toBe(1)
    server.events.removeAllListeners()
  })

  // ADR-0036, Entscheidung 3: Eine abgewiesene Bestätigung hinterlässt einen NEUEN Plan. Ohne das
  // Nachladen stünde weiter der alte Bericht da, und die nächste Bestätigung liefe mit der alten
  // Kennung ins Leere.
  it('presents the new plan after the shown one has drifted', async () => {
    const confirmed: string[] = []
    server.use(
      http.post(
        '/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan/:planId/confirm',
        ({ params }) => {
          confirmed.push(String(params.planId))
          return HttpResponse.json(
            { error: 'Der Plan hat sich geändert', code: 'DIRECTORY_SYNC_PLAN_CHANGED' },
            { status: 409 },
          )
        },
      ),
      // The directory drifts once the first confirmation was refused - however often the page
      // asked for the plan before that.
      http.get('/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan', () => {
        return HttpResponse.json(
          confirmed.length === 0
            ? mockPendingPlan
            : {
                ...mockPendingPlan,
                id: 'plan-2',
                createdAt: '2026-09-21T06:00:00Z',
                report: {
                  ...mockPendingPlan.report,
                  message: 'Neu gerechnet: 5 Mitgliedschaften würden entzogen.',
                },
              },
        )
      }),
    )
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    await within(panel).findByRole('button', { name: 'Plan bestätigen' })
    await user.type(within(panel).getByLabelText(/grund/i), 'Reorganisation zum 01.10.')
    await user.click(within(panel).getByRole('button', { name: 'Plan bestätigen' }))

    expect(
      await within(panel).findByText(/Verzeichnis hat sich seit der Vorlage geändert/),
    ).toBeInTheDocument()
    expect(await within(panel).findByText(/Neu gerechnet/)).toBeInTheDocument()

    await user.type(within(panel).getByLabelText(/grund/i), 'Neuer Stand geprüft')
    await user.click(within(panel).getByRole('button', { name: 'Plan bestätigen' }))

    // Die zweite Bestätigung trägt die Kennung des neu vorgelegten Plans, nicht die alte.
    await waitFor(() => expect(confirmed).toEqual(['plan-1', 'plan-2']))
  })

  // regression guard: a plan decided elsewhere answers 404 - the panel must not wait for it
  // forever, and the overview must drop the plan with a fresh status.
  it('reloads the status when the opened plan is gone', async () => {
    let statusCalls = 0
    server.use(
      http.get('/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan', () =>
        HttpResponse.json({ error: 'Kein ausstehender Plan' }, { status: 404 }),
      ),
      http.get('/api/v1/admin/directory-sync/status', () => {
        statusCalls++
        return HttpResponse.json(
          statusCalls === 1
            ? mockDirectorySyncStatus
            : mockDirectorySyncStatus.map((status) => ({
                ...status,
                lastOutcome: 'APPLIED',
                pendingPlan: null,
              })),
        )
      }),
    )
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    await waitFor(() => expect(statusCalls).toBeGreaterThan(1))
    await waitFor(() =>
      expect(within(panel).queryByText(/Plan wird geladen/)).not.toBeInTheDocument(),
    )
    expect(within(panel).queryByRole('button', { name: 'Plan bestätigen' })).not.toBeInTheDocument()
    // the modal panel hides the overview from the accessibility tree until it is closed
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const row = await rowOf('Verzeichnisdienst')
    expect(within(row).queryByText('Entscheidung nötig')).not.toBeInTheDocument()
  })

  // regression guard: a failed plan load says so and offers another try instead of loading forever.
  it('names a failed plan load and loads it again on request', async () => {
    let planCalls = 0
    server.use(
      http.get('/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan', () => {
        planCalls++
        return planCalls === 1
          ? HttpResponse.json({ error: 'Interner Fehler' }, { status: 500 })
          : HttpResponse.json(mockPendingPlan)
      }),
    )
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    expect(
      await within(panel).findByText(/ausstehende Plan konnte nicht geladen werden/),
    ).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Erneut laden' }))

    expect(
      await within(panel).findByRole('button', { name: 'Plan bestätigen' }),
    ).toBeInTheDocument()
    expect(planCalls).toBe(2)
  })

  // DirectorySyncService#requireRunnable: a confirmation runs the sync and is refused while it is
  // off; discarding is no run and stays possible.
  it('blocks confirming a plan while the sync is off, but not discarding it', async () => {
    const provider = mockOidcProviders.find((p) => p.id === 'oidc-provider-beschaeftigte')!
    provider.directorySyncEnabled = false
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Verzeichnisdienst')
    await within(panel).findByRole('button', { name: 'Plan bestätigen' })
    await user.type(within(panel).getByLabelText(/grund/i), 'Reorganisation')

    expect(within(panel).getByRole('button', { name: 'Plan bestätigen' })).toBeDisabled()
    expect(within(panel).getByRole('button', { name: 'Plan verwerfen' })).toBeEnabled()
    expect(within(panel).getByText(/Bestätigen geht erst/)).toBeInTheDocument()
  })

  it('does not switch the sync on while the interval is invalid', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Landesportal')
    const field = within(panel).getByLabelText('Intervall in Minuten')
    await user.clear(field)
    await user.type(field, '3')
    expect(within(panel).getByRole('switch', { name: 'Abgleich eingeschaltet' })).toBeDisabled()

    await user.clear(field)
    await user.type(field, '120')
    expect(within(panel).getByRole('switch', { name: 'Abgleich eingeschaltet' })).toBeEnabled()
  })

  it('switches the sync on and saves the interval from the side panel', async () => {
    const requests: unknown[] = []
    server.use(
      http.put('/api/v1/admin/oidc-providers/:providerId/directory-sync', async ({ request }) => {
        requests.push(await request.json())
        return HttpResponse.json({})
      }),
    )
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Landesportal')
    expect(within(panel).getByRole('button', { name: 'Trockenlauf' })).toBeDisabled()
    await user.click(within(panel).getByRole('switch', { name: 'Abgleich eingeschaltet' }))
    await waitFor(() => expect(requests).toEqual([{ enabled: true, intervalMinutes: 360 }]))
  })

  it('names the groups claim instead of offering a switch that would be refused', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const panel = await openPanel(user, 'Partnerportal')
    expect(within(panel).getByRole('switch', { name: 'Abgleich eingeschaltet' })).toBeDisabled()
    expect(within(panel).getByText(/Gruppen-Claim „groups“/)).toBeInTheDocument()
  })

  it('closes the side panel with Escape and returns the focus to the row', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    await openPanel(user, 'Landesportal')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Details: Landesportal' })).toHaveFocus()
  })

  describe('with many providers', () => {
    beforeEach(() => {
      server.use(
        http.get('/api/v1/admin/oidc-providers', () => HttpResponse.json(manyDirectoryProviders())),
        http.get('/api/v1/admin/directory-sync/status', () =>
          HttpResponse.json(manyDirectoryStatuses()),
        ),
      )
    })

    it('lists every provider in one compact overview', async () => {
      renderWithProviders(<DirectorySyncPage />, { withRouter: true })

      const table = await overview()
      await within(table).findByText('Personalverwaltung')
      // one header row plus one row per provider
      expect(within(table).getAllByRole('row')).toHaveLength(manyDirectoryProviders().length + 1)
    })

    it('narrows the overview to the providers that need something done', async () => {
      renderWithProviders(<DirectorySyncPage />, { withRouter: true })
      const user = userEvent.setup()

      const table = await overview()
      await within(table).findByText('Personalverwaltung')
      const attention = manyDirectoryStatuses().filter(
        (status) => status.pendingPlan || status.lastOutcome === 'UNREACHABLE',
      )

      await user.click(screen.getByRole('button', { name: /Handlungsbedarf/ }))
      const rows = within(await overview())
        .getAllByRole('row')
        .slice(1)
      expect(rows.length).toBeGreaterThanOrEqual(attention.length)
      expect(rows.length).toBeLessThan(manyDirectoryProviders().length)
      for (const status of attention) {
        expect(within(table).getByText(status.providerDisplayName)).toBeInTheDocument()
      }
    })

    it('finds a provider by its name', async () => {
      renderWithProviders(<DirectorySyncPage />, { withRouter: true })
      const user = userEvent.setup()

      const table = await overview()
      await within(table).findByText('Personalverwaltung')
      await user.type(screen.getByRole('searchbox', { name: 'Anbieter suchen' }), 'personal')

      const rows = within(await overview())
        .getAllByRole('row')
        .slice(1)
      expect(rows).toHaveLength(1)
      expect(within(rows[0]).getByText('Personalverwaltung')).toBeInTheDocument()
    })
  })

  it('falls back to a list below tablet width, with the same state and the same way in', async () => {
    window.matchMedia = (query: string) =>
      ({ ...desktopMatchMedia(query), matches: false }) as MediaQueryList
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    const entry = await screen.findByRole('article', { name: 'Verzeichnisdienst' })
    expect(within(entry).getByText('Entscheidung nötig')).toBeInTheDocument()
    expect(
      within(entry).getByRole('button', { name: 'Entscheiden: Verzeichnisdienst' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    window.matchMedia = desktopMatchMedia
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
