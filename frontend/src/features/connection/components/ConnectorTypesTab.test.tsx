import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import ConnectorTypesTab from './ConnectorTypesTab'
import { useDesktopViewport } from './connectionTestUtils'

const NEXTCLOUD_STATE = {
  sourceType: 'NEXTCLOUD',
  displayName: 'Nextcloud',
  locked: false,
  lockedAt: null,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
}

function render(route = '/admin/connections/source-types') {
  return renderWithProviders(<ConnectorTypesTab />, { withRouter: true, initialRoute: route })
}

/** Opens the side panel of a source type from its row. */
async function openType(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: `Details: ${name}` }))
  return screen.findByRole('dialog', { name })
}

/** Captures the body of every PUT to a profile requirement. */
function captureRequirementPuts(): Array<Record<string, unknown>> {
  const sent: Array<Record<string, unknown>> = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PUT' && request.url.endsWith('/profile-requirement')) {
      sent.push((await request.clone().json()) as Record<string, unknown>)
    }
  })
  return sent
}

/**
 * The „Quellarten“ tab: overview per source type, lock and „Nur über Zugänge“ in the
 * side panel.
 */
describe('ConnectorTypesTab', () => {
  useDesktopViewport()

  beforeEach(() => {
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: {
        id: 'user-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
      token: null,
      error: null,
      userManager: null,
    })
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('lists every source type with its state, its rule for profiles and its profiles', async () => {
    render()

    const table = await screen.findByRole('table', { name: 'Quellarten' })
    const nextcloud = within(table).getByRole('row', { name: /Nextcloud/ })
    expect(nextcloud).toHaveTextContent('Nutzbar')
    expect(nextcloud).toHaveTextContent('1 Zugang')
    expect(within(table).getAllByText('Keine Zugänge möglich').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Alle Quellarten sind nutzbar' })).toBeVisible()
  })

  it('links the profiles of a source type to their panel', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'Nextcloud')
    expect(within(panel).getByRole('link', { name: 'Zugang Nextcloud intern' })).toHaveAttribute(
      'href',
      '/admin/connections/access?profile=connection-profile-nextcloud',
    )
  })

  it('locks a connector type after a confirmation and sends nothing on abort', async () => {
    const sent: Array<{ type: string; locked: boolean }> = []
    server.use(
      http.put('/api/v1/admin/connector-types/:sourceType/lock', async ({ params, request }) => {
        const { locked } = (await request.json()) as { locked: boolean }
        sent.push({ type: String(params.sourceType), locked })
        return HttpResponse.json({
          sourceType: String(params.sourceType),
          displayName: 'Nextcloud',
          locked,
          lockedAt: null,
        })
      }),
    )
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'Nextcloud')
    await user.click(within(panel).getByRole('button', { name: 'Quellart Nextcloud sperren' }))
    await answerConfirm(user, /Quellart „Nextcloud“ sperren\?/, 'Abbrechen')
    expect(sent).toEqual([])

    await user.click(within(panel).getByRole('button', { name: 'Quellart Nextcloud sperren' }))
    await answerConfirm(user, /Quellart „Nextcloud“ sperren\?/, 'Sperren')
    expect(await screen.findByText('Die Quellart „Nextcloud“ ist gesperrt.')).toBeVisible()
    expect(sent).toEqual([{ type: 'NEXTCLOUD', locked: true }])
  })

  it('offers the switch only for a type that admits profiles as optional', async () => {
    const user = userEvent.setup()
    render()

    let panel = await openType(user, 'Nextcloud')
    expect(
      within(panel).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
    ).not.toBeChecked()
    await user.click(within(panel).getByRole('button', { name: 'Schließen' }))

    panel = await openType(user, 'Dateisystem')
    expect(within(panel).queryByRole('switch')).not.toBeInTheDocument()
    expect(within(panel).getByText(/keine Zugänge möglich/)).toBeVisible()
  })

  it('says why the requirement cannot be switched on, and sends nothing', async () => {
    const sent = captureRequirementPuts()
    server.use(
      http.get('/api/v1/admin/connector-types/:sourceType/profile-requirement', () =>
        HttpResponse.json({
          state: NEXTCLOUD_STATE,
          switchable: false,
          notSwitchableReason:
            'Für Nextcloud gibt es keinen nicht gesperrten Zugang, der Bibliotheken zulässt.',
          ownAddressLibraries: [],
        }),
      ),
    )
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'Nextcloud')
    await user.click(within(panel).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }))
    const dialog = await screen.findByRole('dialog', { name: /„Nur über Zugänge“ für/ })
    expect(
      await within(dialog).findByTestId('profile-requirement-not-switchable'),
    ).toHaveTextContent('keinen nicht gesperrten Zugang')
    expect(within(dialog).queryByRole('button', { name: 'Einschalten' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
    expect(sent).toEqual([])
  })

  it.each([
    ['RUNS', /Weiterlaufen lassen/],
    ['LOCKED', /Sperren – sie laufen nicht mehr/],
  ])(
    'switches the requirement on with the choice %s for the libraries it lists',
    async (stock, choice) => {
      const sent = captureRequirementPuts()
      const user = userEvent.setup()
      render()

      const panel = await openType(user, 'Nextcloud')
      await user.click(
        within(panel).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
      )
      const dialog = await screen.findByRole('dialog', { name: /„Nur über Zugänge“ für/ })
      const list = await within(dialog).findByRole('list', {
        name: 'Bibliotheken mit eigener Adresse',
      })
      expect(list).toHaveTextContent('Projektablage · Gruppe Referat 50')
      await user.click(within(dialog).getByRole('radio', { name: choice }))
      await user.click(within(dialog).getByRole('button', { name: 'Einschalten' }))

      await waitFor(() => expect(sent).toEqual([{ required: true, ownAddressStock: stock }]))
      expect(
        await screen.findByText('Die Quellart „Nextcloud“ ist jetzt nur über Zugänge nutzbar.'),
      ).toBeVisible()
      expect(
        await within(panel).findByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
      ).toBeChecked()
    },
    20000,
  )

  it('names what the requirement does not cover for an RSS feed', async () => {
    const coverage =
      'Die Pflicht legt nur die Feed-Adresse fest. Detailseiten auf anderen Servern werden weiter abgerufen, aber ohne Zugangsdaten.'
    server.use(
      http.get('/api/v1/admin/connector-types', () =>
        HttpResponse.json([
          { ...NEXTCLOUD_STATE, sourceType: 'RSS_FEED', displayName: 'RSS-Feed' },
        ]),
      ),
      http.get('/api/v1/admin/connector-types/:sourceType/profile-requirement', () =>
        HttpResponse.json({
          state: { ...NEXTCLOUD_STATE, sourceType: 'RSS_FEED', displayName: 'RSS-Feed' },
          switchable: true,
          ownAddressLibraries: [],
          coverageNotice: coverage,
        }),
      ),
    )
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'RSS-Feed')
    await user.click(within(panel).getByRole('switch', { name: 'Nur über Zugänge für RSS-Feed' }))
    const dialog = await screen.findByRole('dialog', { name: /„Nur über Zugänge“ für/ })
    expect(await within(dialog).findByTestId('profile-requirement-coverage')).toHaveTextContent(
      coverage,
    )
    expect(
      within(dialog).getByText('Keine Bibliothek dieser Quellart nutzt eine eigene Adresse.'),
    ).toBeVisible()
  })

  it('changes the choice for the stock while the requirement holds', async () => {
    const required = { ...NEXTCLOUD_STATE, profileRequired: true, ownAddressStock: 'RUNS' }
    server.use(http.get('/api/v1/admin/connector-types', () => HttpResponse.json([required])))
    const sent = captureRequirementPuts()
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'Nextcloud')
    expect(within(panel).getByText(/laufen weiter, ihre Adresse ist eingefroren/)).toBeVisible()
    await user.click(
      within(panel).getByRole('button', { name: 'Bestandswahl für Nextcloud ändern' }),
    )
    const dialog = await screen.findByRole('dialog', { name: /Bestand von Nextcloud/ })
    expect(await within(dialog).findByRole('radio', { name: /Weiterlaufen lassen/ })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: /Sperren – sie laufen nicht mehr/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Wahl speichern' }))

    await waitFor(() => expect(sent).toEqual([{ required: true, ownAddressStock: 'LOCKED' }]))
  })

  it('switches the requirement off only after a confirmation', async () => {
    const required = { ...NEXTCLOUD_STATE, profileRequired: true, ownAddressStock: 'LOCKED' }
    server.use(http.get('/api/v1/admin/connector-types', () => HttpResponse.json([required])))
    const sent = captureRequirementPuts()
    const user = userEvent.setup()
    render()

    const panel = await openType(user, 'Nextcloud')
    const toggle = within(panel).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' })
    expect(toggle).toBeChecked()
    await user.click(toggle)
    await answerConfirm(
      user,
      /„Nur über Zugänge“ für die Quellart „Nextcloud“ ausschalten\?/,
      'Abbrechen',
    )
    expect(sent).toEqual([])

    await user.click(toggle)
    const question = await screen.findByRole('dialog', { name: /ausschalten\?/ })
    expect(question).toHaveTextContent('ohne Neueinrichtung weiter')
    await answerConfirm(user, /ausschalten\?/, 'Ausschalten')
    await waitFor(() => expect(sent).toEqual([{ required: false }]))
  })

  it('names no count of profiles it could not load', async () => {
    server.use(
      http.get('/api/v1/admin/connection-profiles', () =>
        HttpResponse.json({ error: 'Interner Fehler' }, { status: 500 }),
      ),
    )
    const user = userEvent.setup()
    render()

    const table = await screen.findByRole('table', { name: 'Quellarten' })
    expect(within(table).getByRole('row', { name: /Nextcloud/ })).toHaveTextContent('nicht geladen')
    expect(within(table).queryByText('0 Zugänge')).not.toBeInTheDocument()
    const panel = await openType(user, 'Nextcloud')
    expect(panel).toHaveTextContent('Die Zugänge dieser Quellart konnten nicht geladen werden')
    expect(panel).not.toHaveTextContent('Noch kein Zugang')
  })

  it('opens the panel a link points at', async () => {
    render('/admin/connections/source-types?sourceType=NEXTCLOUD')

    expect(await screen.findByRole('dialog', { name: 'Nextcloud' })).toBeVisible()
  })
})
