import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { EmbeddingInfoResponse } from '@/shared/api/types'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import {
  mockEmbeddingChangeIndex,
  mockEmbeddingInfo,
  mockEmbeddingModelProbe,
} from '../mocks/modelFixtures'
import EmbeddingChangeSection from './EmbeddingChangeSection'

function renderSection(info: EmbeddingInfoResponse = mockEmbeddingInfo, onChanged = vi.fn()) {
  renderWithProviders(<EmbeddingChangeSection info={info} onChanged={onChanged} />, {
    withRouter: true,
  })
  return onChanged
}

function capture(method: 'post' | 'put' | 'delete', path: string, answer: unknown) {
  const sent: unknown[] = []
  const handler = method === 'post' ? http.post : method === 'put' ? http.put : http.delete
  server.use(
    handler(path, async ({ request }) => {
      sent.push(method === 'delete' ? null : await request.json())
      return HttpResponse.json(answer as Record<string, unknown>)
    }),
  )
  return sent
}

describe('EmbeddingChangeSection', () => {
  it('previews a model change and announces the interruption before anything starts', async () => {
    const user = userEvent.setup()
    const started = capture(
      'post',
      '/api/v1/admin/models/embedding/change',
      mockEmbeddingChangeIndex,
    )
    renderSection()

    const model = screen.getByLabelText(/Modell-Kennung/)
    await user.clear(model)
    await user.type(model, 'bge-m3')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))

    expect(await screen.findByLabelText('Vorschau des Wechsels')).toHaveTextContent('4.812')
    expect(screen.getByLabelText('Vorschau des Wechsels')).toHaveTextContent('etwa 40 Minuten')
    expect(
      screen.getAllByText(/Vektorsuche ausgesetzt|bisherigen Vektoren weg/).length,
    ).toBeGreaterThanOrEqual(2)
    expect(started).toHaveLength(0)
  })

  it('starts the change only after an explicit confirmation, with the size of the probe', async () => {
    const user = userEvent.setup()
    const started = capture(
      'post',
      '/api/v1/admin/models/embedding/change',
      mockEmbeddingChangeIndex,
    )
    const onChanged = renderSection()
    const model = screen.getByLabelText(/Modell-Kennung/)
    await user.clear(model)
    await user.type(model, 'bge-m3')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))
    await user.click(await screen.findByRole('button', { name: 'Wechsel starten' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Vektorsuche ausgesetzt')
    await user.click(within(dialog).getByRole('button', { name: 'Wechsel starten' }))

    await waitFor(() => expect(started).toHaveLength(1))
    expect(started[0]).toMatchObject({
      baseUrl: 'http://ollama:11434/v1',
      model: 'bge-m3',
      expectedDimensions: mockEmbeddingModelProbe.dimensions,
      apiKey: '',
    })
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('offers the endpoint change without a re-embedding for the same model', async () => {
    const user = userEvent.setup()
    const moved = capture('put', '/api/v1/admin/models/embedding/endpoint', mockEmbeddingInfo.index)
    renderSection()
    const address = screen.getByLabelText(/Basis-Adresse/)
    await user.clear(address)
    await user.type(address, 'http://ersatz:11434/v1')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))

    expect(await screen.findByText(/ein Wechsel des Endpunkts genügt/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wechsel starten' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Endpunkt ändern' }))

    await waitFor(() => expect(moved).toHaveLength(1))
    expect(moved[0]).toMatchObject({ baseUrl: 'http://ersatz:11434/v1', model: 'nomic-embed-text' })
  })

  it('keeps a stored key only for the same endpoint', async () => {
    const user = userEvent.setup()
    const probed = capture('post', '/api/v1/admin/models/embedding/probe', mockEmbeddingModelProbe)
    renderSection({ ...mockEmbeddingInfo, apiKeySet: true })

    expect(screen.getByRole('radio', { name: 'beibehalten' })).toBeChecked()
    const address = screen.getByLabelText(/Basis-Adresse/)
    await user.clear(address)
    await user.type(address, 'https://api.anderer-anbieter.example/v1')

    expect(screen.getByRole('radio', { name: 'beibehalten' })).toBeDisabled()
    expect(screen.getByText(/der gespeicherte Schlüssel geht nicht mit/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Neuer Zugangsschlüssel'), 'sk-neu')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))

    await waitFor(() => expect(probed).toHaveLength(1))
    expect(probed[0]).toMatchObject({ apiKey: 'sk-neu' })
  })

  it('omits the key while it is kept for the same endpoint', async () => {
    const user = userEvent.setup()
    const probed = capture('post', '/api/v1/admin/models/embedding/probe', mockEmbeddingModelProbe)
    renderSection({ ...mockEmbeddingInfo, apiKeySet: true })

    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))

    await waitFor(() => expect(probed).toHaveLength(1))
    expect(probed[0]).not.toHaveProperty('apiKey')
  })

  it('shows a running change with its progress and discards it after a confirmation', async () => {
    const user = userEvent.setup()
    const discarded = capture(
      'delete',
      '/api/v1/admin/models/embedding/change',
      mockEmbeddingInfo.index,
    )
    const onChanged = renderSection({ ...mockEmbeddingInfo, index: mockEmbeddingChangeIndex })

    expect(screen.getByText(/Die Vektorsuche ist ausgesetzt/)).toBeInTheDocument()
    expect(screen.getByText(/2\.406 von 4\.812 Abschnitten/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ersatz-Endpunkt eintragen' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Wechsel verwerfen' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('12 Abschnitte')
    await user.click(within(dialog).getByRole('button', { name: 'Verwerfen' }))

    await waitFor(() => expect(discarded).toHaveLength(1))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('refuses the discard while the switch is under way', () => {
    renderSection({
      ...mockEmbeddingInfo,
      index: { ...mockEmbeddingChangeIndex, switching: true },
    })

    expect(screen.getByRole('button', { name: 'Wechsel verwerfen' })).toBeDisabled()
    expect(screen.getByText(/Umschalten läuft/)).toBeInTheDocument()
  })

  it('takes a replacement endpoint for the target model of a stopped change', async () => {
    const user = userEvent.setup()
    capture('post', '/api/v1/admin/models/embedding/probe', {
      succeeded: true,
      message: 'Probeeinbettung erfolgreich.',
      dimensions: 1024,
      kind: 'ENDPOINT_CHANGE',
      similarity: 0.9997,
    })
    const moved = capture(
      'put',
      '/api/v1/admin/models/embedding/endpoint',
      mockEmbeddingChangeIndex,
    )
    renderSection({
      ...mockEmbeddingInfo,
      model: 'bge-m3',
      index: {
        ...mockEmbeddingChangeIndex,
        running: false,
        lastRun: { status: 'FAILED', kind: 'REEMBED', message: 'Endpunkt nicht erreichbar.' },
      },
    })

    await user.click(screen.getByRole('button', { name: 'Ersatz-Endpunkt eintragen' }))
    expect(screen.getByLabelText(/Modell-Kennung/)).toBeDisabled()
    const address = screen.getByLabelText(/Basis-Adresse/)
    await user.clear(address)
    await user.type(address, 'http://ersatz:11434/v1')
    await user.click(screen.getByRole('button', { name: 'Probeeinbettung' }))
    await user.click(await screen.findByRole('button', { name: 'Ersatz-Endpunkt speichern' }))

    await waitFor(() => expect(moved).toHaveLength(1))
    expect(moved[0]).toMatchObject({ model: 'bge-m3', baseUrl: 'http://ersatz:11434/v1' })
  })
})
