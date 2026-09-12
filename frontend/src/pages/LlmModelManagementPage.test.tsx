import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { server } from '../mocks/server'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import LlmModelManagementPage from './LlmModelManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'user-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole,
    },
    token: null,
    error: null,
    userManager: null,
  })
}

/**
 * Die Seite liest ihren Bereich aus der Route (`/admin/models/:tab`), also montieren die Tests sie
 * unter denselben Routen wie App.tsx - einschließlich der Umleitung des bloßen Pfades.
 */
function renderPage(route = '/admin/models/chat') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/models" element={<Navigate to="/admin/models/chat" replace />} />
      <Route path="/admin/models/:tab" element={<LlmModelManagementPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

const renderChat = () => renderPage('/admin/models/chat')
const renderEmbedding = () => renderPage('/admin/models/embedding')

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

/** Die Tabellenzeile eines Modells - erkannt am Anzeigenamen, den ihre erste Spalte führt. */
function rowOf(name: string) {
  return screen.getByRole('row', { name: new RegExp(name) })
}

async function openRowMenu(user: UserEvent, name: string) {
  await user.click(within(rowOf(name)).getByRole('button', { name: `Aktionen für „${name}“` }))
  return screen.findByRole('menu')
}

/**
 * Öffnet das Formular eines Modells über „Bearbeiten" seines Zeilenmenüs - und sichert dabei zu,
 * dass der Dialog das Modell trägt, dessen Zeile ihn geöffnet hat.
 */
async function openEditDialog(user: UserEvent, name: string) {
  const menu = await openRowMenu(user, name)
  await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText(`„${name}“ bearbeiten`)).toBeInTheDocument()
  return dialog
}

/**
 * Zeichnet jeden Aufruf der Modell-Endpunkte samt Rumpf auf.
 *
 * Beim API-Schlüssel ist der **gesendete** Request der Prüfgegenstand: Die Dreiwege-Konvention von
 * `LlmModelRequest` - weggelassen heißt unverändert, leerer Text heißt entfernen, alles andere
 * heißt setzen - ist am Ergebnis allein nicht zu unterscheiden.
 */
function recordModelRequests() {
  const aufrufe: Array<{ method: string; path: string; body: Record<string, unknown> | null }> = []
  const horcher = async ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname
    if (!path.startsWith('/api/v1/admin/models')) return
    // `clone()` vor dem Lesen, damit der Rumpf des Originals für den Handler unberührt bleibt;
    // Aufrufe ohne Rumpf (GET, DELETE, Aktivierung) tragen `null`.
    const body = await request
      .clone()
      .json()
      .then((rumpf) => rumpf as Record<string, unknown>)
      .catch(() => null)
    aufrufe.push({ method: request.method, path, body })
  }
  server.events.on('request:start', horcher)
  onTestFinished(() => server.events.removeListener('request:start', horcher))
  return aufrufe
}

