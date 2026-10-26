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
    expect(screen.getByRole('switch', { name: 'Reranking einschalten' })).not.toBeChecked()
    expect(screen.queryByLabelText('Sprache')).not.toBeInTheDocument()
  })

  it('saves switch, endpoint and key and keeps a stored key untouched afterwards', async () => {
    const user = userEvent.setup()
    const sent = captureRerankSave()
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    await user.click(await screen.findByRole('switch', { name: 'Reranking einschalten' }))
    await user.type(screen.getByLabelText(/Basis-Adresse/), 'http://reranker:80')
    await user.type(screen.getByLabelText(/Modell-Kennung/), 'bge-reranker')
    await user.type(screen.getByLabelText(/Zugangsschlüssel/), 'sk-1')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      enabled: true,
      baseUrl: 'http://reranker:80',
      model: 'bge-reranker',
      apiKey: 'sk-1',
    })
    expect(
      await screen.findByText('Zustand: Eingeschaltet, aber der Endpunkt antwortet noch nicht.'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).not.toHaveProperty('apiKey')
  })

  it('shows the field errors of a refused save at their fields', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ModelRoleSection kind="rerank" />)

    await user.click(await screen.findByRole('switch', { name: 'Reranking einschalten' }))
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
    expect(screen.getByLabelText('Sprache')).toHaveValue('de')
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
    expect(screen.getByRole('switch', { name: 'Spracheingabe einschalten' })).toBeDisabled()
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
})
