import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import {
  MOCK_INSTALLATION_TOKEN,
  mockInstallationState,
  setMockInstallationPending,
  updateMockStep,
} from '../mocks/installationFixtures'
import InstallationPage from './InstallationPage'

const PASSWORD = 'Sehr-geheimes-Passwort-42'

/** The input itself - the token form is named after its heading, which reads the same. */
function field(label: string) {
  return screen.getByLabelText(label, { selector: 'input' })
}

function queryField(label: string) {
  return screen.queryByLabelText(label, { selector: 'input' })
}

function renderPage() {
  return renderWithProviders(<InstallationPage />, {
    withRouter: true,
    initialRoute: '/installation',
  })
}

async function enterToken(user: UserEvent, token = MOCK_INSTALLATION_TOKEN) {
  await user.type(field('Token'), token)
  await user.click(screen.getByRole('button', { name: 'Token prüfen' }))
}

function stepHeading(name: string) {
  return screen.findByRole('heading', { level: 2, name })
}

async function fillFirstAdmin(user: UserEvent, email = 'it-betrieb@stadt.example') {
  await user.type(field('E-Mail-Adresse'), email)
  await user.type(field('Anzeigename'), 'Systemverwaltung')
  await user.type(field('Passwort'), PASSWORD)
  await user.type(field('Passwort wiederholen'), PASSWORD)
}

async function submitStep(user: UserEvent) {
  await user.click(screen.getByRole('button', { name: 'Prüfen und weiter' }))
}