describe('LlmModelManagementPage', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    useLlmModelStore.setState({
      models: [],
      embeddingInfo: null,
      isLoading: false,
      error: null,
    })
  })

  /** #759 acceptance criterion: no route/entry for anyone but SYSTEM_ADMIN. */
  /**
   * Die Bereiche sind Routen, keine Zustände (#1619): Ein Verweis soll im richtigen Bereich
   * landen, und ein Neuladen ihn behalten. Der bloße Pfad landet auf den Chat-Modellen, und ein
   * Tippfehler wird nicht stillschweigend umgedeutet.
   */
  it('lands on the chat models', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage('/admin/models')

    expect(await screen.findByRole('tab', { name: 'Chat-Modelle' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('sends an unknown area back to the chat models', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage('/admin/models/quatsch')

    expect(await screen.findByRole('tab', { name: 'Chat-Modelle' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  /**
   * Jeder Bereich holt nur, was er zeigt. Vorher lud die Seite die Modellliste **und** die
   * Einbettungsangaben, obwohl immer nur eines davon sichtbar war.
   */
  it('fetches only the models in the chat area', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe: string[] = []
    const horcher = ({ request }: { request: Request }) => {
      const pfad = new URL(request.url).pathname
      if (pfad.startsWith('/api/v1/admin/models')) aufrufe.push(pfad)
    }
    server.events.on('request:start', horcher)
    onTestFinished(() => server.events.removeListener('request:start', horcher))

    renderChat()
    await screen.findByText('Ollama lokal')

    expect(aufrufe).toEqual(['/api/v1/admin/models'])
  })

  it('fetches only the embedding info in the embedding area', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe: string[] = []
    const horcher = ({ request }: { request: Request }) => {
      const pfad = new URL(request.url).pathname
      if (pfad.startsWith('/api/v1/admin/models')) aufrufe.push(pfad)
    }
    server.events.on('request:start', horcher)
    onTestFinished(() => server.events.removeListener('request:start', horcher))

    renderEmbedding()
    await screen.findByText('nomic-embed-text')

    expect(aufrufe).toEqual(['/api/v1/admin/models/embedding-info'])
  })

  it('shows no model management to a user who is not a system administrator', () => {
    signInAs('USER')

    renderChat()

    expect(screen.queryByRole('button', { name: 'Neues Modell' })).not.toBeInTheDocument()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
  })

  it('lists the configured models with the active one clearly marked', async () => {
    signInAs('SYSTEM_ADMIN')

    renderChat()

    await screen.findByRole('table', { name: 'Chat-Modelle' })
    // Der Zustand steht als Wort in der Zeile des Modells, nicht allein als Farbe.
    expect(within(rowOf('Ollama lokal')).getByText('Aktiv')).toBeInTheDocument()
  })

  /**
   * Die Tabelle ist die Übersicht (#1621): Jede Spalte ist benannt, und jede Zeile führt ihr
   * Modell mit Kennung, Endpunkt und Zugang, ohne dass etwas aufgeklappt werden muss.
   */
  /**
   * Regressionsschutz zu #958 in neuer Form: Die Seite hatte je Modell eine Überschrift, und MUI
   * setzte dort standardmäßig ein `h3` — eine übersprungene Stufe unter dem `h1` der Seite. Die
   * Überschriften sind mit der Tabelle entfallen; damit die Stufenfolge nicht später wieder
   * bricht, hält dieser Test fest, dass der Bereich außer dem Seitentitel keine trägt.
   */
  it('keeps the heading structure flat below the page title', async () => {
    signInAs('SYSTEM_ADMIN')

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })

    const stufen = screen
      .getAllByRole('heading')
      .map((h) => Number(h.tagName.slice(1)))
      .sort((a, b) => a - b)
    expect(stufen[0]).toBe(1)
    // Keine Stufe wird übersprungen: entweder gibt es nur das h1, oder die nächste ist ein h2.
    expect(stufen.every((stufe, i) => i === 0 || stufe - stufen[i - 1] <= 1)).toBe(true)
  })

  it('names its columns and carries each model in one row', async () => {
    signInAs('SYSTEM_ADMIN')

    renderChat()

    const tabelle = await screen.findByRole('table', { name: 'Chat-Modelle' })
    expect(
      within(tabelle)
        .getAllByRole('columnheader')
        .map((spalte) => spalte.textContent),
    ).toEqual(['Modell', 'Endpunkt', 'Zustand', 'Zugang', 'Aktionen'])

    const zeile = within(tabelle).getByRole('row', { name: /Ollama lokal/ })
    expect(within(zeile).getByText('phi3:mini')).toBeInTheDocument()
    expect(within(zeile).getByText('http://ollama:11434/v1')).toBeInTheDocument()
    expect(within(zeile).getByText('Ohne Schlüssel')).toBeInTheDocument()
  })

  it('shows the read-only embedding block with provider, model and dimensions', async () => {
    signInAs('SYSTEM_ADMIN')

    renderEmbedding()

    await waitFor(() => {
      expect(screen.getByText('nomic-embed-text')).toBeInTheDocument()
    })
    expect(screen.getByText('openai')).toBeInTheDocument()
    expect(screen.getByText('1536')).toBeInTheDocument()
    expect(screen.getByText(/vollständige Neuindizierung/i)).toBeInTheDocument()
  })

  it('creates a model without an API key without a validation error', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderChat()
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))

    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Neues Modell')
    await user.type(
      dialog.getByLabelText('Basis-Adresse', { exact: false }),
      'http://localhost:11434/v1',
    )
    await user.type(dialog.getByLabelText('Modell-Kennung', { exact: false }), 'llama3')
    await user.click(dialog.getByRole('button', { name: 'Anlegen' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(useLlmModelStore.getState().models.some((m) => m.displayName === 'Neues Modell')).toBe(
      true,
    )
  })

  it('activates a model, with a visible effect in the list', async () => {
    signInAs('SYSTEM_ADMIN')
    // Sets up a second, inactive model against the real mock handlers (mockLlmModels), so
    // activation below exercises the whole path - store action, POST .../activate, reload - not
    // just a hand-crafted server response.
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Modell B',
      baseUrl: 'http://b/v1',
      modelIdentifier: 'b',
      temperature: 0.7,
      maxTokens: 2000,
    })
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const menu = await openRowMenu(user, 'Modell B')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aktiv setzen' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Modell B')?.active,
      ).toBe(true)
    })
    // Sichtbar in der Liste, und zwar an beiden Zeilen: Aktiv ist immer genau ein Modell.
    await waitFor(() => {
      expect(within(rowOf('Modell B')).getByText('Aktiv')).toBeInTheDocument()
    })
    expect(within(rowOf('Ollama lokal')).getByText('Nicht aktiv')).toBeInTheDocument()
    expect(await screen.findByText(/„Modell B“ ist jetzt das aktive Modell/)).toBeInTheDocument()
  })

  /**
   * #759 review: editing must not touch the stored API key at all when the field is left alone,
   * and the field must never show a value - not even after the round trip through save/reload.
   */
  it('changes the display name and saves without touching the stored API key', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const dialog = await openEditDialog(user, 'Ollama lokal')

    const nameField = within(dialog).getByLabelText('Anzeigename', { exact: false })
    await user.clear(nameField)
    await user.type(nameField, 'Ollama umbenannt')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Ollama umbenannt'),
      ).toBeTruthy()
    })
    // Dreiwege-Konvention: Das unberührte Schlüsselfeld lässt `apiKey` im Request ganz weg - und
    // genau das heißt „unverändert".
    const gesendet = aufrufe.find((aufruf) => aufruf.method === 'PUT')!
    expect(gesendet.body).toMatchObject({ displayName: 'Ollama umbenannt' })
    expect(gesendet.body).not.toHaveProperty('apiKey')
    const saved = useLlmModelStore
      .getState()
      .models.find((m) => m.displayName === 'Ollama umbenannt')!
    expect(saved.apiKeySet).toBe(false)

    // Das Feld zeigt den gespeicherten Schlüssel auch nach dem Rundlauf durch Speichern und
    // erneutes Öffnen nicht.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const wieder = await openEditDialog(user, 'Ollama umbenannt')
    expect(within(wieder).getByLabelText('API-Schlüssel (optional)')).toHaveValue('')
  })

  /**
   * #759 review: Ein Speichern bestätigt sich sichtbar, und die Liste führt danach den neuen Wert.
   * Eine Bestätigung, die niemand zu sehen bekommt, und eine Liste, die noch den alten Namen
   * zeigt, sind genau das, was hier ausgeschlossen wird.
   */
  it('closes the dialog, confirms the save and shows the new value in the list', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const dialog = await openEditDialog(user, 'Ollama lokal')
    const nameField = within(dialog).getByLabelText('Anzeigename', { exact: false })
    await user.clear(nameField)
    await user.type(nameField, 'Ollama umbenannt')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText(/„Ollama umbenannt“ wurde gespeichert/)).toBeInTheDocument()
    await waitFor(() => expect(rowOf('Ollama umbenannt')).toBeInTheDocument())
  })

  /**
   * #759 review: removing a stored key must be reachable through an explicit action, not by
   * inferring "remove" from an untouched empty field (which is indistinguishable from "leave
   * unchanged", since the field never shows the current value either way).
   */
  it('removes a stored API key via the explicit removal action', async () => {
    signInAs('SYSTEM_ADMIN')
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Modell mit Schlüssel',
      baseUrl: 'http://c/v1',
      modelIdentifier: 'c',
      temperature: 0.7,
      maxTokens: 2000,
      apiKey: 'geheim',
    })
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    expect(
      within(rowOf('Modell mit Schlüssel')).getByText('Schlüssel hinterlegt'),
    ).toBeInTheDocument()

    const dialog = await openEditDialog(user, 'Modell mit Schlüssel')
    await user.click(
      within(dialog).getByRole('button', {
        name: 'Gespeicherten Schlüssel von "Modell mit Schlüssel" entfernen',
      }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Modell mit Schlüssel')
          ?.apiKeySet,
      ).toBe(false)
    })
    // Dreiwege-Konvention: Die ausdrückliche Anforderung sendet den leeren Text - und genau das
    // heißt „entfernen", im Unterschied zum weggelassenen Feld.
    expect(aufrufe.find((aufruf) => aufruf.method === 'PUT')!.body?.apiKey).toBe('')
    await waitFor(() => {
      expect(within(rowOf('Modell mit Schlüssel')).getByText('Ohne Schlüssel')).toBeInTheDocument()
    })
  })

  it('runs a connection test and shows the outcome', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const menu = await openRowMenu(user, 'Ollama lokal')
    await user.click(within(menu).getByRole('menuitem', { name: 'Verbindung testen' }))

    expect(await screen.findByText(/Verbindung erfolgreich/i)).toBeInTheDocument()
  })

  it('shows the API failure message on a failed connection test, form stays editable', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.post('/api/v1/admin/models/test', () =>
        HttpResponse.json({ success: false, message: 'Modell nicht erreichbar' }),
      ),
    )
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const dialog = await openEditDialog(user, 'Ollama lokal')
    await user.click(within(dialog).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(dialog).findByText('Modell nicht erreichbar')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Anzeigename', { exact: false })).toBeEnabled()
  })

  it('rejects deleting the active model client-side, with a visible reason', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const menu = await openRowMenu(user, 'Ollama lokal')

    const loeschen = within(menu).getByRole('menuitem', { name: 'Löschen' })
    expect(loeschen).toHaveAttribute('aria-disabled', 'true')
    // Der Grund steht im DOM und ist über `aria-describedby` mit dem gesperrten Eintrag verbunden,
    // wird also auch vorgelesen - ein Tooltip fände nur eine Maus.
    const grund = within(menu).getByText(/aktive Modell kann nicht gelöscht werden/i)
    expect(loeschen).toHaveAttribute('aria-describedby', grund.closest('li')!.id)

    // Der Eintrag nimmt auch keinen Klick an - `pointer-events: none`, nicht bloß ein Vermerk im
    // Namen; testing-library weist den Versuch genau deshalb ab. Es geht kein Löschaufruf hinaus.
    await expect(user.click(loeschen)).rejects.toThrow(/pointer-events/)
    expect(aufrufe.filter((aufruf) => aufruf.method === 'DELETE')).toEqual([])
    expect(
      useLlmModelStore.getState().models.find((m) => m.displayName === 'Ollama lokal'),
    ).toBeTruthy()
  })

  it('shows the API 409 message when a delete is rejected server-side', async () => {
    signInAs('SYSTEM_ADMIN')
    // An inactive model whose delete button is enabled client-side, but the server rejects anyway
    // (e.g. a concurrent activation just before this request landed) - the 409 message must still
    // reach the user rather than being swallowed.
    server.use(
      http.get('/api/v1/admin/models', () =>
        HttpResponse.json([
          {
            id: 'model-a',
            displayName: 'Modell A',
            baseUrl: 'http://a/v1',
            modelIdentifier: 'a',
            temperature: 0.7,
            maxTokens: 2000,
            apiKeySet: false,
            active: false,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
      http.delete('/api/v1/admin/models/model-a', () =>
        HttpResponse.json(
          { error: 'Das aktive Chat-Modell kann nicht gelöscht werden.' },
          { status: 409 },
        ),
      ),
    )
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const menu = await openRowMenu(user, 'Modell A')
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, /Modell „Modell A“ löschen\?/, 'Löschen')

    expect(await screen.findByText(/nicht gelöscht werden/i)).toBeInTheDocument()
  })
})
