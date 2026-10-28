import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'
import { BRANDING_TEXTS } from '@/shared/ui/branding/brandingSettings'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import {
  MOCK_INSTALLATION_TOKEN,
  mockDiscoveryRequests,
  mockInstallationState,
  setMockInstallationPending,
  mockSkippedSteps,
  mockTestRecipients,
  setMockPublicBaseUrlConfigured,
  updateMockStep,
  withMockBrandingStep,
  withMockEmbeddingStep,
  withMockRerankStep,
  withoutMockStep,
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

async function openFromStepList(user: UserEvent, label: string) {
  const nav = screen.getByRole('navigation', { name: 'Schritte der Einrichtung' })
  await user.click(within(nav).getByRole('button', { name: new RegExp(label) }))
  await stepHeading(label)
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
    expect(within(optional).getByText('Chat-Modell')).toBeInTheDocument()
    expect(within(mandatory).queryByText('Anmeldeverfahren')).not.toBeInTheDocument()
    expect(within(optional).getByText('Anmeldeverfahren')).toBeInTheDocument()
    expect(screen.getByText('Schritt 2 von 6')).toBeInTheDocument()
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

  describe('skipping an optional step', () => {
    async function openOptionalSignIn(user: UserEvent) {
      updateMockStep('SIGN_IN', { mandatory: false })
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      await stepHeading('Erstes Konto')
      const optional = screen.getByRole('list', { name: 'Optionale Schritte' })
      await user.click(within(optional).getByRole('button', { name: /Anmeldeverfahren/ }))
      await stepHeading('Anmeldeverfahren')
    }

    it('names a refused skip at the step and keeps it open', async () => {
      server.use(
        http.post('/api/v1/installation/steps/:step/skip', () =>
          HttpResponse.json(
            { error: 'Zu viele Anfragen', status: 429, timestamp: new Date().toISOString() },
            { status: 429 },
          ),
        ),
      )
      const user = userEvent.setup()
      await openOptionalSignIn(user)

      await user.click(screen.getByRole('button', { name: 'Überspringen' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Zu viele Anfragen oder Fehlversuche von dieser Adresse',
      )
      expect(screen.getByRole('heading', { level: 2, name: 'Anmeldeverfahren' })).toBeVisible()
    })

    it('sends one skip although the button is pressed twice', async () => {
      let skips = 0
      let release: () => void = () => {}
      server.use(
        http.post('/api/v1/installation/steps/:step/skip', async () => {
          skips += 1
          await new Promise<void>((resolve) => {
            release = resolve
          })
          updateMockStep('SIGN_IN', { saved: false, checked: false })
          return HttpResponse.json(mockInstallationState().steps.find((s) => s.step === 'SIGN_IN'))
        }),
      )
      const user = userEvent.setup()
      await openOptionalSignIn(user)

      const skip = screen.getByRole('button', { name: 'Überspringen' })
      await user.click(skip)
      await waitFor(() => expect(skips).toBe(1))
      expect(skip).toHaveAttribute('aria-busy', 'true')
      await user.click(skip)
      release()

      expect(await stepHeading('Chat-Modell')).toBeInTheDocument()
      expect(skips).toBe(1)
    })
  })

  it('shows no optional group while the API lists only mandatory steps', async () => {
    updateMockStep('CHAT_MODEL', { mandatory: true })
    withoutMockStep('MAIL')
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

    // The optional mail step is skipped through the API, which records it.
    expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('Abschluss')).toBeInTheDocument()
    expect(mockSkippedSteps()).toEqual(['MAIL'])
    expect(screen.getByText(/Der E-Mail-Versand bleibt aus/)).toBeInTheDocument()
    expect(mockInstallationState().completable).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Installation abschließen' }))

    expect(await stepHeading('Installation abgeschlossen')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur Anmeldung' })).toHaveAttribute('href', '/login')
    // Every step of the assistant in one test, the optional one included: above the default limit.
  }, 15_000)

  it('completes without the model steps once they are skipped', async () => {
    withMockEmbeddingStep()
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Erstes Konto')
    await fillFirstAdmin(user)
    await submitStep(user)
    await stepHeading('Anmeldeverfahren')
    await submitStep(user)

    expect(await stepHeading('Chat-Modell')).toBeInTheDocument()
    expect(screen.getByText('Optional · später unter Modelle')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('Embedding-Modell')).toBeInTheDocument()
    expect(screen.getByText('Optional · später unter Modelle')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('Rerank-Modell')).toBeInTheDocument()
    expect(screen.getByText('Optional · später unter Modelle')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))
    expect(await stepHeading('Branding')).toBeInTheDocument()
    expect(screen.getByText('Optional · später unter Branding')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Überspringen' }))

    expect(await stepHeading('Abschluss')).toBeInTheDocument()
    const summary = screen.getByRole('list', { name: 'Stand der Schritte' })
    expect(within(summary).getAllByText('Nicht eingerichtet')).toHaveLength(5)
    expect(screen.getByText(/Übersprungen: Chat-Modell, Embedding-Modell\./)).toBeInTheDocument()
    expect(screen.getByText(/Das Reranking bleibt aus/)).toBeInTheDocument()
    expect(screen.getByText(/Der E-Mail-Versand bleibt aus/)).toBeInTheDocument()
    expect(screen.getByText(/Es gilt das Standard-Branding von OPAA/)).toBeInTheDocument()
    expect(mockSkippedSteps()).toEqual([
      'CHAT_MODEL',
      'EMBEDDING_MODEL',
      'RERANK_MODEL',
      'MAIL',
      'BRANDING',
    ])
    await user.click(screen.getByRole('button', { name: 'Installation abschließen' }))

    expect(await stepHeading('Installation abgeschlossen')).toBeInTheDocument()
  })

  it('discards a model step whose check failed when it is skipped', async () => {
    withoutMockStep('MAIL')
    updateMockStep('FIRST_ADMIN', { saved: true, checked: true })
    updateMockStep('SIGN_IN', { saved: true, checked: true })
    updateMockStep('CHAT_MODEL', {
      saved: true,
      checked: false,
      checkMessage: 'Das Modell antwortet nicht: Verbindung abgelehnt.',
      chatModel: {
        baseUrl: 'http://kaputt:11434/v1',
        model: 'qwen2.5:7b',
        apiKeySet: false,
        maxTokens: 8000,
      },
    })
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    expect(await stepHeading('Chat-Modell')).toBeInTheDocument()
    expect(mockInstallationState().completable).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Überspringen' }))

    expect(await stepHeading('Abschluss')).toBeInTheDocument()
    expect(mockInstallationState().steps.find((s) => s.step === 'CHAT_MODEL')?.saved).toBe(false)
    expect(mockInstallationState().completable).toBe(true)
    expect(screen.getByRole('button', { name: 'Installation abschließen' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    )
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
    expect(action).toHaveAccessibleDescription(/Offen: Erstes Konto, Anmeldeverfahren\./)
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
    const replace = screen.getByRole('button', { name: 'Passwort ersetzen' })
    expect(replace).toHaveAccessibleDescription(
      'Zum Ändern des Kontos bitte das Passwort erneut eingeben.',
    )
    await waitFor(() => expect(replace).toHaveFocus())
  })

  it('removes a stored API key only on request', async () => {
    updateMockStep('FIRST_ADMIN', { saved: true, checked: true })
    updateMockStep('SIGN_IN', { saved: true, checked: true })
    updateMockStep('CHAT_MODEL', {
      saved: true,
      checked: false,
      chatModel: {
        baseUrl: 'http://ollama:11434/v1',
        model: 'qwen2.5:7b',
        apiKeySet: true,
        maxTokens: 8000,
      },
    })
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    await enterToken(user)
    await stepHeading('Chat-Modell')

    await user.click(screen.getByRole('button', { name: 'Gespeicherten Schlüssel entfernen' }))
    expect(
      screen.getByText('Der gespeicherte Schlüssel wird beim Speichern entfernt.'),
    ).toBeInTheDocument()
    await submitStep(user)

    await stepHeading('E-Mail-Versand')
    const chatModel = mockInstallationState().steps.find((s) => s.step === 'CHAT_MODEL')
    expect(chatModel?.chatModel?.apiKeySet).toBe(false)
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

  it('shows a backend field error without a field of its own above the form', async () => {
    const user = userEvent.setup()
    renderPage()
    await stepHeading('Installations-Token')
    updateMockStep('FIRST_ADMIN', { saved: true, checked: true })
    await enterToken(user)
    await stepHeading('Anmeldeverfahren')
    server.use(
      http.put('/api/v1/installation/steps/:step', () =>
        HttpResponse.json(
          {
            error: 'Die Angaben sind ungültig.',
            status: 400,
            timestamp: new Date().toISOString(),
            fieldErrors: [
              {
                field: 'signIn.mode',
                code: 'REQUIRED',
                message: 'Bitte ein Anmeldeverfahren wählen.',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    )

    await submitStep(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Die Angaben sind ungültig. Bitte ein Anmeldeverfahren wählen.',
    )
  })

  describe('choosing the chat model from the endpoint', () => {
    beforeEach(() => {
      for (const step of ['FIRST_ADMIN', 'SIGN_IN'] as const) {
        updateMockStep(step, { saved: true, checked: true })
      }
    })

    async function openChatModelStep(user: UserEvent) {
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      // Every mandatory step is checked: the assistant resumes at the summary, the optional
      // chat model step stays reachable from the step list.
      await stepHeading('Abschluss')
      await openFromStepList(user, 'Chat-Modell')
    }

    function modelField() {
      return screen.getByRole('combobox', { name: 'Modell-Kennung' })
    }

    it('asks for provider, address and key before the choice of the model', async () => {
      await openChatModelStep(userEvent.setup())

      const order = [
        screen.getByRole('radiogroup', { name: 'Anbieter' }),
        field('Basis-Adresse'),
        screen.getByLabelText(/API-Schlüssel/, { selector: 'input' }),
        modelField(),
      ]
      for (let i = 1; i < order.length; i++) {
        expect(
          order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy()
      }
    })

    it('fetches the models of the chosen endpoint and offers them for selection', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.click(screen.getByRole('radio', { name: /Ollama/ }))
      expect(field('Basis-Adresse')).toHaveValue('http://ollama:11434/v1')
      // Choosing a provider fetches nothing; leaving the address does.
      expect(mockDiscoveryRequests()).toHaveLength(0)
      await user.click(field('Basis-Adresse'))
      await user.tab()

      expect(await screen.findByText(/4 Modelle gefunden\./)).toBeInTheDocument()
      await user.click(modelField())
      const listbox = await screen.findByRole('listbox')
      expect(within(listbox).getByRole('option', { name: /nomic-embed-text/ })).toHaveAttribute(
        'aria-disabled',
        'true',
      )
      await user.click(within(listbox).getByRole('option', { name: /qwen3:8b/ }))
      expect(modelField()).toHaveValue('qwen3:8b')
      await submitStep(user)

      expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
      const chatModel = mockInstallationState().steps.find((s) => s.step === 'CHAT_MODEL')
      expect(chatModel?.chatModel).toMatchObject({
        baseUrl: 'http://ollama:11434/v1',
        model: 'qwen3:8b',
      })
    })

    it('keeps the input by hand when the endpoint offers no list and says why', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.type(field('Basis-Adresse'), 'http://ohne-liste:8000/v1')
      await user.tab()

      expect(await screen.findByText(/bietet keine Modellliste an/)).toBeInTheDocument()
      await user.type(modelField(), 'mein-modell')
      await submitStep(user)

      expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
      const chatModel = mockInstallationState().steps.find((s) => s.step === 'CHAT_MODEL')
      expect(chatModel?.chatModel?.model).toBe('mein-modell')
    })

    it('names an unreachable endpoint and leaves the identifier to be typed', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.type(field('Basis-Adresse'), 'http://kaputt:11434/v1')
      await user.tab()

      const status = await screen.findByText(/Host konnte nicht gefunden werden/)
      expect(status.closest('[role="status"]')).toHaveTextContent(
        'Die Kennung lässt sich trotzdem von Hand eintragen.',
      )
      expect(modelField()).toBeEnabled()
    })

    // regression guard: a typed key never follows the address to another provider
    it('never sends a typed key to the provider chosen next', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.click(screen.getByRole('radio', { name: /^OpenAI/ }))
      const key = screen.getByLabelText(/API-Schlüssel/, { selector: 'input' })
      await user.type(key, 'sk-fuer-openai')
      await user.click(screen.getByRole('radio', { name: /Anthropic/ }))
      await user.keyboard('{ArrowLeft}')

      expect(field('Basis-Adresse')).toHaveValue('https://api.openai.com/v1')
      expect(key).toHaveValue('')
      const leaked = mockDiscoveryRequests().filter(
        (request) =>
          request.apiKey === 'sk-fuer-openai' && !request.baseUrl.includes('api.openai.com'),
      )
      expect(leaked).toEqual([])
    })

    // regression guard: a typed key does not follow a hand-edited address on its own
    it('does not fetch on its own with a typed key once the address moved elsewhere', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.type(field('Basis-Adresse'), 'http://vllm:8000/v1')
      await user.type(screen.getByLabelText(/API-Schlüssel/, { selector: 'input' }), 'sk-fuer-vllm')
      const address = field('Basis-Adresse')
      await user.clear(address)
      await user.type(address, 'http://anderer:8000/v1')
      await user.tab()

      expect(
        mockDiscoveryRequests().filter((request) => request.baseUrl.includes('anderer')),
      ).toEqual([])
      expect(screen.getByText(/für eine andere Adresse eingegeben/)).toBeInTheDocument()
    })

    it('lets the arrow keys move through the providers without fetching or leaving', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)
      const ollama = screen.getByRole('radio', { name: /Ollama/ })
      ollama.focus()

      // Nothing is chosen yet: the first arrow chooses the first tile, Ollama.
      await user.keyboard('{ArrowRight}')
      await user.keyboard('{ArrowRight}')
      await user.keyboard('{ArrowLeft}')
      await new Promise((resolve) => setTimeout(resolve, 20))

      expect(ollama).toHaveFocus()
      expect(ollama).toHaveAttribute('aria-checked', 'true')
      expect(field('Basis-Adresse')).toHaveValue('http://ollama:11434/v1')
      expect(mockDiscoveryRequests()).toHaveLength(0)
    })

    it('selects the Ollama host for overwriting once the address field has the focus', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.click(screen.getByRole('radio', { name: /Ollama/ }))
      const address = field('Basis-Adresse') as HTMLInputElement
      address.focus()

      await waitFor(() => {
        expect(address.value.slice(address.selectionStart!, address.selectionEnd!)).toBe('ollama')
      })
    })

    it('forgets the provider once the address is cleared', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.click(screen.getByRole('radio', { name: /Ollama/ }))
      await user.clear(field('Basis-Adresse'))

      for (const radio of screen.getAllByRole('radio')) {
        expect(radio).toHaveAttribute('aria-checked', 'false')
      }
      expect(field('Basis-Adresse')).toHaveAccessibleDescription(/So, wie das Backend/)
    })

    it('names the limit of discoveries and keeps the input by hand', async () => {
      server.use(
        http.post('/api/v1/installation/steps/:step/discover', () =>
          HttpResponse.json(
            {
              error: 'Zu viele Modellabrufe',
              status: 429,
              code: 'MODEL_DISCOVERY_LIMIT',
              timestamp: new Date().toISOString(),
            },
            { status: 429, headers: { 'Retry-After': '120' } },
          ),
        ),
      )
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.type(field('Basis-Adresse'), 'http://vllm:8000/v1')
      await user.tab()

      expect(await screen.findByText(/Zu viele Modellabrufe/)).toHaveTextContent(
        /2 Minuten.*von Hand/,
      )
    })

    it('names the per-address budget of the installation API as such', async () => {
      server.use(
        http.post('/api/v1/installation/steps/:step/discover', () =>
          HttpResponse.json(
            { error: 'Zu viele Anfragen', status: 429, timestamp: new Date().toISOString() },
            { status: 429, headers: { 'Retry-After': '120' } },
          ),
        ),
      )
      const user = userEvent.setup()
      await openChatModelStep(user)

      await user.type(field('Basis-Adresse'), 'http://vllm:8000/v1')
      await user.tab()

      expect(await screen.findByText(/Zu viele Anfragen oder Fehlversuche/)).toBeInTheDocument()
      expect(screen.queryByText(/Zu viele Modellabrufe/)).not.toBeInTheDocument()
    })

    it('announces the outcome in one lasting status region', async () => {
      const user = userEvent.setup()
      await openChatModelStep(user)
      const region = screen
        .getByText(/Anbieter wählen oder Basis-Adresse eintragen/)
        .closest('[role="status"]')

      await user.type(field('Basis-Adresse'), 'http://kaputt:11434/v1')
      await user.tab()

      await waitFor(() => expect(region).toHaveTextContent(/Host konnte nicht gefunden/))
      expect(region).toBeInTheDocument()
    })

    it('uses the stored key only for its own address', async () => {
      updateMockStep('CHAT_MODEL', {
        saved: true,
        checked: false,
        chatModel: {
          baseUrl: 'https://api.openai.com/v1',
          model: 'gpt-4o',
          apiKeySet: true,
          maxTokens: 8000,
        },
      })
      const user = userEvent.setup()
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      // A saved optional step whose check is owed is where the assistant resumes.
      await stepHeading('Chat-Modell')

      // Reopened with its stored endpoint, the step fetches the list with the stored key.
      expect(await screen.findByText(/Modelle gefunden\./)).toBeInTheDocument()
      expect(mockDiscoveryRequests().at(-1)).toMatchObject({
        step: 'CHAT_MODEL',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: undefined,
      })

      const baseUrl = field('Basis-Adresse')
      await user.clear(baseUrl)
      await user.type(baseUrl, 'http://ollama:11434/v1')
      await user.tab()
      const requests = mockDiscoveryRequests().length
      expect(screen.getByText(/weicht von der gespeicherten ab/)).toBeInTheDocument()
      expect(mockDiscoveryRequests()).toHaveLength(requests)

      await user.click(screen.getByRole('button', { name: 'Gespeicherten Schlüssel entfernen' }))
      await user.click(screen.getByRole('button', { name: 'Modelle abrufen' }))
      expect(await screen.findByText(/4 Modelle gefunden\./)).toBeInTheDocument()
      expect(mockDiscoveryRequests().at(-1)).toMatchObject({
        baseUrl: 'http://ollama:11434/v1',
        apiKey: '',
      })
    })
  })

  describe('with the embedding model step', () => {
    beforeEach(() => {
      withMockEmbeddingStep()
      for (const step of ['FIRST_ADMIN', 'SIGN_IN', 'CHAT_MODEL'] as const) {
        updateMockStep(step, { saved: true, checked: true })
      }
    })

    it('lists the step as soon as the API does and shows the probed vector size', async () => {
      const user = userEvent.setup()
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)

      await stepHeading('Abschluss')
      const optional = screen.getByRole('list', { name: 'Optionale Schritte' })
      expect(within(optional).getByText('Embedding-Modell')).toBeInTheDocument()
      await openFromStepList(user, 'Embedding-Modell')

      await user.type(field('Basis-Adresse'), 'http://kaputt:11434/v1')
      await user.type(field('Modell-Kennung'), 'bge-m3')
      await submitStep(user)
      expect(await screen.findByRole('alert')).toHaveTextContent('Das Modell antwortet nicht')

      const baseUrl = field('Basis-Adresse')
      await user.clear(baseUrl)
      await user.type(baseUrl, 'http://ollama:11434/v1')
      await user.type(field('Eingabegrenze (Token) (optional)'), '8192')
      await submitStep(user)

      expect(await stepHeading('Rerank-Modell')).toBeInTheDocument()
      const embedding = mockInstallationState().steps.find((s) => s.step === 'EMBEDDING_MODEL')
      expect(embedding?.embeddingModel).toMatchObject({ maxInputTokens: 8192, dimensions: 1024 })

      await user.click(screen.getByRole('button', { name: 'Zurück' }))
      expect(await stepHeading('Embedding-Modell')).toBeInTheDocument()
      expect(screen.getByText('Geprüft: 1024 Dimensionen')).toBeInTheDocument()
    })

    it('offers the embedding models of the endpoint first', async () => {
      const user = userEvent.setup()
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      await stepHeading('Abschluss')
      await openFromStepList(user, 'Embedding-Modell')

      const providers = screen.getByRole('radiogroup', { name: 'Anbieter' })
      expect(within(providers).queryByRole('radio', { name: /Anthropic/ })).not.toBeInTheDocument()
      await user.click(within(providers).getByRole('radio', { name: /Ollama/ }))
      await user.click(field('Basis-Adresse'))
      await user.tab()
      await screen.findByText(/Modelle gefunden\./)
      await user.click(screen.getByRole('combobox', { name: 'Modell-Kennung' }))

      const listbox = await screen.findByRole('listbox')
      const suitable = within(listbox).getByRole('group', { name: 'Für die Einbettung geeignet' })
      expect(within(suitable).getByRole('option', { name: /nomic-embed-text/ })).toBeInTheDocument()
      expect(mockDiscoveryRequests().at(-1)).toMatchObject({ step: 'EMBEDDING_MODEL' })
    })
  })

  describe('with the optional mail step', () => {
    beforeEach(() => {
      for (const step of ['FIRST_ADMIN', 'SIGN_IN', 'CHAT_MODEL'] as const) {
        updateMockStep(step, { saved: true, checked: true })
      }
    })

    async function openMailStep(user: UserEvent) {
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      await stepHeading('Abschluss')
      const nav = screen.getByRole('navigation', { name: 'Schritte der Einrichtung' })
      await user.click(within(nav).getByRole('button', { name: /E-Mail-Versand/ }))
      return stepHeading('E-Mail-Versand')
    }

    it('says it is optional and names the missing fields with the sentences of the administration', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      expect(screen.getByText('Optional · später unter E-Mail')).toBeInTheDocument()
      expect(
        within(screen.getByRole('list', { name: 'Optionale Schritte' })).getByText(
          'Nicht eingerichtet',
        ),
      ).toBeInTheDocument()
      await submitStep(user)

      expect(field('Server')).toHaveAccessibleDescription(
        'Ohne SMTP-Server kann der Versand nicht eingeschaltet werden',
      )
      expect(field('Port')).toHaveAccessibleDescription(
        'Ohne Port kann der Versand nicht eingeschaltet werden',
      )
      expect(field('Absenderadresse')).toHaveAccessibleDescription(
        'Ohne Absenderadresse kann der Versand nicht eingeschaltet werden',
      )
      expect(mockInstallationState().steps.find((s) => s.step === 'MAIL')?.saved).toBe(false)
    })

    it('checks the server, sends the test message only to the address typed and is taken over', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.stadt.example')
      await user.type(field('Port'), '587')
      await user.type(field('Benutzername (optional)'), 'opaa')
      await user.type(field('Passwort (optional)'), 'Mail-Passwort')
      await user.type(field('Absenderadresse'), 'opaa@stadt.example')
      await user.type(field('Testnachricht an (optional)'), 'it-betrieb@stadt.example')
      await submitStep(user)

      expect(await stepHeading('Abschluss')).toBeInTheDocument()
      expect(mockTestRecipients()).toEqual(['it-betrieb@stadt.example'])
      const mail = mockInstallationState().steps.find((s) => s.step === 'MAIL')
      expect(mail).toMatchObject({ checked: true })
      expect(mail?.mail).toMatchObject({
        host: 'smtp.stadt.example',
        port: 587,
        encryption: 'STARTTLS',
        passwordSet: true,
      })
      expect(
        screen.getByText(/E-Mail-Versand wird mit dem geprüften Mailserver eingeschaltet/),
      ).toBeInTheDocument()
      expect(
        within(screen.getByRole('list', { name: 'Stand der Schritte' })).getByText(
          'Verbindung und Anmeldung beim Mailserver sind gelungen. Die Testnachricht wurde dem Mailserver übergeben.',
        ),
      ).toBeInTheDocument()
    })

    it('says at the check that it sends nothing without an address, and where it sends with one', async () => {
      const user = userEvent.setup()
      await openMailStep(user)
      const check = screen.getByRole('button', { name: 'Prüfen und weiter' })
      await user.type(field('Benutzername (optional)'), 'opaa')

      expect(check).toHaveAccessibleDescription(
        'Ohne Adresse unter „Testnachricht an“ prüft der Schritt nur Verbindung und Anmeldung und sendet keine Nachricht.',
      )
      await user.type(field('Testnachricht an (optional)'), 'it-betrieb@stadt.example')

      expect(check).toHaveAccessibleDescription(
        'Die Prüfung sendet eine Testnachricht an it-betrieb@stadt.example.',
      )
    })

    it('names only the connection at the check while no user name is set', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      expect(screen.getByRole('button', { name: 'Prüfen und weiter' })).toHaveAccessibleDescription(
        'Ohne Adresse unter „Testnachricht an“ prüft der Schritt nur die Verbindung und sendet keine Nachricht.',
      )
    })

    it('announces no recipient at the check while the address is not valid', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Testnachricht an (optional)'), 'it-betrieb')

      expect(screen.getByRole('button', { name: 'Prüfen und weiter' })).toHaveAccessibleDescription(
        'Die Testnachricht braucht eine gültige Adresse.',
      )
    })

    it('says after a check without an address that no test message was sent', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.stadt.example')
      await user.type(field('Port'), '587')
      await user.type(field('Benutzername (optional)'), 'opaa')
      await user.type(field('Passwort (optional)'), 'Mail-Passwort')
      await user.type(field('Absenderadresse'), 'opaa@stadt.example')
      await submitStep(user)

      expect(await stepHeading('Abschluss')).toBeInTheDocument()
      expect(mockTestRecipients()).toEqual([])
      expect(
        within(screen.getByRole('list', { name: 'Stand der Schritte' })).getByText(
          'Verbindung und Anmeldung beim Mailserver sind gelungen. Es wurde keine Testnachricht gesendet.',
        ),
      ).toBeInTheDocument()
    })

    it('holds up the completion after a failed check until the step is skipped', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.kaputt.example')
      await user.type(field('Port'), '25')
      await user.type(field('Absenderadresse'), 'opaa@stadt.example')
      await submitStep(user)

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Die Verbindung zum Mailserver ist fehlgeschlagen',
      )
      expect(mockInstallationState().completable).toBe(false)
      await user.click(screen.getByRole('button', { name: 'Überspringen' }))

      expect(await stepHeading('Abschluss')).toBeInTheDocument()
      expect(mockSkippedSteps()).toEqual(['MAIL'])
      expect(mockInstallationState().completable).toBe(true)
    })

    it('asks for the password again once a stored one would go to another server', async () => {
      updateMockStep('MAIL', {
        saved: true,
        checked: true,
        mail: {
          host: 'smtp.stadt.example',
          port: 587,
          encryption: 'STARTTLS',
          username: 'opaa',
          passwordSet: true,
          fromAddress: 'opaa@stadt.example',
        },
      })
      const user = userEvent.setup()
      await openMailStep(user)

      expect(screen.getByRole('button', { name: 'Passwort ersetzen' })).toBeInTheDocument()
      const host = field('Server')
      await user.clear(host)
      await user.type(host, 'smtp.anderes.example')
      await submitStep(user)

      const replace = screen.getByRole('button', { name: 'Passwort ersetzen' })
      expect(replace).toHaveAccessibleDescription(
        'Server, Port, Benutzername oder Verschlüsselung ändern sich: Bitte das Passwort neu eingeben oder löschen.',
      )
      expect(mockInstallationState().steps.find((s) => s.step === 'MAIL')?.mail?.host).toBe(
        'smtp.stadt.example',
      )
    })

    it('names the budget of test messages and says the check works without one', async () => {
      server.use(
        http.post('/api/v1/installation/steps/MAIL/check', () =>
          HttpResponse.json(
            {
              error: 'Zu viele Testnachrichten.',
              status: 429,
              code: 'MAIL_TEST_LIMIT',
              timestamp: new Date().toISOString(),
            },
            { status: 429, headers: { 'Retry-After': '120' } },
          ),
        ),
      )
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.stadt.example')
      await user.type(field('Port'), '587')
      await user.type(field('Absenderadresse'), 'opaa@stadt.example')
      await user.type(field('Testnachricht an (optional)'), 'it-betrieb@stadt.example')
      await submitStep(user)

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Zu viele Testnachrichten. Bitte in etwa 2 Minuten erneut versuchen; ohne Testnachricht lässt sich der Mailserver weiter prüfen.',
      )
    })

    it('names the budget of mail checks as such', async () => {
      server.use(
        http.post('/api/v1/installation/steps/MAIL/check', () =>
          HttpResponse.json(
            {
              error: 'Zu viele Prüfungen.',
              status: 429,
              code: 'MAIL_CHECK_LIMIT',
              timestamp: new Date().toISOString(),
            },
            { status: 429 },
          ),
        ),
      )
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.stadt.example')
      await user.type(field('Port'), '587')
      await user.type(field('Absenderadresse'), 'opaa@stadt.example')
      await submitStep(user)

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Zu viele Prüfungen des Mailservers. Bitte in einigen Minuten erneut versuchen.',
      )
    })

    it('takes one mailbox as test recipient, never a list', async () => {
      const user = userEvent.setup()
      await openMailStep(user)

      await user.type(field('Server'), 'smtp.stadt.example')
      await user.type(field('Port'), '587')
      await user.type(field('Absenderadresse'), 'a@b.c,d.e.f')
      await user.type(field('Testnachricht an (optional)'), 'a@b.c,d.e.f')
      await submitStep(user)

      expect(field('Absenderadresse')).toHaveAccessibleDescription(
        'Die Absenderadresse muss eine E-Mail-Adresse sein, zum Beispiel opaa@example.org',
      )
      expect(field('Testnachricht an (optional)')).toHaveAttribute('aria-invalid', 'true')
      expect(mockTestRecipients()).toEqual([])
    })

    it('says that links in mails stay unusable without the public address', async () => {
      setMockPublicBaseUrlConfigured(false)
      const user = userEvent.setup()
      await openMailStep(user)

      expect(screen.getByText('Öffentliche Adresse fehlt')).toBeInTheDocument()
    })
  })

  describe('with the optional rerank step', () => {
    beforeEach(() => {
      withMockRerankStep()
      for (const step of ['FIRST_ADMIN', 'SIGN_IN', 'CHAT_MODEL'] as const) {
        updateMockStep(step, { saved: true, checked: true })
      }
    })

    async function openRerankStep(user: UserEvent) {
      renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      await stepHeading('Abschluss')
      await openFromStepList(user, 'Rerank-Modell')
    }

    function rerankOf() {
      return mockInstallationState().steps.find((s) => s.step === 'RERANK_MODEL')
    }

    it('starts at the address, offers the rerankers of the endpoint first and is taken over', async () => {
      const user = userEvent.setup()
      await openRerankStep(user)

      expect(screen.getByText('Optional · später unter Modelle')).toBeInTheDocument()
      // No preset serves a rerank endpoint: the step asks for the address alone.
      expect(screen.queryByRole('radiogroup', { name: 'Anbieter' })).not.toBeInTheDocument()
      expect(field('Basis-Adresse')).toHaveAccessibleDescription(
        'So, wie das Backend den Dienst erreicht. Ohne den Pfad /rerank, etwa http://reranker:80.',
      )
      await user.type(field('Basis-Adresse'), 'http://reranker:80')
      await user.tab()
      expect(await screen.findByText(/2 Modelle gefunden\./)).toBeInTheDocument()
      expect(mockDiscoveryRequests().at(-1)).toMatchObject({
        step: 'RERANK_MODEL',
        baseUrl: 'http://reranker:80',
      })

      await user.click(screen.getByRole('combobox', { name: 'Modell-Kennung' }))
      const listbox = await screen.findByRole('listbox')
      const suitable = within(listbox).getByRole('group', { name: 'Für das Reranking geeignet' })
      await user.click(within(suitable).getByRole('option', { name: /bge-reranker-v2-m3/ }))
      await submitStep(user)

      expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
      expect(rerankOf()).toMatchObject({
        checked: true,
        rerankModel: {
          baseUrl: 'http://reranker:80',
          model: 'BAAI/bge-reranker-v2-m3',
          apiKeySet: false,
        },
      })
      await openFromStepList(user, 'Abschluss')
      expect(
        screen.getByText(/Das Reranking wird mit dem geprüften Rerank-Modell eingeschaltet/),
      ).toBeInTheDocument()
    })

    it('takes the identifier by hand where the endpoint offers no list', async () => {
      const user = userEvent.setup()
      await openRerankStep(user)

      await user.type(field('Basis-Adresse'), 'http://reranker-ohne-liste:80')
      await user.tab()
      expect(await screen.findByText(/bietet keine Modellliste an/)).toBeInTheDocument()
      await user.type(screen.getByRole('combobox', { name: 'Modell-Kennung' }), 'jina-reranker')
      await submitStep(user)

      expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
      expect(rerankOf()?.rerankModel?.model).toBe('jina-reranker')
    })

    it('names a failed probe at the step, and skipping it leaves reranking off', async () => {
      const user = userEvent.setup()
      await openRerankStep(user)

      await user.type(field('Basis-Adresse'), 'http://kaputt:80')
      await user.type(screen.getByRole('combobox', { name: 'Modell-Kennung' }), 'bge-reranker')
      await submitStep(user)

      expect(await screen.findByRole('alert')).toHaveTextContent('Das Modell antwortet nicht')
      expect(mockInstallationState().completable).toBe(false)
      await user.click(screen.getByRole('button', { name: 'Überspringen' }))

      expect(await stepHeading('E-Mail-Versand')).toBeInTheDocument()
      expect(mockSkippedSteps()).toEqual(['RERANK_MODEL'])
      expect(rerankOf()).toMatchObject({ saved: false, checked: false })
      await openFromStepList(user, 'Abschluss')
      expect(screen.getByText(/Das Reranking bleibt aus/)).toBeInTheDocument()
    })

    it('asks for the address before sending anything', async () => {
      const user = userEvent.setup()
      await openRerankStep(user)

      expect(
        screen.getByText(/^Basis-Adresse eintragen; dann ruft der Assistent die Modelle ab/),
      ).toBeInTheDocument()
      await submitStep(user)

      expect(field('Basis-Adresse')).toHaveAttribute('aria-invalid', 'true')
      expect(rerankOf()?.saved).toBe(false)
      expect(mockDiscoveryRequests()).toHaveLength(0)
    })
  })

  describe('with the optional branding step', () => {
    beforeEach(() => {
      withMockBrandingStep()
      for (const step of ['FIRST_ADMIN', 'SIGN_IN', 'CHAT_MODEL'] as const) {
        updateMockStep(step, { saved: true, checked: true })
      }
    })

    async function openBrandingStep(user: UserEvent) {
      const rendered = renderPage()
      await stepHeading('Installations-Token')
      await enterToken(user)
      await stepHeading('Abschluss')
      await openFromStepList(user, 'Branding')
      return rendered
    }

    function brandingOf() {
      return mockInstallationState().steps.find((s) => s.step === 'BRANDING')
    }

    it('offers the fields and hints of the administration and keeps the standard when left alone', async () => {
      const user = userEvent.setup()
      await openBrandingStep(user)

      expect(screen.getByText('Optional · später unter Branding')).toBeInTheDocument()
      expect(screen.getByText(/der Assistent nimmt keine Bilder an/)).toBeInTheDocument()
      expect(field('Produktname (optional)')).toHaveAccessibleDescription(
        BRANDING_TEXTS.productNameHint,
      )
      expect(screen.getByLabelText('Hinweis zur KI-Nutzung')).toHaveValue(
        OPAA_BRANDING.aiNoticeDefault,
      )
      expect(screen.getByRole('radio', { name: 'System' })).toBeChecked()
      await submitStep(user)

      expect(await stepHeading('Abschluss')).toBeInTheDocument()
      expect(brandingOf()).toMatchObject({
        checked: true,
        branding: {
          productName: null,
          claim: null,
          aiNotice: null,
          primaryColor: null,
        },
      })
      expect(brandingOf()?.branding?.defaultColorScheme).toBeUndefined()
      expect(screen.getByText(/Das Branding wird übernommen/)).toBeInTheDocument()
    })

    it('refuses a colour that is no hex value before sending it', async () => {
      const user = userEvent.setup()
      await openBrandingStep(user)

      await user.type(field('Primärfarbe (optional)'), 'blau')
      await submitStep(user)

      expect(field('Primärfarbe (optional)')).toHaveAccessibleDescription(
        BRANDING_TEXTS.primaryColorInvalid,
      )
      expect(brandingOf()?.saved).toBe(false)
    })

    it('warns about the contrast without blocking, previews both schemes and is taken over', async () => {
      const user = userEvent.setup()
      const { container } = await openBrandingStep(user)

      await user.type(field('Produktname (optional)'), 'Landesamt-Assistent')
      await user.type(field('Primärfarbe (optional)'), '#FFEB3B')
      await user.click(screen.getByRole('radio', { name: 'Dunkel' }))

      expect(screen.getByText('Kontrast unterschritten')).toBeInTheDocument()
      const previews = container.querySelectorAll('figure[aria-hidden="true"]')
      expect(previews).toHaveLength(2)
      for (const preview of previews) {
        expect(preview).toHaveTextContent('Landesamt-Assistent')
      }
      await submitStep(user)

      expect(await stepHeading('Abschluss')).toBeInTheDocument()
      expect(brandingOf()?.branding).toMatchObject({
        productName: 'Landesamt-Assistent',
        primaryColor: '#FFEB3B',
        defaultColorScheme: 'DARK',
      })
    })

    it('takes the colour of the picker into the field', async () => {
      const user = userEvent.setup()
      await openBrandingStep(user)

      fireEvent.change(screen.getByLabelText('Primärfarbe auswählen'), {
        target: { value: '#7a3e9d' },
      })

      expect(field('Primärfarbe (optional)')).toHaveValue('#7A3E9D')
    })

    it('shows a backend field error of the step under its field', async () => {
      server.use(
        http.put('/api/v1/installation/steps/BRANDING', () =>
          HttpResponse.json(
            {
              error: 'Die Angaben des Schritts sind unvollständig.',
              status: 400,
              fieldErrors: [
                {
                  field: 'branding.productName',
                  code: 'INVALID',
                  message: 'Der Produktname darf keine Steuerzeichen enthalten',
                },
              ],
            },
            { status: 400 },
          ),
        ),
      )
      const user = userEvent.setup()
      await openBrandingStep(user)

      await user.type(field('Produktname (optional)'), 'Amt')
      await submitStep(user)

      await waitFor(() =>
        expect(field('Produktname (optional)')).toHaveAccessibleDescription(
          'Der Produktname darf keine Steuerzeichen enthalten',
        ),
      )
    })
  })
})
