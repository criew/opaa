import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
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
 * The page reads its area from the route (`/admin/models/:tab`), so the tests mount it
 * under the same routes as App.tsx - including the redirect of the bare path.
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

/** A model's table row - recognized by the display name its first column shows. */
function rowOf(name: string) {
  return screen.getByRole('row', { name: new RegExp(name) })
}

async function openRowMenu(user: UserEvent, name: string) {
  await user.click(
    within(rowOf(name)).getByRole('button', { name: `Weitere Aktionen für „${name}“` }),
  )
  return screen.findByRole('menu')
}

/**
 * Opens a model's form via „Bearbeiten" in its row menu - and asserts along the way
 * that the dialog carries the model whose row opened it.
 */
async function openEditDialog(user: UserEvent, name: string) {
  const menu = await openRowMenu(user, name)
  await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByText(`„${name}“ bearbeiten`)).toBeInTheDocument()
  return dialog
}

/**
 * Records every call of the model endpoints including the body.
 *
 * For the API key the **sent** request is what is checked: the three-way convention of
 * `LlmModelRequest` - omitted means unchanged, empty text means remove, anything else
 * means set - cannot be told apart from the result alone.
 */
function recordModelRequests() {
  const aufrufe: Array<{ method: string; path: string; body: Record<string, unknown> | null }> = []
  const horcher = async ({ request }: { request: Request }) => {
    const path = new URL(request.url).pathname
    if (!path.startsWith('/api/v1/admin/models')) return
    // `clone()` before reading, so that the original's body stays untouched for the handler;
    // calls without a body (GET, DELETE, activation) carry `null`.
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

  /** No route/entry for anyone but SYSTEM_ADMIN. */
  /**
   * The areas are routes, not states: a link should land in the right area,
   * and a reload should keep it. The bare path lands on the chat models, and a
   * typo is not silently reinterpreted.
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
   * Every area fetches only what it shows. Previously the page loaded the model list **and** the
   * embedding details, although only one of them was ever visible.
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
    // The state is shown as a word in the model's row, not as colour alone.
    expect(within(rowOf('Ollama lokal')).getByText('Aktiv')).toBeInTheDocument()
  })

  /**
   * The table is the overview: every column is named, and every row shows its
   * model with identifier, endpoint and access, without anything having to be expanded.
   */
  /**
   * regression guard for #958: the area carries no heading besides the page title. A
   * heading per model would by default be an `h3` in MUI — a skipped level below
   * the page's `h1`.
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
    // No level is skipped: either there is only the h1, or the next one is an h2.
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

  it('shows the embedding model as a table with provider, model and dimensions', async () => {
    signInAs('SYSTEM_ADMIN')

    renderEmbedding()

    const tabelle = await screen.findByRole('table', { name: 'Einbettungsmodell' })
    expect(
      within(tabelle)
        .getAllByRole('columnheader')
        .map((z) => z.textContent),
    ).toEqual(['Modell', 'Endpunkt', 'Dimensionen', 'Eingabegrenze', 'Schlüssel'])
    expect(within(tabelle).getByText('nomic-embed-text')).toBeInTheDocument()
    expect(within(tabelle).getByText('http://ollama:11434/v1')).toBeInTheDocument()
    expect(within(tabelle).getByText('1536')).toBeInTheDocument()
    expect(screen.getByText(/bestehenden Vektoren unvergleichbar/i)).toBeInTheDocument()
  })

  /**
   * The difference to the neighbouring tab: the embedding model is never edited in place - no
   * row menu - but changed through the probe embedding of the guided change below the table.
   */
  it('changes the embedding model only through the probe of the guided change', async () => {
    signInAs('SYSTEM_ADMIN')

    renderEmbedding()
    const tabelle = await screen.findByRole('table', { name: 'Einbettungsmodell' })

    expect(within(tabelle).queryAllByRole('button')).toEqual([])
    expect(within(tabelle).queryAllByRole('menuitem')).toEqual([])
    const panel = screen.getByRole('tabpanel')
    expect(within(panel).getByRole('button', { name: 'Probeeinbettung' })).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Wechsel starten' })).not.toBeInTheDocument()
  })

  it('sets the first embedding model of an installation without one', async () => {
    signInAs('SYSTEM_ADMIN')
    let installed = false
    let changeRequest: unknown = null
    server.use(
      http.get('/api/v1/admin/models/embedding-info', () =>
        HttpResponse.json(
          installed
            ? {
                provider: 'openai',
                model: 'bge-m3',
                dimensions: 1024,
                baseUrl: 'http://ollama:11434/v1',
                maxInputTokens: 2048,
                apiKeySet: false,
              }
            : { provider: 'openai', model: null, dimensions: 0, baseUrl: null },
        ),
      ),
      http.post('/api/v1/admin/models/embedding/probe', () =>
        HttpResponse.json({
          succeeded: true,
          message: 'Probeeinbettung erfolgreich: Das Modell liefert Vektoren mit 1024 Dimensionen.',
          dimensions: 1024,
          kind: 'INITIAL',
        }),
      ),
      http.post('/api/v1/admin/models/embedding/change', async ({ request }) => {
        changeRequest = await request.json()
        installed = true
        return HttpResponse.json({
          current: { model: 'bge-m3', dimensions: 1024, generation: 2 },
          configured: { model: 'bge-m3', dimensions: 1024, generation: 2 },
          stale: false,
          running: false,
          totalChunks: 0,
          embeddedChunks: 0,
          privateLibrariesCounted: true,
          switching: false,
        })
      }),
    )
    const user = userEvent.setup()

    renderEmbedding()

    expect(await screen.findByText(/noch kein Embedding-Modell eingerichtet/)).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Einbettungsmodell' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Embedding-Modell festlegen' })).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Basis-Adresse/), 'http://ollama:11434/v1')
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'bge-m3')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))
    await user.click(await screen.findByRole('button', { name: 'Embedding-Modell festlegen' }))
    await answerConfirm(user, /als Embedding-Modell festlegen/, 'Festlegen')

    expect(await screen.findByRole('table', { name: 'Einbettungsmodell' })).toBeInTheDocument()
    expect(changeRequest).toMatchObject({
      baseUrl: 'http://ollama:11434/v1',
      model: 'bge-m3',
      expectedDimensions: 1024,
      apiKey: '',
    })
  })

  it('says that no chat model is active while none is', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(http.get('/api/v1/admin/models', () => HttpResponse.json([])))

    renderChat()

    expect(await screen.findByText(/noch kein Chat-Modell aktiv/)).toBeInTheDocument()
  })

  it('marks "Neues Modell" with the plus icon', () => {
    signInAs('SYSTEM_ADMIN')
    renderChat()

    const create = screen.getByRole('button', { name: 'Neues Modell' })
    expect(within(create).getByTestId('AddIcon')).toBeInTheDocument()
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

  // Some models reject any temperature - „keine Angabe“ is the default.
  it('creates a model without a temperature by default', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByLabelText('Temperatur (optional)')).toHaveValue(null)

    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Haiku')
    await user.type(dialog.getByLabelText('Basis-Adresse', { exact: false }), 'https://api/v1')
    await user.type(dialog.getByLabelText('Modell-Kennung', { exact: false }), 'claude-haiku')
    await user.click(dialog.getByRole('button', { name: 'Anlegen' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const anlegen = aufrufe.find((aufruf) => aufruf.method === 'POST')
    expect(anlegen?.body?.temperature).toBeNull()
    expect(anlegen?.body?.maxTokens).toBe(8000)
  })

  it('sends the entered temperature with the connection test', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))
    await user.type(dialog.getByLabelText('Basis-Adresse', { exact: false }), 'https://api/v1')
    await user.type(dialog.getByLabelText('Modell-Kennung', { exact: false }), 'claude-haiku')
    await user.type(dialog.getByLabelText('Temperatur (optional)'), '0.2')
    await user.click(dialog.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(aufrufe.find((aufruf) => aufruf.path.endsWith('/test'))?.body?.temperature).toBe(0.2),
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
    // Visible in the list, on both rows: exactly one model is always active.
    await waitFor(() => {
      expect(within(rowOf('Modell B')).getByText('Aktiv')).toBeInTheDocument()
    })
    expect(within(rowOf('Ollama lokal')).getByText('Nicht aktiv')).toBeInTheDocument()
    expect(await screen.findByText(/„Modell B“ ist jetzt das aktive Modell/)).toBeInTheDocument()
  })

  /**
   * Editing must not touch the stored API key at all when the field is left alone,
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
    // Three-way convention: the untouched key field omits `apiKey` from the request entirely - and
    // exactly that means „unverändert".
    const gesendet = aufrufe.find((aufruf) => aufruf.method === 'PUT')!
    expect(gesendet.body).toMatchObject({ displayName: 'Ollama umbenannt' })
    expect(gesendet.body).not.toHaveProperty('apiKey')
    const saved = useLlmModelStore
      .getState()
      .models.find((m) => m.displayName === 'Ollama umbenannt')!
    expect(saved.apiKeySet).toBe(false)

    // The field does not show the stored key even after the round trip through saving and
    // reopening.
    await waitForDialogClosed()
    const wieder = await openEditDialog(user, 'Ollama umbenannt')
    expect(within(wieder).getByLabelText('API-Schlüssel (optional)')).toHaveValue('')
  })

  /**
   * A save confirms itself visibly, and the list shows the new value afterwards.
   * A confirmation nobody gets to see and a list that still shows the old name
   * are exactly what is ruled out here.
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
   * Removing a stored key must be reachable through an explicit action, not by
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
    // Three-way convention: the explicit request sends the empty text - and exactly that
    // means „entfernen", unlike the omitted field.
    expect(aufrufe.find((aufruf) => aufruf.method === 'PUT')!.body?.apiKey).toBe('')
    await waitFor(() => {
      expect(within(rowOf('Modell mit Schlüssel')).getByText('Ohne Schlüssel')).toBeInTheDocument()
    })
  })

  it('clears a stored temperature by emptying the field', async () => {
    signInAs('SYSTEM_ADMIN')
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Modell mit Temperatur',
      baseUrl: 'http://t/v1',
      modelIdentifier: 't',
      temperature: 0.7,
      maxTokens: 8000,
    })
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await screen.findByRole('table', { name: 'Chat-Modelle' })
    const dialog = await openEditDialog(user, 'Modell mit Temperatur')
    const feld = within(dialog).getByLabelText('Temperatur (optional)')
    expect(feld).toHaveValue(0.7)
    await user.clear(feld)
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(aufrufe.some((aufruf) => aufruf.method === 'PUT')).toBe(true))
    expect(aufrufe.find((aufruf) => aufruf.method === 'PUT')!.body?.temperature).toBeNull()
  })

  // The browser reports an unreadable number as an empty value with `validity.badInput`; it must
  // not be silently saved as „keine Temperatur“. jsdom does not know badInput, hence it is set.
  it('blocks saving an unreadable temperature instead of storing none', async () => {
    signInAs('SYSTEM_ADMIN')
    const aufrufe = recordModelRequests()
    const user = userEvent.setup()

    renderChat()
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))
    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Haiku')
    await user.type(dialog.getByLabelText('Basis-Adresse', { exact: false }), 'https://api/v1')
    await user.type(dialog.getByLabelText('Modell-Kennung', { exact: false }), 'claude-haiku')
    const feld = dialog.getByLabelText('Temperatur (optional)') as HTMLInputElement
    // A value first, so that React reports the following change to „leer“ as a change.
    await user.type(feld, '1')
    Object.defineProperty(feld, 'validity', { value: { badInput: true }, configurable: true })
    fireEvent.change(feld, { target: { value: '' } })

    expect(dialog.getByText(/keine gültige Zahl/)).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'Anlegen' })).toBeDisabled()
    expect(aufrufe.filter((aufruf) => aufruf.method === 'POST')).toEqual([])
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
    // The reason is in the DOM and connected to the disabled entry via `aria-describedby`,
    // so it is read out as well - a tooltip would only be found by a mouse.
    const grund = within(menu).getByText(/aktive Modell kann nicht gelöscht werden/i)
    expect(loeschen).toHaveAttribute('aria-describedby', grund.closest('li')!.id)

    // The entry accepts no click either - `pointer-events: none`, not just a note in the
    // name; testing-library rejects the attempt for exactly that reason. No delete call goes out.
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
