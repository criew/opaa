import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { mockLocalAccount } from '@/shared/mocks/authFixtures'
import { useAuthStore } from '@/shared/session/authStore'
import type { ServiceAccountResponse } from '@/shared/api/types'
import { scenarioErrorBody } from '@/shared/mocks/mockScenario'
import { server } from '../../../../test/server'
import {
  answerConfirm,
  renderWithProviders,
  setMockAuthState,
  waitForDialogClosed,
} from '../../../../test/test-utils'
import ServiceAccountsTab from './ServiceAccountsTab'

function render(route = '/admin/connections/service-accounts') {
  return renderWithProviders(<ServiceAccountsTab />, { withRouter: true, initialRoute: route })
}

function accountRows() {
  return within(screen.getByRole('list', { name: 'Dienstkonten' })).getAllByRole('listitem')
}

function generated(count: number): ServiceAccountResponse[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `sa-${i}`,
    name: i === 4 ? 'Wohngeld-Online' : `Verfahren ${i}`,
    description: null,
    active: true,
    deactivatedAt: null,
    responsibleUserId: 'owner-1',
    responsibleDisplayName: i === 6 ? 'Bob' : 'Alice',
    responsiblePersonMissing: false,
    rateLimitPerHour: null,
    effectiveRateLimitPerHour: 60,
    activeTokenCount: 1,
    consentedLibraryCount: 0,
    nextExpiryAt: null,
    createdAt: '2026-01-01T00:00:00Z',
  })) as ServiceAccountResponse[]
}

