import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
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

/** Chooses a template from the address suggestions. */
async function chooseTemplate(
  user: ReturnType<typeof userEvent.setup>,
  dialog: ReturnType<typeof within>,
  name: RegExp,
) {
  await user.click(dialog.getByRole('combobox', { name: /Basis-Adresse/ }))
  await user.click(await screen.findByRole('option', { name }))
}

/** Chooses the Ollama template, keeps its host and fetches the list. */
async function fetchFromOllama(
  user: ReturnType<typeof userEvent.setup>,
  dialog: ReturnType<typeof within>,
) {
  await chooseTemplate(user, dialog, /^Ollama/)
  await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))
}

function renderCreate() {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  renderWithProviders(<LlmModelFormDialog open model={null} onClose={onClose} onSaved={onSaved} />)
  return { dialog: within(screen.getByRole('dialog')), onSaved, onClose }
}

describe('LlmModelFormDialog – Modellermittlung (#2488)', () => {
  it('fills the Ollama address, selects its host for overwriting and fetches only on request', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    const address = dialog.getByRole('combobox', { name: /Basis-Adresse/ })

    await chooseTemplate(user, dialog, /^Ollama/)

    expect(address).toHaveValue('http://ollama:11434/v1')
    await waitFor(() => {
      expect(address).toHaveFocus()
      expect([
        (address as HTMLInputElement).selectionStart,
        (address as HTMLInputElement).selectionEnd,
      ]).toEqual([7, 13])
    })
    expect(discoveries).toHaveLength(0)
    expect(dialog.getByText(/Host anpassen, dann „Modelle abrufen“ oder Enter/)).toBeInTheDocument()

    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))
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

    await fetchFromOllama(user, dialog)
    await dialog.findByText(/4 Modelle gefunden/)
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /qwen3:8b/ }))

    expect(dialog.getByRole('combobox', { name: /Modell-Kennung/ })).toHaveValue('qwen3:8b')
    expect(dialog.getByLabelText('Anzeigename', { exact: false })).toHaveValue('qwen3:8b')
    // The default of 8,000 fits into half of the 40,960-token context window and stays.
    expect(dialog.getByLabelText('Maximale Antwortlänge (Token)', { exact: false })).toHaveValue(
      8000,
    )
    expect(dialog.getByText(/Kontextfenster des Modells: 40\.960 Token/)).toBeInTheDocument()

    expect(await dialog.findByText(/Verbindung erfolgreich/)).toBeInTheDocument()
    expect(tests).toHaveLength(1)
    expect(tests[0]).toMatchObject({
      baseUrl: 'http://ollama:11434/v1',
      modelIdentifier: 'qwen3:8b',
    })
  })

  it('limits the answer length to half of a small context window, unless typed by hand', async () => {
    server.use(
      http.post('/api/v1/admin/models/discover', () =>
        HttpResponse.json({
          success: true,
          failure: null,
          message: '2 Modelle gefunden.',
          ollama: true,
          models: [
            { identifier: 'phi3:mini', contextLength: 4096, capabilities: [], chatSuitable: true },
            { identifier: 'tinyllama', contextLength: 2048, capabilities: [], chatSuitable: true },
          ],
        }),
      ),
    )
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    const maxTokens = dialog.getByLabelText('Maximale Antwortlänge (Token)', { exact: false })

    await fetchFromOllama(user, dialog)
    await dialog.findByText(/2 Modelle gefunden/)
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /phi3:mini/ }))
    expect(maxTokens).toHaveValue(2048)

    await user.clear(maxTokens)
    await user.type(maxTokens, '1500')
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /tinyllama/ }))
    expect(maxTokens).toHaveValue(1500)
  })

  it('keeps a display name the person typed when a model is selected', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Hausmodell')
    await fetchFromOllama(user, dialog)
    await dialog.findByText(/4 Modelle gefunden/)
    await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
    await user.click(await screen.findByRole('option', { name: /llama3\.2:3b/ }))

    expect(dialog.getByLabelText('Anzeigename', { exact: false })).toHaveValue('Hausmodell')
  })

  it('narrows the list while typing and selects by keyboard', async () => {
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await fetchFromOllama(user, dialog)
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
  })

  it('does not fetch at once for a template that needs a key, and explains why', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()

    await chooseTemplate(user, dialog, /^OpenAI/)

    expect(
      dialog.getByText(/Zugangsschlüssel eintragen, dann „Modelle abrufen“/),
    ).toBeInTheDocument()
    expect(discoveries).toHaveLength(0)
  })

  it('names a missing key and still allows typing the identifier by hand', async () => {
    const user = userEvent.setup()
    const { dialog, onSaved } = renderCreate()

    await chooseTemplate(user, dialog, /^OpenAI/)
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    expect(
      await dialog.findByText(/verlangt einen Zugangsschlüssel \(HTTP 401\)/),
    ).toBeInTheDocument()
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

    await fetchFromOllama(user, dialog)

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

    await fetchFromOllama(user, dialog)

    expect(await dialog.findByText(/weicht von der gespeicherten Adresse ab/)).toBeInTheDocument()
  })

  it('reuses the stored key of the edited model without sending one', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    renderWithProviders(
      <LlmModelFormDialog open model={mockLlmModels[0]} onClose={vi.fn()} onSaved={vi.fn()} />,
    )
    const dialog = within(screen.getByRole('dialog'))

    expect(dialog.getByRole('combobox', { name: /Basis-Adresse/ })).toHaveValue(
      'http://ollama:11434/v1',
    )
    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))

    await dialog.findByText(/4 Modelle gefunden/)
    expect(discoveries[0]).toEqual({
      baseUrl: 'http://ollama:11434/v1',
      modelId: mockLlmModels[0].id,
    })
  })
})

