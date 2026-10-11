import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import { server } from '../../../mocks/server'
import { mockLlmModels } from '../../../mocks/modelFixtures'
import { renderWithProviders } from '../../../test/test-utils'
import LlmModelFormDialog from './LlmModelFormDialog'

/** Records the body of every call to one of the model endpoints, in order. */
function recordRequests(path: string) {
  const bodies: Array<Record<string, unknown>> = []
  const listener = async ({ request }: { request: Request }) => {
    if (new URL(request.url).pathname !== path) return
    bodies.push((await request.clone().json()) as Record<string, unknown>)
  }
  server.events.on('request:start', listener)
  onTestFinished(() => server.events.removeListener('request:start', listener))
  return bodies
}

function renderCreate() {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  renderWithProviders(
    <LlmModelFormDialog open model={null} onClose={onClose} onSaved={onSaved} />,
  )
  return { dialog: within(screen.getByRole('dialog')), onSaved, onClose }
}

describe('LlmModelFormDialog – Modellermittlung (#2488)', () => {
  it('fills the address from a template and lists the models of a keyless endpoint at once', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))

    expect(dialog.getByLabelText('Basis-Adresse', { exact: false })).toHaveValue(
      'http://ollama:11434/v1',
    )
    expect(await dialog.findByText(/4 Modelle gefunden/)).toBeInTheDocument()

    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByRole('option', { name: /qwen3:8b/ })).toBeInTheDocument()
    // Details the endpoint reported, in German.
    expect(within(listbox).getByRole('option', { name: /qwen3:8b/ })).toHaveTextContent(
      /8,2 Mrd\. Parameter.*5,2 GB.*Kontext 40\.960 Token.*Werkzeuge/,
    )
    // An embedding-only model is shown, but marked and not selectable.
    const embedding = within(listbox).getByRole('option', { name: /nomic-embed-text/ })
    expect(embedding).toHaveAttribute('aria-disabled', 'true')
    expect(within(listbox).getByText('Nicht für den Chat geeignet')).toBeInTheDocument()
  })

  it('fills identifier, display name and answer length on selection and tests the connection', async () => {
    const tests = recordRequests('/api/v1/admin/models/test')
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))
    await dialog.findByText(/4 Modelle gefunden/)
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /qwen3:8b/ }))

    expect(dialog.getByRole('combobox', { name: /Modell-Kennung/ })).toHaveValue('qwen3:8b')
    expect(dialog.getByLabelText('Anzeigename', { exact: false })).toHaveValue('qwen3:8b')
    // A quarter of the 40,960-token context window, capped at 8,192.
    expect(dialog.getByLabelText('Maximale Antwortlänge (Token)', { exact: false })).toHaveValue(
      8192,
    )
    expect(dialog.getByText(/Kontextfenster des Modells: 40\.960 Token/)).toBeInTheDocument()

    expect(await dialog.findByText(/Verbindung erfolgreich/)).toBeInTheDocument()
    expect(tests).toHaveLength(1)
    expect(tests[0]).toMatchObject({
      baseUrl: 'http://ollama:11434/v1',
      modelIdentifier: 'qwen3:8b',
    })
  })

  it('keeps a display name the person typed when a model is selected', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Hausmodell')
    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))
    await dialog.findByText(/4 Modelle gefunden/)
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /llama3\.2:3b/ }))

    expect(dialog.getByLabelText('Anzeigename', { exact: false })).toHaveValue('Hausmodell')
  })

  it('narrows the list while typing and selects by keyboard', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))
    await dialog.findByText(/4 Modelle gefunden/)
    const field = dialog.getByRole('combobox', { name: /Modell-Kennung/ })
    await user.click(field)
    await user.type(field, 'mistral')

    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getAllByRole('option')).toHaveLength(1)
    await user.keyboard('{ArrowDown}{Enter}')

    expect(field).toHaveValue('mistral-small3.2:24b')
    expect(dialog.getByLabelText('Anzeigename', { exact: false })).toHaveValue(
      'mistral-small3.2:24b',
    )
  })

  it('fetches the list on Enter in the address field and sends the typed key along', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.type(dialog.getByLabelText('API-Schlüssel (optional)'), 'sk-test')
    await user.type(
      dialog.getByLabelText('Basis-Adresse', { exact: false }),
      'https://api.openai.com/v1{Enter}',
    )

    expect(await dialog.findByText(/4 Modelle gefunden/)).toBeInTheDocument()
    expect(discoveries.at(-1)).toMatchObject({
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-test',
    })
    expect(dialog.getByRole('radio', { name: /OpenAI/ })).toHaveAttribute('aria-checked', 'true')
  })

  it('does not fetch at once for a template that needs a key, and explains why', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /OpenAI/ }))

    expect(dialog.getByText(/Zugangsschlüssel eintragen, dann „Modelle abrufen“/)).toBeInTheDocument()
    expect(discoveries).toHaveLength(0)
  })

  it('names a missing key and still allows typing the identifier by hand', async () => {
    const user = userEvent.setup()
    const { dialog, onSaved } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /OpenAI/ }))
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    expect(await dialog.findByText(/verlangt einen Zugangsschlüssel \(HTTP 401\)/)).toBeInTheDocument()
    expect(dialog.getByText(/Kennung lässt sich trotzdem von Hand eintragen/)).toBeInTheDocument()

    await user.type(dialog.getByRole('combobox', { name: /Modell-Kennung/ }), 'gpt-4o')
    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'GPT-4o')
    await user.click(dialog.getByRole('button', { name: 'Anlegen' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(mockLlmModels.some((m) => m.modelIdentifier === 'gpt-4o')).toBe(true)
  })

  it('names an unreachable address', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.type(
      dialog.getByLabelText('Basis-Adresse', { exact: false }),
      'http://unerreichbar.intern/v1',
    )
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    expect(await dialog.findByText(/Host konnte nicht gefunden werden/)).toBeInTheDocument()
    expect(dialog.getByRole('combobox', { name: /Modell-Kennung/ })).toBeEnabled()
  })

  it('names an endpoint without model list', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.type(
      dialog.getByLabelText('Basis-Adresse', { exact: false }),
      'http://ohne-liste.intern/v1',
    )
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    expect(await dialog.findByText(/bietet keine Modellliste an/)).toBeInTheDocument()
  })

  it('names an endpoint that reports no models', async () => {
    server.use(
      http.post('/api/v1/admin/models/discover', () =>
        HttpResponse.json({
          success: true,
          failure: null,
          message: 'Der Endpunkt meldet keine Modelle.',
          ollama: true,
          models: [],
        }),
      ),
    )
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))

    expect(await dialog.findByText(/meldet keine Modelle/)).toBeInTheDocument()
    expect(dialog.getByText(/ollama pull/)).toBeInTheDocument()
  })

  it('shows a rejected request as an error, for instance the stored-key rule', async () => {
    server.use(
      http.post('/api/v1/admin/models/discover', () =>
        HttpResponse.json(
          { error: 'Die angegebene Basis-Adresse weicht von der gespeicherten Adresse ab.' },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.click(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ }))

    expect(
      await dialog.findByText(/weicht von der gespeicherten Adresse ab/),
    ).toBeInTheDocument()
  })

  it('reuses the stored key of the edited model without sending one', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    renderWithProviders(
      <LlmModelFormDialog open model={mockLlmModels[0]} onClose={vi.fn()} onSaved={vi.fn()} />,
    )
    const dialog = within(screen.getByRole('dialog'))

    expect(dialog.getByRole('radio', { name: /Ollama im Compose-Stack/ })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    await dialog.findByText(/4 Modelle gefunden/)
    expect(discoveries[0]).toEqual({
      baseUrl: 'http://ollama:11434/v1',
      modelId: mockLlmModels[0].id,
    })
  })
})