describe('ServiceAccountsTab', () => {
  it('zeigt aktive Dienstkonten mit Person und markiert eine fehlende Person', async () => {
    render()

    expect(await screen.findByText('Bauantrag-Portal')).toBeInTheDocument()
    expect(screen.getByText('Wohngeld-Online')).toBeInTheDocument()
    expect(screen.getByText('Bürgerservice-Assistent')).toBeInTheDocument()
    const wohngeld = accountRows().find((row) => row.textContent?.includes('Wohngeld-Online'))!
    expect(within(wohngeld).getByText('fehlt')).toBeInTheDocument()
    expect(screen.queryByText('Gewerbeanmeldung (Altverfahren)')).not.toBeInTheDocument()
    // three accounts stay under the threshold of guidelines 5.11: no search
    expect(screen.queryByRole('textbox', { name: 'Dienstkonten durchsuchen' })).toBeNull()
  })

  it('zeigt Deaktivierte erst über den Schalter', async () => {
    const user = userEvent.setup()
    render()

    await screen.findByText('Bauantrag-Portal')
    await user.click(screen.getByRole('switch', { name: 'Deaktivierte anzeigen (1)' }))

    const row = (await screen.findByText('Gewerbeanmeldung (Altverfahren)')).closest('li')!
    expect(within(row).getByText('Deaktiviert')).toBeInTheDocument()
  })

  it('bietet ab acht Dienstkonten eine Suche mit Zurücksetzen bei null Treffern', async () => {
    server.use(
      http.get('/api/v1/admin/service-accounts', () =>
        HttpResponse.json({ serviceAccounts: generated(8) }),
      ),
    )
    const user = userEvent.setup()
    render()

    const search = await screen.findByRole('textbox', { name: 'Dienstkonten durchsuchen' })
    expect(accountRows()).toHaveLength(8)

    await user.type(search, 'wohngeld')
    expect(accountRows()).toHaveLength(1)
    await user.clear(search)
    await user.type(search, 'bob')
    expect(accountRows().map((row) => row.textContent)).toEqual([
      expect.stringContaining('Verfahren 6'),
    ])

    await user.clear(search)
    await user.type(search, 'Grundsteuer')
    expect(screen.getByText('Keine Treffer für „Grundsteuer“.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Suche zurücksetzen' }))
    expect(accountRows()).toHaveLength(8)
  })

  it('meldet fehlende Angaben am Feld und setzt „Fehler:“ vor den Seitentitel', async () => {
    const user = userEvent.setup()
    document.title = 'Anbindungen · OPAA'
    render()

    await user.click(await screen.findByRole('button', { name: 'Dienstkonto anlegen' }))
    const dialog = await screen.findByRole('dialog', { name: 'Dienstkonto anlegen' })
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    expect(name).toHaveAccessibleDescription('Bitte einen Namen angeben.')
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveFocus()
    expect(
      within(dialog).getByRole('combobox', { name: 'Verantwortliche Person' }),
    ).toHaveAccessibleDescription('Bitte eine Person auswählen.')
    expect(document.title).toBe('Fehler: Anbindungen · OPAA')

    await user.click(within(dialog).getByRole('button', { name: 'Abbrechen' }))
    await waitForDialogClosed()
    expect(document.title).toBe('Anbindungen · OPAA')
  })

  it('führt vom Anlegen direkt zum ersten Token und zeigt dessen Wert einmal', async () => {
    const user = userEvent.setup()
    render()

    await user.click(await screen.findByRole('button', { name: 'Dienstkonto anlegen' }))
    const form = await screen.findByRole('dialog', { name: 'Dienstkonto anlegen' })
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'Kita-Platzvergabe')
    await user.type(within(form).getByRole('combobox', { name: 'Verantwortliche Person' }), 'Al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(within(form).getByRole('button', { name: 'Weiter' }))

    const tokenForm = await screen.findByRole('dialog', { name: 'Erstes Token ausstellen' })
    expect(within(tokenForm).getByText('Für „Kita-Platzvergabe“')).toBeInTheDocument()
    await user.type(within(tokenForm).getByRole('textbox', { name: 'Name' }), 'Produktion')
    await user.click(within(tokenForm).getByRole('button', { name: 'Ausstellen' }))

    const valueDialog = await screen.findByRole('dialog', { name: 'Token für Kita-Platzvergabe' })
    expect(within(valueDialog).getByTestId('service-account-token-value')).toHaveTextContent(
      /^opaa_pat_/,
    )
    expect(within(valueDialog).getByText(/Der Wert wird nur jetzt angezeigt/)).toBeInTheDocument()
    await user.click(within(valueDialog).getByRole('button', { name: 'Schließen' }))
    await waitForDialogClosed()

    expect(await screen.findByRole('heading', { name: 'Kita-Platzvergabe' })).toBeInTheDocument()
    const tokens = await screen.findByRole('list', { name: 'Tokens' })
    expect(within(tokens).getByText('Produktion')).toBeInTheDocument()
    expect(screen.queryByTestId('service-account-token-value')).toBeNull()
  })

  it('lässt das erste Token über „Abbrechen“ aus', async () => {
    const user = userEvent.setup()
    render()

    await user.click(await screen.findByRole('button', { name: 'Dienstkonto anlegen' }))
    const form = await screen.findByRole('dialog', { name: 'Dienstkonto anlegen' })
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'Kita-Platzvergabe')
    await user.type(within(form).getByRole('combobox', { name: 'Verantwortliche Person' }), 'Al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(within(form).getByRole('button', { name: 'Weiter' }))

    const tokenForm = await screen.findByRole('dialog', { name: 'Erstes Token ausstellen' })
    await user.click(within(tokenForm).getByRole('button', { name: 'Abbrechen' }))
    await waitForDialogClosed()

    expect(await screen.findByRole('heading', { name: 'Kita-Platzvergabe' })).toBeInTheDocument()
    expect(await screen.findByText('Noch kein Token.')).toBeInTheDocument()
  })

  it('öffnet ein Dienstkonto über seine Adresse und widerruft ein Token nach Rückfrage', async () => {
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    expect(await screen.findByRole('heading', { name: 'Bauantrag-Portal' })).toBeInTheDocument()
    expect(screen.getByText('600 je Stunde')).toBeInTheDocument()
    const tokens = screen.getByRole('list', { name: 'Tokens' })
    const production = within(tokens).getByText('Produktion').closest('li')!
    await user.click(within(production).getByRole('button', { name: /Produktion/ }))
    await user.click(await screen.findByRole('menuitem', { name: 'Widerrufen' }))
    await answerConfirm(user, '„Produktion“ widerrufen?', 'Widerrufen')

    await waitFor(() =>
      expect(
        within(
          within(screen.getByRole('list', { name: 'Tokens' }))
            .getByText('Produktion')
            .closest('li')!,
        ).getByText('widerrufen'),
      ).toBeInTheDocument(),
    )
  })

  it('deaktiviert ein Dienstkonto nach Rückfrage und nimmt die Aktionen weg', async () => {
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    await user.click(await screen.findByRole('button', { name: 'Dienstkonto deaktivieren' }))
    await answerConfirm(user, '„Bauantrag-Portal“ deaktivieren?', 'Deaktivieren')

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Dienstkonto deaktivieren' })).toBeNull(),
    )
    expect(screen.getByText('Deaktiviert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Token ausstellen' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Ändern/ })).toBeNull()
  })

  it('zeigt einen Ladefehler mit „Erneut laden“ und sperrt das Anlegen', async () => {
    let fail = true
    server.use(
      http.get('/api/v1/admin/service-accounts', () =>
        fail
          ? HttpResponse.json(
              scenarioErrorBody(502, 'Die Dienstkonten konnten nicht geladen werden.'),
              { status: 502 },
            )
          : HttpResponse.json({ serviceAccounts: generated(2) }),
      ),
    )
    const user = userEvent.setup()
    render()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Die Dienstkonten konnten nicht geladen werden.',
    )
    expect(screen.getByRole('button', { name: 'Dienstkonto anlegen' })).toBeDisabled()

    fail = false
    await user.click(screen.getByRole('button', { name: 'Erneut laden' }))
    expect(await screen.findByText('Verfahren 1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dienstkonto anlegen' })).toBeEnabled()
  })

  it('zeigt einen Ladefehler der Detailansicht mit „Erneut laden“', async () => {
    render('/admin/connections/service-accounts?account=sa-unbekannt')

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('button', { name: 'Erneut laden' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Dienstkonto deaktivieren' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Alle Dienstkonten' })).toBeInTheDocument()
  })

  it('bestätigt vor dem Ausstellen die Anmeldung und stellt danach einmal aus', async () => {
    let calls = 0
    server.use(
      http.post('*/api/v1/admin/service-accounts/:id/tokens', () => {
        calls += 1
        if (calls === 1) {
          return HttpResponse.json(
            { code: 'RECENT_LOGIN_REQUIRED', message: 'Bitte bestätigen Sie Ihre Anmeldung.' },
            { status: 403 },
          )
        }
        return undefined
      }),
      http.post('*/api/v1/auth/local/reauthenticate', () =>
        HttpResponse.json({ accessToken: 'recent', expiresInSeconds: 900, tokenType: 'Bearer' }),
      ),
    )
    setMockAuthState()
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    await user.click(await screen.findByRole('button', { name: 'Token ausstellen' }))
    const form = await screen.findByRole('dialog', { name: 'Token ausstellen' })
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'Ersatz')
    await user.click(within(form).getByRole('button', { name: 'Ausstellen' }))

    const confirm = await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })
    expect(within(confirm).getByText(/Token eines Dienstkontos/)).toBeInTheDocument()
    await user.keyboard(`${mockLocalAccount.password}{Enter}`)

    expect(
      await screen.findByRole('dialog', { name: 'Token für Bauantrag-Portal' }),
    ).toBeInTheDocument()
    expect(calls).toBe(2)
  })

  it('nennt eine zweite Ablehnung nach der Bestätigung im Formular', async () => {
    server.use(
      http.post('*/api/v1/admin/service-accounts/:id/tokens', () =>
        HttpResponse.json(
          { code: 'RECENT_LOGIN_REQUIRED', message: 'Bitte bestätigen Sie Ihre Anmeldung.' },
          { status: 403 },
        ),
      ),
      http.post('*/api/v1/auth/local/reauthenticate', () =>
        HttpResponse.json({ accessToken: 'recent', expiresInSeconds: 900, tokenType: 'Bearer' }),
      ),
    )
    setMockAuthState()
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    await user.click(await screen.findByRole('button', { name: 'Token ausstellen' }))
    const form = await screen.findByRole('dialog', { name: 'Token ausstellen' })
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'Ersatz')
    await user.click(within(form).getByRole('button', { name: 'Ausstellen' }))
    await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })
    await user.keyboard(`${mockLocalAccount.password}{Enter}`)

    expect(
      await within(form).findByText(
        'Bitte bestätigen Sie zuerst Ihre Anmeldung, dann versuchen Sie es erneut.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('service-account-token-value')).toBeNull()
  })

  it('ändert die Angaben eines Dienstkontos', async () => {
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    await user.click(await screen.findByRole('button', { name: /^Ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: '„Bauantrag-Portal“ bearbeiten' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    expect(name).toHaveValue('Bauantrag-Portal')
    await user.clear(name)
    await user.type(name, 'Bauantrag-Portal Nord')
    const rate = within(dialog).getByRole('textbox', { name: 'Anfragen je Stunde (optional)' })
    await user.clear(rate)
    await user.type(rate, '900')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))
    await waitForDialogClosed()

    expect(
      await screen.findByRole('heading', { name: 'Bauantrag-Portal Nord' }),
    ).toBeInTheDocument()
    expect(screen.getByText('900 je Stunde')).toBeInTheDocument()
  })

  it('zeigt einen Feldfehler des Backends ohne eigenes Feld über dem Formular', async () => {
    server.use(
      http.put('*/api/v1/admin/service-accounts/:id', () =>
        HttpResponse.json(
          {
            ...scenarioErrorBody(400, 'Die Angaben sind unvollständig.'),
            fieldErrors: [{ field: 'description', message: 'Die Beschreibung ist zu lang.' }],
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    render('/admin/connections/service-accounts?account=sa-bauantrag')

    await user.click(await screen.findByRole('button', { name: /^Ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: '„Bauantrag-Portal“ bearbeiten' })
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Die Beschreibung ist zu lang.',
    )
  })

  it('nimmt „Fehler:“ erst mit dem letzten fehlerhaften Formular aus dem Titel', async () => {
    const { useErrorTitle } = await import('./serviceAccountHooks')
    const { renderHook } = await import('@testing-library/react')
    document.title = 'Anbindungen · OPAA'
    const first = renderHook(({ on }) => useErrorTitle(on), { initialProps: { on: true } })
    const second = renderHook(({ on }) => useErrorTitle(on), { initialProps: { on: true } })
    expect(document.title).toBe('Fehler: Anbindungen · OPAA')

    first.unmount()
    expect(document.title).toBe('Fehler: Anbindungen · OPAA')
    second.rerender({ on: false })
    expect(document.title).toBe('Anbindungen · OPAA')
    second.unmount()
  })
})
