import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { factoryRerankModel, factoryTranscriptionModel } from '../mocks/modelRoleFixtures'
import ModelRoleSection from './ModelRoleSection'

function captureRerankSave() {
  const sent: Record<string, unknown>[] = []
  server.use(
    http.put('/api/v1/admin/models/rerank', async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>
      sent.push(body)
      return HttpResponse.json({
        ...factoryRerankModel(),
        enabled: body.enabled,
        baseUrl: body.baseUrl,
        model: body.model,
        apiKeySet: body.apiKey === undefined ? true : body.apiKey !== '',
        state: 'UNREACHABLE',
      })
    }),
  )
  return sent
}

describe('ModelRoleSection', () => {
  it('shows the factory default of the rerank role as switched off', async () => {
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    expect(await screen.findByText('Zustand: Ausgeschaltet.')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Reranking einschalten' })).not.toBeChecked()
    expect(screen.queryByLabelText('Sprache (optional)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Begriffsliste')).not.toBeInTheDocument()
  })

  it('saves the vocabulary list of the transcription role', async () => {
    const user = userEvent.setup()
    const sent: Record<string, unknown>[] = []
    server.use(
      http.put('/api/v1/admin/models/transcription', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        sent.push(body)
        return HttpResponse.json({ ...factoryTranscriptionModel(), vocabulary: body.vocabulary })
      }),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    await user.type(await screen.findByLabelText('Begriffsliste'), '  AO, BZSt ')
    expect(screen.getByText(/8 \/ 500 Zeichen/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ vocabulary: 'AO, BZSt' })
    expect(await screen.findByLabelText('Begriffsliste')).toHaveValue('AO, BZSt')
  })

  it('saves switch, endpoint and key and keeps a stored key untouched afterwards', async () => {
    const user = userEvent.setup()
    const sent = captureRerankSave()
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    await user.click(await screen.findByRole('checkbox', { name: 'Reranking einschalten' }))
    await user.type(screen.getByLabelText(/Basis-Adresse/), 'http://reranker:80')
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'bge-reranker')
    await user.type(screen.getByLabelText(/^API-Schlüssel/, { selector: 'input' }), 'sk-1')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      enabled: true,
      baseUrl: 'http://reranker:80',
      model: 'bge-reranker',
      apiKey: 'sk-1',
    })
    expect(await screen.findByText('Zustand: Wird geprüft …')).toBeInTheDocument()

    // a later change that leaves the key field alone
    await user.type(screen.getByLabelText(/Modell-Kennung/), '-v2')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).not.toHaveProperty('apiKey')
  })

  it('reads the state once more after saving instead of showing the unprobed state', async () => {
    const user = userEvent.setup()
    captureRerankSave()
    renderWithProviders(<ModelRoleSection kind="rerank" />)
    await user.click(await screen.findByRole('checkbox', { name: 'Reranking einschalten' }))
    await user.type(screen.getByLabelText(/Basis-Adresse/), 'http://reranker:80')
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'bge-reranker')
    server.use(
      http.get('/api/v1/admin/models/rerank', () =>
        HttpResponse.json({
          ...factoryRerankModel(),
          enabled: true,
          baseUrl: 'http://reranker:80',
          model: 'bge-reranker',
          state: 'ACTIVE',
        }),
      ),
    )

    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByText('Zustand: Wird geprüft …')).toBeInTheDocument()
    expect(
      await screen.findByText(
        'Zustand: Eingeschaltet, der Endpunkt antwortet.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
  })

  it('keeps the stored key when the typed key is cleared again', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/admin/models/rerank', () =>
        HttpResponse.json({ ...factoryRerankModel(), apiKeySet: true }),
      ),
    )
    const sent = captureRerankSave()
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    await user.click(await screen.findByRole('button', { name: 'API-Schlüssel ersetzen' }))
    const key = screen.getByLabelText(/^API-Schlüssel/, { selector: 'input' })
    await user.type(key, 'sk-1')
    await user.clear(key)
    // another change, so „Speichern“ sends at all
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'bge-reranker')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).not.toHaveProperty('apiKey')
  })

  it('shows the field errors of a refused save at their fields', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    await user.click(await screen.findByRole('checkbox', { name: 'Reranking einschalten' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findAllByText('Die Basis-Adresse fehlt.')).not.toHaveLength(0)
    expect(screen.getByText('Die Modell-Kennung fehlt.')).toBeInTheDocument()
  })

  it('names a former configuration variable that is no longer read', async () => {
    server.use(
      http.get('/api/v1/admin/models/transcription', () =>
        HttpResponse.json({
          ...factoryTranscriptionModel(),
          retiredVariables: ['OPAA_TRANSCRIPTION_BASE_URL', 'OPAA_TRANSCRIPTION_ENABLED'],
        }),
      ),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    expect(
      await screen.findByText(/OPAA_TRANSCRIPTION_BASE_URL, OPAA_TRANSCRIPTION_ENABLED gesetzt/),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Sprache (optional)')).toHaveValue('de')
  })

  it('keeps the switch off while the operation has switched off speech input', async () => {
    server.use(
      http.get('/api/v1/admin/models/transcription', () =>
        HttpResponse.json({ ...factoryTranscriptionModel(), speechInputAvailable: false }),
      ),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    expect(
      await screen.findByText(/Der Betrieb hat die Spracheingabe abgeschaltet/),
    ).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Spracheingabe einschalten' })).toBeDisabled()
  })

  it('tests the draft endpoint and shows the outcome', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    await user.type(await screen.findByLabelText(/Basis-Adresse/), 'http://unerreichbar:8000/v1')
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'whisper')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    expect(
      await screen.findByText('Die Verbindung wurde vom Server abgelehnt.'),
    ).toBeInTheDocument()
  })

  it('offers live transcription as a choice with a fixed list of services and their presets', async () => {
    const user = userEvent.setup()
    const sent: Record<string, unknown>[] = []
    server.use(
      http.put('/api/v1/admin/models/transcription', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        sent.push(body)
        return HttpResponse.json(factoryTranscriptionModel())
      }),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    expect(
      await screen.findByRole('checkbox', { name: 'Live-Transkript einschalten' }),
    ).not.toBeChecked()
    expect(screen.queryByLabelText(/Live-Adresse/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Live-Transkript einschalten' }))

    expect(screen.getByLabelText(/Live-Adresse/)).toHaveValue(
      'wss://api.openai.com/v1/realtime?intent=transcription',
    )
    expect(screen.getByLabelText(/Live-Modell/)).toHaveValue('gpt-live-transcribe')
    expect(screen.getByText(/laufend an einen externen Dienst/)).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /^vLLM \(eigener Server\)/ }))
    expect(screen.getByLabelText(/Live-Adresse/)).toHaveValue('ws://vllm:8000/v1/realtime')
    expect(screen.getByLabelText(/Live-Modell/)).toHaveValue(
      'mistralai/Voxtral-Mini-4B-Realtime-2602',
    )
    expect(screen.queryByText(/laufend an einen externen Dienst/)).not.toBeInTheDocument()

    const url = screen.getByLabelText(/Live-Adresse/)
    await user.clear(url)
    await user.type(url, 'ws://gpu-01:8000/v1/realtime')
    await user.click(screen.getByRole('radio', { name: /^Mistral/ }))
    expect(screen.getByLabelText(/Live-Adresse/)).toHaveValue('ws://gpu-01:8000/v1/realtime')
    expect(screen.getByLabelText(/Live-Modell/)).toHaveValue('voxtral-mini-transcribe-realtime-3')

    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].realtime).toEqual({
      enabled: true,
      protocol: 'MISTRAL',
      url: 'ws://gpu-01:8000/v1/realtime',
      model: 'voxtral-mini-transcribe-realtime-3',
      maxSessions: 1,
    })
  })

  it('asks for a live key of its own when OpenAI runs live beside a batch model elsewhere', async () => {
    const user = userEvent.setup()
    const sent: Record<string, unknown>[] = []
    server.use(
      http.get('/api/v1/admin/models/transcription', () =>
        HttpResponse.json({
          ...factoryTranscriptionModel(),
          enabled: true,
          baseUrl: 'http://speaches:8000/v1',
          model: 'whisper',
          apiKeySet: false,
        }),
      ),
      http.put('/api/v1/admin/models/transcription', async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>)
        return HttpResponse.json(factoryTranscriptionModel())
      }),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    await user.click(await screen.findByRole('checkbox', { name: 'Live-Transkript einschalten' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(
      await screen.findByText(/OpenAI und Mistral verlangen einen API-Schlüssel/),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(0)

    const key = screen.getByLabelText('Live-Schlüssel (optional)')
    await user.type(key, 'sk-live')
    expect(key).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Live-Schlüssel anzeigen' }))
    expect(key).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].realtime).toMatchObject({ protocol: 'OPENAI', apiKey: 'sk-live' })
  })

  it('switches live off again and tests a live service on its own', async () => {
    const user = userEvent.setup()
    const sent: Record<string, unknown>[] = []
    server.use(
      http.get('/api/v1/admin/models/transcription', () =>
        HttpResponse.json({
          ...factoryTranscriptionModel(),
          realtime: {
            enabled: true,
            protocol: 'VLLM',
            url: 'ws://unerreichbar:8000/v1/realtime',
            model: 'voxtral',
            apiKeySet: false,
            maxSessions: 2,
            state: 'UNREACHABLE',
          },
        }),
      ),
      http.put('/api/v1/admin/models/transcription', async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>)
        return HttpResponse.json(factoryTranscriptionModel())
      }),
    )
    renderWithProviders(<ModelRoleSection kind="transcription" />)

    expect(await screen.findByText(/der Live-Dienst antwortet noch nicht/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Gleichzeitige Live-Sitzungen/)).toHaveValue(2)
    await user.click(screen.getByRole('button', { name: 'Live-Dienst testen' }))
    expect(
      await screen.findByText('Die Verbindung wurde vom Server abgelehnt.'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Live-Transkript einschalten' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].realtime).toEqual({ enabled: false })
  })
})