describe('InstallationPage', () => {
  beforeEach(() => {
    setMockInstallationPending(true)
  })

  it('shows nothing of the installation before the token is accepted', async () => {
    renderPage()

    expect(await stepHeading('Installations-Token')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'OPAA einrichten' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Schritte der Einrichtung' })
    // Before the token, the list holds only the token itself and offers nothing to open.
    expect(within(nav).queryByRole('button')).not.toBeInTheDocument()
    expect(within(nav).queryByText('Erstes Konto')).not.toBeInTheDocument()
    expect(queryField('E-Mail-Adresse')).not.toBeInTheDocument()
  })

  it('refuses a wrong token at the field and stays at the token', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user, 'falsches-token')

    const tokenField = field('Token')
    await waitFor(() => expect(tokenField).toHaveAttribute('aria-invalid', 'true'))
    expect(tokenField).toHaveAccessibleDescription(/Das Installations-Token stimmt nicht/)
    expect(queryField('E-Mail-Adresse')).not.toBeInTheDocument()
  })

  it('asks for a token before sending an empty one', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await user.click(screen.getByRole('button', { name: 'Token prüfen' }))

    expect(field('Token')).toHaveAccessibleDescription('Bitte das Installations-Token eingeben.')
  })

  it('lists mandatory and optional steps in separate groups once the token is accepted', async () => {
    // The API decides what is mandatory; an optional step goes into a group of its own.
    updateMockStep('SIGN_IN', { mandatory: false })
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user)

    expect(await stepHeading('Erstes Konto')).toBeInTheDocument()
    const mandatory = screen.getByRole('list', { name: 'Pflichtschritte' })
    const optional = screen.getByRole('list', { name: 'Optionale Schritte' })
    expect(within(mandatory).getByText('Erstes Konto')).toBeInTheDocument()
    expect(within(mandatory).getByText('Chat-Modell')).toBeInTheDocument()
    expect(within(mandatory).queryByText('Anmeldeverfahren')).not.toBeInTheDocument()
    expect(within(optional).getByText('Anmeldeverfahren')).toBeInTheDocument()
    expect(screen.getByText('Schritt 2 von 5')).toBeInTheDocument()
    expect(within(mandatory).getByRole('button', { name: /Erstes Konto/ })).toHaveAttribute(
      'aria-current',
      'step',
    )

    await user.click(within(optional).getByRole('button', { name: /Anmeldeverfahren/ }))
    expect(await stepHeading('Anmeldeverfahren')).toBeInTheDocument()
    expect(screen.getByText('Optional · später unter Identitätsanbieter')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('Chat-Modell')).toBeInTheDocument()
    expect(mockInstallationState().steps.find((s) => s.step === 'SIGN_IN')?.saved).toBe(false)
  })

  it('shows no optional group while the API lists only mandatory steps', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user)

    await stepHeading('Erstes Konto')
    expect(screen.queryByRole('list', { name: 'Optionale Schritte' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Überspringen' })).not.toBeInTheDocument()
  })

  it('keeps the token out of the browser storage', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user)
    await stepHeading('Erstes Konto')

    const stored = [
      ...Object.values({ ...window.localStorage }),
      ...Object.values({ ...window.sessionStorage }),
    ]
    expect(stored.join(' ')).not.toContain(MOCK_INSTALLATION_TOKEN)
  })

  it('checks the first account locally before sending it', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')

    await user.type(field('E-Mail-Adresse'), 'admin@opaa.local')
    await user.type(field('Passwort'), PASSWORD)
    await user.type(field('Passwort wiederholen'), 'anders')
    await submitStep(user)

    expect(field('E-Mail-Adresse')).toHaveAccessibleDescription(
      /admin@opaa.local ist für das erste Konto nicht zulässig/,
    )
    expect(field('Anzeigename')).toHaveAccessibleDescription('Bitte einen Anzeigenamen angeben.')
    expect(field('Passwort wiederholen')).toHaveAccessibleDescription(
      'Die beiden Eingaben stimmen nicht überein.',
    )
    await waitFor(() => expect(field('E-Mail-Adresse')).toHaveFocus())
    expect(mockInstallationState().steps[0].saved).toBe(false)
  })

  it('shows the backend field error under its field', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')

    await user.type(field('E-Mail-Adresse'), 'it@stadt.example')
    await user.type(field('Anzeigename'), 'IT')
    await user.type(field('Passwort'), 'kurz')
    await user.type(field('Passwort wiederholen'), 'kurz')
    await submitStep(user)

    const password = field('Passwort')
    await waitFor(() =>
      expect(password).toHaveAccessibleDescription('Das Passwort braucht mindestens 12 Zeichen.'),
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Die Angaben sind unvollständig.')
  })

  it('runs through every step and completes only after all mandatory ones', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')

    await fillFirstAdmin(user)
    await submitStep(user)

    expect(await stepHeading('Anmeldeverfahren')).toBeInTheDocument()
    await submitStep(user)

    expect(await stepHeading('Chat-Modell')).toBeInTheDocument()
    await user.type(field('Basis-Adresse'), 'http://kaputt:11434/v1')
    await user.type(field('Modell-Kennung'), 'qwen2.5:7b')
    await submitStep(user)

    // A failed check keeps the entries and names the reason; the step is stored, not checked.
    expect(await screen.findByRole('alert')).toHaveTextContent('Das Modell antwortet nicht')
    const nav = screen.getByRole('navigation', { name: 'Schritte der Einrichtung' })
    expect(within(nav).getByRole('button', { name: /Chat-Modell/ })).toHaveTextContent(
      'Prüfung fehlgeschlagen',
    )
    const baseUrl = field('Basis-Adresse')
    await user.clear(baseUrl)
    await user.type(baseUrl, 'http://ollama:11434/v1')
    await submitStep(user)

    expect(await stepHeading('Abschluss')).toBeInTheDocument()
    expect(mockInstallationState().completable).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Installation abschließen' }))

    expect(await stepHeading('Installation abgeschlossen')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toHaveAttribute('href', '/login')
  })

  it('refuses the completion while a mandatory step is open and names it', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')

    const nav = screen.getByRole('navigation', { name: 'Schritte der Einrichtung' })
    await user.click(within(nav).getByRole('button', { name: /Abschluss/ }))

    const action = await screen.findByRole('button', { name: 'Installation abschließen' })
    expect(action).toHaveAttribute('aria-disabled', 'true')
    expect(action).toHaveAccessibleDescription(
      /Offen: Erstes Konto, Anmeldeverfahren, Chat-Modell\./,
    )
    await user.click(action)
    expect(screen.queryByText('Installation abgeschlossen')).not.toBeInTheDocument()
  })

  it('resumes at the first open mandatory step with the stored values and check result', async () => {
    updateMockStep('FIRST_ADMIN', {
      saved: true,
      checked: true,
      firstAdmin: { email: 'it@stadt.example', displayName: 'IT', passwordSet: true },
    })
    updateMockStep('SIGN_IN', {
      saved: true,
      checked: false,
      checkMessage: 'Die Discovery-Adresse des Anbieters ist nicht erreichbar.',
      signIn: {
        mode: 'LOCAL_AND_OIDC',
        oidc: { issuerUri: 'https://login.stadt.example/realms/opaa', clientId: 'opaa' },
      },
    })
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user)

    expect(await stepHeading('Anmeldeverfahren')).toBeInTheDocument()
    expect(field('Issuer-URI')).toHaveValue('https://login.stadt.example/realms/opaa')
    expect(screen.getByRole('radio', { name: /Lokale Konten und ein OIDC-Anbieter/ })).toBeChecked()
    expect(screen.getByRole('alert')).toHaveTextContent('Discovery-Adresse')

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(await stepHeading('Erstes Konto')).toBeInTheDocument()
    expect(field('E-Mail-Adresse')).toHaveValue('it@stadt.example')
    // The stored password is never mirrored back.
    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Passwort ersetzen' })).toBeInTheDocument()

    // Unchanged, the stored account is only checked again: the API needs the password to save.
    await submitStep(user)
    expect(await stepHeading('Anmeldeverfahren')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    await stepHeading('Erstes Konto')
    await user.type(field('Anzeigename'), ' Betrieb')
    await submitStep(user)
    expect(
      screen.getByText('Zum Ändern des Kontos bitte das Passwort erneut eingeben.'),
    ).toBeInTheDocument()
  })

  it('returns to the token when the backend no longer accepts it', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')
    server.use(
      http.put('/api/v1/installation/steps/:step', () =>
        HttpResponse.json(
          { error: 'Token ungültig', status: 401, timestamp: new Date().toISOString() },
          { status: 401 },
        ),
      ),
    )

    await fillFirstAdmin(user)
    await submitStep(user)

    expect(await stepHeading('Installations-Token')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Das Installations-Token stimmt nicht')
  })

  it('says so when the installation is already complete', async () => {
    setMockInstallationPending(false)
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')

    await enterToken(user)

    expect(await stepHeading('Bereits eingerichtet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toBeInTheDocument()
  })
})
