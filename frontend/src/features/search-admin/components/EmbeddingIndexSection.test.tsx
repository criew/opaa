import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { mockEmbeddingIndexStale } from '@/features/library/mocks/indexingFixtures'
import { renderWithProviders } from '@/test/test-utils'
import type { EmbeddingReindexStatusResponse } from '@/shared/api/types'
import EmbeddingIndexSection from './EmbeddingIndexSection'

const ENDPOINT = '/api/v1/admin/indexing/embedding-reindex'

const running: EmbeddingReindexStatusResponse = {
  ...mockEmbeddingIndexStale,
  running: true,
  lastRun: { status: 'RUNNING', startedAt: '2026-10-13T09:00:00Z' },
}

describe('EmbeddingIndexSection', () => {
  it('shows an index of the configured model as current, without any action', async () => {
    renderWithProviders(<EmbeddingIndexSection />)

    expect(await screen.findByText('Index aktuell')).toBeInTheDocument()
    expect(screen.getByText(/nomic-embed-text \(768 Dimensionen\)/)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('names both models of a stale index, the progress and that the search is limited', async () => {
    server.use(http.get(ENDPOINT, () => HttpResponse.json(mockEmbeddingIndexStale)))

    renderWithProviders(<EmbeddingIndexSection />)

    expect(await screen.findByText('Index veraltet')).toBeInTheDocument()
    expect(screen.getByText(/stammt von nomic-embed-text \(768 Dimensionen\)/)).toBeInTheDocument()
    expect(screen.getByText(/konfiguriert ist bge-m3 \(1024 Dimensionen\)/)).toBeInTheDocument()
    expect(screen.getByText(/Vektorsuche ausgesetzt/)).toBeInTheDocument()
    expect(
      screen.getByText(
        /1\.237 von 2\.474 Abschnitten neu eingebettet \(50 %\), ohne private Bibliotheken/,
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('progressbar', { name: 'Fortschritt der Neueinbettung' }),
    ).toHaveAttribute('aria-valuenow', '50')
    expect(screen.getByText('abgebrochen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neueinbettung fortsetzen' })).toBeInTheDocument()
  })

  it('starts the re-embedding and offers to stop it while it runs', async () => {
    let started = false
    server.use(
      http.get(ENDPOINT, () => HttpResponse.json(started ? running : mockEmbeddingIndexStale)),
      http.post(ENDPOINT, () => {
        started = true
        return HttpResponse.json(running)
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<EmbeddingIndexSection />)

    await user.click(await screen.findByRole('button', { name: 'Neueinbettung fortsetzen' }))

    expect(started).toBe(true)
    expect(
      await screen.findByRole('button', { name: 'Neueinbettung abbrechen' }),
    ).toBeInTheDocument()
    expect(screen.getByText('läuft')).toBeInTheDocument()
  })

  it('stops a running re-embedding', async () => {
    let cancelled = false
    server.use(
      http.get(ENDPOINT, () => HttpResponse.json(running)),
      http.delete(ENDPOINT, () => {
        cancelled = true
        return HttpResponse.json(mockEmbeddingIndexStale)
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<EmbeddingIndexSection />)

    await user.click(await screen.findByRole('button', { name: 'Neueinbettung abbrechen' }))

    await waitFor(() => expect(cancelled).toBe(true))
    expect(
      await screen.findByRole('button', { name: 'Neueinbettung fortsetzen' }),
    ).toBeInTheDocument()
  })

  it('shows the refusal of the server', async () => {
    server.use(
      http.get(ENDPOINT, () =>
        HttpResponse.json({ ...mockEmbeddingIndexStale, embeddedChunks: 0 }),
      ),
      http.post(ENDPOINT, () =>
        HttpResponse.json({ error: 'Die Neueinbettung läuft bereits.' }, { status: 409 }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<EmbeddingIndexSection />)

    await user.click(await screen.findByRole('button', { name: 'Neueinbettung starten' }))

    expect(await screen.findByText('Die Neueinbettung läuft bereits.')).toBeInTheDocument()
  })

  it('resumes an unfinished refill after a discard', async () => {
    let resumed = false
    const unfinished: EmbeddingReindexStatusResponse = {
      ...mockEmbeddingIndexStale,
      stale: false,
      running: false,
      configured: mockEmbeddingIndexStale.current,
      withoutCurrentVector: 3,
      lastRun: { status: 'FAILED', kind: 'REFILL', message: 'Unvollständig.' },
    }
    server.use(
      http.get(ENDPOINT, () => HttpResponse.json(unfinished)),
      http.post(ENDPOINT, () => {
        resumed = true
        return HttpResponse.json({
          ...unfinished,
          running: true,
          lastRun: { status: 'RUNNING', kind: 'REFILL' },
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<EmbeddingIndexSection />)

    expect(await screen.findByText('Letztes Nachfüllen')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Nachfüllen fortsetzen' }))

    await waitFor(() => expect(resumed).toBe(true))
    expect(await screen.findByRole('button', { name: 'Nachfüllen abbrechen' })).toBeInTheDocument()
  })
})
