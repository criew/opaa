import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_SERVER_INSTRUCTIONS,
  mockExternalAccessSettings,
  resetMockExternalAccessSettings,
  setMockExternalAccessSettings,
} from '../mocks/externalAccessHandlers'
import { resetMockExternalAccessTokens } from '../mocks/externalAccessTokenHandlers'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '@/test/test-utils'
import ExternalToolsTab from './ExternalToolsTab'

/** The fixture's point in time in the run's time zone - otherwise the test depends on a zone. */
const FIXTURE_DATE = new Date('2026-09-18T12:00:00Z').toLocaleDateString('de-DE', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})

/** jsdom has no matchMedia; the tables render only on a desktop viewport (guidelines 5.3). */
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

const CUSTOM_REQUEST = {
  enabled: true,
  tokenMaxLifetimeDays: 45,
  tokenRateLimitPerHour: 17,
  allowedCidrs: ['10.1.0.0/16'],
  massRetrievalAlertThreshold: 333,
  serverInstructions: 'Eigener Einleitungstext.',
}
const CUSTOM_SETTINGS = { ...CUSTOM_REQUEST, updatedBy: 'Dev Admin' }

const navigateRef: { current: ((to: number | string) => void) | null } = { current: null }

function NavigationProbe() {
  const navigate = useNavigate()
  useEffect(() => {
    navigateRef.current = (to) => void (typeof to === 'number' ? navigate(to) : navigate(to))
  }, [navigate])
  return null
}

/** The tab behind another page in the history, with a handle on „Zurück" and „Vor". */
function renderInHistory() {
  return renderWithProviders(
    <MemoryRouter initialEntries={['/admin/other', '/admin/connections/external-tools']}>
      <NavigationProbe />
      <Routes>
        <Route path="/admin/other" element={<p>Vorherige Seite</p>} />
        <Route path="/admin/connections/external-tools" element={<ExternalToolsTab />} />
      </Routes>
    </MemoryRouter>,
  )
}

function render(route = '/admin/connections/external-tools') {
  return renderWithProviders(<ExternalToolsTab />, { withRouter: true, initialRoute: route })
}

async function openSettings(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Einstellungen' }))
  return screen.findByRole('dialog', { name: 'Einstellungen für externe KI-Werkzeuge' })
}

/**
 * The „Externe KI-Werkzeuge“ tab: state with emergency stop, the three keys, the two
 * lists and the settings panel with the channel settings.
 */