const SLOW_TEST_MS = 600

/** Model A answers the connection test slowly and successfully, every other model at once. */
function slowTestForModelA() {
  server.use(
    http.post('/api/v1/admin/models/test', async ({ request }) => {
      const body = (await request.json()) as { modelIdentifier: string }
      if (body.modelIdentifier === 'llama3.2:3b') {
        await delay(SLOW_TEST_MS)
        return HttpResponse.json({ success: true, message: 'Modell A hat geantwortet.' })
      }
      return HttpResponse.json({ success: false, message: 'Modell B wurde abgelehnt.' })
    }),
  )
}

async function chooseOption(
  user: ReturnType<typeof userEvent.setup>,
  dialog: ReturnType<typeof within>,
  name: RegExp,
) {
  await user.click(dialog.getByRole('combobox', { name: /Modell-Kennung/ }))
  await user.click(await screen.findByRole('option', { name }))
}

describe('LlmModelFormDialog – Review-Befunde', () => {
  it('never lets a slower test of an earlier selection overwrite the latest one', async () => {
    slowTestForModelA()
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    await fetchFromOllama(user, dialog)
    await dialog.findByText(/4 Modelle gefunden/)

    await chooseOption(user, dialog, /llama3\.2:3b/)
    await chooseOption(user, dialog, /qwen3:8b/)
    expect(await dialog.findByText('Modell B wurde abgelehnt.')).toBeInTheDocument()

    // Long enough for model A's answer to arrive.
    await new Promise((resolve) => setTimeout(resolve, SLOW_TEST_MS + 300))
    expect(dialog.queryByText('Modell A hat geantwortet.')).not.toBeInTheDocument()
    expect(dialog.getByText('Modell B wurde abgelehnt.')).toBeInTheDocument()
  })

  it('keeps showing a running test until the latest one has finished', async () => {
    // B (older) answers after 300 ms, A (newer) after 1200 ms.
    server.use(
      http.post('/api/v1/admin/models/test', async ({ request }) => {
        const body = (await request.json()) as { modelIdentifier: string }
        if (body.modelIdentifier === 'llama3.2:3b') {
          await delay(1200)
          return HttpResponse.json({ success: true, message: 'Modell A hat geantwortet.' })
        }
        await delay(300)
        return HttpResponse.json({ success: false, message: 'Modell B wurde abgelehnt.' })
      }),
    )
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    await fetchFromOllama(user, dialog)
    await dialog.findByText(/4 Modelle gefunden/)

    await chooseOption(user, dialog, /qwen3:8b/)
    await chooseOption(user, dialog, /llama3\.2:3b/)
    await new Promise((resolve) => setTimeout(resolve, 600))

    expect(dialog.queryByText('Modell B wurde abgelehnt.')).not.toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'Verbindung wird getestet …' })).toBeInTheDocument()
    expect(
      await dialog.findByText('Modell A hat geantwortet.', {}, { timeout: 2000 }),
    ).toBeInTheDocument()
  })

  it('discards list and test result when the address changes, also during a running test', async () => {
    slowTestForModelA()
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    await fetchFromOllama(user, dialog)
    await dialog.findByText(/4 Modelle gefunden/)

    await chooseOption(user, dialog, /llama3\.2:3b/)
    await user.type(dialog.getByLabelText('Basis-Adresse', { exact: false }), 'x')

    await new Promise((resolve) => setTimeout(resolve, SLOW_TEST_MS + 300))
    expect(dialog.queryByText('Modell A hat geantwortet.')).not.toBeInTheDocument()
    expect(dialog.queryByText(/4 Modelle gefunden/)).not.toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'Verbindung testen' })).toBeInTheDocument()
  })

  it('keeps the stored answer length of an edited model when a model is selected', async () => {
    server.use(
      http.post('/api/v1/admin/models/discover', () =>
        HttpResponse.json({
          success: true,
          failure: null,
          message: '1 Modell gefunden.',
          ollama: true,
          models: [
            { identifier: 'phi3:mini', contextLength: 4096, capabilities: [], chatSuitable: true },
          ],
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <LlmModelFormDialog
        open
        model={{ ...mockLlmModels[0], maxTokens: 3000 }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    )
    const dialog = within(screen.getByRole('dialog'))

    await user.click(dialog.getByRole('button', { name: 'Modelle abrufen' }))
    await dialog.findByText(/1 Modell gefunden/)
    await chooseOption(user, dialog, /phi3:mini/)

    expect(dialog.getByLabelText('Maximale Antwortlänge (Token)', { exact: false })).toHaveValue(
      3000,
    )
  })

  it('offers the templates by keyboard: arrows, Enter, Escape', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    const address = dialog.getByRole('combobox', { name: /Basis-Adresse/ })

    await user.click(address)
    const listbox = await screen.findByRole('listbox')
    expect(
      within(listbox)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual([
      expect.stringContaining('Ollama'),
      expect.stringContaining('OpenAI'),
      expect.stringContaining('Anthropic (Claude)'),
    ])
    expect(within(listbox).getByRole('option', { name: /^Anthropic/ })).toHaveTextContent(
      /https:\/\/api\.anthropic\.com\/v1.*Temperatur leer lassen/,
    )

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{Enter}')
    expect(address).toHaveValue('https://api.openai.com/v1')
    expect(discoveries).toHaveLength(0)
  })

  it('narrows the templates while typing and keeps any other address as free text', async () => {
    const discoveries = recordRequests('/api/v1/admin/models/discover')
    const user = userEvent.setup()
    const { dialog } = renderCreate()
    const address = dialog.getByRole('combobox', { name: /Basis-Adresse/ })

    await user.type(address, 'anthro')
    expect(within(await screen.findByRole('listbox')).getAllByRole('option')).toHaveLength(1)

    await user.clear(address)
    await user.type(address, 'http://vllm.intern:8000/v1{Enter}')
    expect(address).toHaveValue('http://vllm.intern:8000/v1')
    await waitFor(() => expect(discoveries).toHaveLength(1))
    expect(discoveries[0]).toMatchObject({ baseUrl: 'http://vllm.intern:8000/v1' })
  })

  it('tests without the stored key once its removal is requested', async () => {
    const tests = recordRequests('/api/v1/admin/models/test')
    const user = userEvent.setup()
    const withKey = { ...mockLlmModels[0], apiKeySet: true }
    renderWithProviders(
      <LlmModelFormDialog open model={withKey} onClose={vi.fn()} onSaved={vi.fn()} />,
    )
    const dialog = within(screen.getByRole('dialog'))

    await user.click(
      dialog.getByRole('button', {
        name: `Gespeicherten Schlüssel von "${withKey.displayName}" entfernen`,
      }),
    )
    await user.click(dialog.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() => expect(tests).toHaveLength(1))
    expect(tests[0]).not.toHaveProperty('modelId')
  })
})