describe('ExternalToolsTab', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    resetMockExternalAccessSettings()
    resetMockExternalAccessTokens()
    window.matchMedia = desktopMatchMedia
  })

  afterEach(() => {
    server.events.removeAllListeners()
    window.matchMedia = originalMatchMedia
  })

  it('zeigt eine frische Installation als ausgeschaltet, mit Änderungsdatum', async () => {
    render()

    expect(await screen.findByRole('heading', { name: 'Ausgeschaltet' })).toBeVisible()
    expect(
      screen.getByText(new RegExp(`Zuletzt geändert am ${FIXTURE_DATE} durch die Installation`)),
    ).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Ausschalten' })).not.toBeInTheDocument()
  })

  it('nennt die drei Schlüssel mit ihrem Stand', async () => {
    render()

    const steps = await screen.findByRole('region', { name: 'So kommt ein Werkzeug an Inhalte' })
    const items = within(steps).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    await waitFor(() => expect(items[0]).toHaveTextContent('nicht zugelassen'))
    await waitFor(() => expect(items[1]).toHaveTextContent(/\d+ Bibliothek/))
    await waitFor(() => expect(items[2]).toHaveTextContent(/\d+ gültige? Tokens?/))
  })

  it('verweist im dritten Schritt und über der Tokenliste auf die eigenen Zugangstokens', async () => {
    render()

    const steps = await screen.findByRole('region', { name: 'So kommt ein Werkzeug an Inhalte' })
    expect(
      within(steps).getByRole('link', { name: 'Einstellungen → Zugangstokens' }),
    ).toHaveAttribute('href', '/settings/tokens')
    const tokens = screen.getByRole('region', { name: 'Zugangstokens' })
    expect(tokens).toHaveTextContent(
      'Zugangstokens erzeugt jede Person selbst unter Einstellungen → Zugangstokens. Hier sehen Sie den Bestand und können Tokens sperren.',
    )
    expect(
      within(tokens).getByRole('link', { name: 'Einstellungen → Zugangstokens' }),
    ).toHaveAttribute('href', '/settings/tokens')
  })

  it('führt die freigegebenen Bibliotheken mit Verweis auf ihre Freigaben', async () => {
    render()

    const table = await screen.findByRole('table', { name: 'Freigegebene Bibliotheken' })
    const link = within(table).getAllByRole('link')[0]
    expect(link.getAttribute('href')).toMatch(/^\/libraries\/.+\?tab=freigaben$/)
  })

  it('zeigt die Tokenliste ohne Nutzungsangaben und ohne Filter nach Person', async () => {
    render()

    expect(await screen.findByText('Claude Code (Dienst-PC)')).toBeInTheDocument()
    expect(screen.getByText(/Nutzungsangaben je Person gibt es hier nicht/)).toBeInTheDocument()
    expect(screen.queryByText(/Zuletzt benutzt/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Person|Besitzer/i)).not.toBeInTheDocument()
  })

  it('öffnet die Einstellungen mit Hausnetz, Hinweis zur Personalvertretung und Einleitungstext', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openSettings(user)
    expect(
      within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }),
    ).not.toBeChecked()
    expect(within(panel).getByText(/Gespeichert: aus — zuletzt geändert am/)).toBeVisible()
    expect(within(panel).getByText(/Beteiligung der Personalvertretung/)).toBeVisible()
    expect(within(panel).getByText(/Die Tokens bleiben erhalten/)).toBeVisible()
    expect(within(panel).getByLabelText(/höchstens gültig/)).toHaveValue(90)
    expect(within(panel).getByLabelText('Netzbereiche')).toHaveValue(
      '10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n127.0.0.0/8\n::1/128\nfc00::/7',
    )
    expect(within(panel).getByLabelText('Einleitungstext')).toHaveValue(DEFAULT_SERVER_INSTRUCTIONS)
  })

  it('öffnet die Einstellungen direkt über den Verweis mit ?panel=settings', async () => {
    render('/admin/connections/external-tools?panel=settings')

    expect(
      await screen.findByRole('dialog', { name: 'Einstellungen für externe KI-Werkzeuge' }),
    ).toBeVisible()
  })

  it('schaltet ein, speichert die geänderten Werte und zeigt danach den neuen Zustand', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openSettings(user)
    await user.click(within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }))
    const lifetime = within(panel).getByLabelText(/höchstens gültig/)
    await user.clear(lifetime)
    await user.type(lifetime, '30')
    await user.click(within(panel).getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByText(/wurden gespeichert/)).toBeInTheDocument()
    await waitForDialogClosed()
    expect(mockExternalAccessSettings.enabled).toBe(true)
    expect(mockExternalAccessSettings.tokenMaxLifetimeDays).toBe(30)
    expect(await screen.findByRole('heading', { name: 'Eingeschaltet' })).toBeVisible()
    expect(screen.getByText(/durch Dev Admin/)).toBeVisible()
  })

  it('setzt den Einleitungstext auf den Vorgabetext zurück', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openSettings(user)
    const instructions = within(panel).getByLabelText('Einleitungstext')
    await user.clear(instructions)
    await user.type(instructions, 'Eigener Text.')
    await user.click(within(panel).getByRole('button', { name: 'Vorgabetext' }))

    expect(instructions).toHaveValue(DEFAULT_SERVER_INSTRUCTIONS)
  })

  it('zeigt eine ungültige Netzangabe am Feld statt sie zu speichern', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openSettings(user)
    const cidrs = within(panel).getByLabelText('Netzbereiche')
    await user.clear(cidrs)
    await user.type(cidrs, 'stadt.example')
    await user.click(within(panel).getByRole('button', { name: 'Speichern' }))

    expect(await within(panel).findByText(/Keine gültige Netzangabe: stadt.example/)).toBeVisible()
    expect(mockExternalAccessSettings.allowedCidrs).toContain('10.0.0.0/8')
  })

  it('fragt vor dem Schließen nach, wenn eine Änderung nicht gespeichert ist', async () => {
    const user = userEvent.setup()
    render()

    const panel = await openSettings(user)
    await user.click(within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }))
    await user.click(within(panel).getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Ungespeicherte Änderungen verwerfen?', 'Weiter bearbeiten')
    expect(
      within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }),
    ).toBeChecked()

    await user.click(within(panel).getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Ungespeicherte Änderungen verwerfen?', 'Verwerfen')
    await waitForDialogClosed()
    expect(mockExternalAccessSettings.enabled).toBe(false)
  })

  it('schaltet über die Zustandszeile aus, erst nach der Rückfrage', async () => {
    // Values off the defaults: a payload built from hard-coded defaults would show here.
    setMockExternalAccessSettings({ ...mockExternalAccessSettings, ...CUSTOM_SETTINGS })
    const puts: unknown[] = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'PUT') puts.push(await request.clone().json())
    })
    const user = userEvent.setup()
    render()

    await user.click(await screen.findByRole('button', { name: 'Ausschalten' }))
    await answerConfirm(user, 'Externe KI-Werkzeuge jetzt ausschalten?', 'Abbrechen')
    expect(puts).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Ausschalten' }))
    await answerConfirm(user, 'Externe KI-Werkzeuge jetzt ausschalten?', 'Ausschalten')

    expect(await screen.findByRole('heading', { name: 'Ausgeschaltet' })).toBeVisible()
    expect(puts).toEqual([{ ...CUSTOM_REQUEST, enabled: false }])
  })

  // The switch-off replaces the settings wholesale, so it must send the
  // stored state of the moment, not the one loaded with the page.
  it('überschreibt beim Ausschalten keine zwischenzeitliche Änderung anderer', async () => {
    setMockExternalAccessSettings({ ...mockExternalAccessSettings, ...CUSTOM_SETTINGS })
    const user = userEvent.setup()
    render()
    await screen.findByRole('button', { name: 'Ausschalten' })

    // Someone else narrows the network ranges and the lifetime while the page is open.
    setMockExternalAccessSettings({
      ...mockExternalAccessSettings,
      allowedCidrs: ['10.20.0.0/24'],
      tokenMaxLifetimeDays: 7,
    })
    await user.click(screen.getByRole('button', { name: 'Ausschalten' }))
    await answerConfirm(user, 'Externe KI-Werkzeuge jetzt ausschalten?', 'Ausschalten')

    expect(await screen.findByRole('heading', { name: 'Ausgeschaltet' })).toBeVisible()
    expect(mockExternalAccessSettings.enabled).toBe(false)
    expect(mockExternalAccessSettings.allowedCidrs).toEqual(['10.20.0.0/24'])
    expect(mockExternalAccessSettings.tokenMaxLifetimeDays).toBe(7)
  })

  // Opening the panel adds no history entry, so „Zurück" leaves the tab
  // instead of silently closing the panel with its unsaved changes.
  it('legt beim Öffnen der Einstellungen keinen eigenen Verlaufseintrag an', async () => {
    const user = userEvent.setup()
    renderInHistory()

    const panel = await openSettings(user)
    await user.click(within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }))
    act(() => navigateRef.current?.(-1))

    expect(await screen.findByText('Vorherige Seite')).toBeInTheDocument()
  })

  it('fragt nach einem Schließen von außen beim nächsten Abbrechen nicht nach', async () => {
    const user = userEvent.setup()
    renderInHistory()

    let panel = await openSettings(user)
    await user.click(within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }))
    // A link to the tab without ?panel closes the panel without its own close button.
    act(() => navigateRef.current?.('/admin/connections/external-tools'))
    await waitForDialogClosed()

    panel = await openSettings(user)
    expect(
      within(panel).getByRole('switch', { name: 'Externe KI-Werkzeuge zulassen' }),
    ).not.toBeChecked()
    await user.click(within(panel).getByRole('button', { name: 'Abbrechen' }))
    await waitForDialogClosed()
    expect(
      screen.queryByRole('dialog', { name: 'Ungespeicherte Änderungen verwerfen?' }),
    ).not.toBeInTheDocument()
  })

  it('warnt, wenn der Weg eingeschaltet ist, aber kein Netzbereich eingetragen', async () => {
    setMockExternalAccessSettings({
      ...mockExternalAccessSettings,
      enabled: true,
      allowedCidrs: [],
    })
    render()

    expect(
      await screen.findByRole('heading', {
        name: 'Eingeschaltet, aber für jede Adresse geschlossen',
      }),
    ).toBeVisible()
  })

  it('zeigt einen Fehler mit erneutem Laden, wenn die Freigaben nicht geladen werden', async () => {
    server.use(
      http.get('/api/v1/admin/external-access/libraries', () =>
        HttpResponse.json({ error: 'Keine Berechtigung' }, { status: 403 }),
      ),
    )
    render()

    expect(await screen.findByRole('button', { name: 'Erneut laden' })).toBeVisible()
    expect(
      screen.queryByRole('table', { name: 'Freigegebene Bibliotheken' }),
    ).not.toBeInTheDocument()
  })
})
