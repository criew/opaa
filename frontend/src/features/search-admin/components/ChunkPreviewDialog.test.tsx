import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import ChunkPreviewDialog from './ChunkPreviewDialog'

function chunk(metadata: Record<string, unknown>) {
  return {
    chunkId: 'chunk-scan',
    documentId: 'doc-scan',
    documentTitle: 'satzung-scan.pdf',
    libraryId: 'lib-1',
    libraryName: 'Satzungen',
    chunkIndex: 4,
    content: 'Nr. | Gegenstand | Gebühr\n4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
    metadata,
  }
}

describe('ChunkPreviewDialog', () => {
  it('marks a chunk whose text came from text recognition', async () => {
    server.use(
      http.get('/api/v1/admin/search/chunks/:chunkId', () =>
        HttpResponse.json(
          chunk({
            anchor: {
              pageFrom: 2,
              regions: [{ page: 2, x: 0.1, y: 0.3, width: 0.8, height: 0.04 }],
              textOrigin: { connectionType: 'DOCLING_SERVE' },
            },
          }),
        ),
      ),
    )
    renderWithProviders(<ChunkPreviewDialog chunkId="chunk-scan" onClose={() => {}} />)

    const dialog = await screen.findByRole('dialog', { name: 'Chunk-Vorschau' })
    expect(
      await within(dialog).findByText('Maschinell erkannt, maßgeblich ist das Original'),
    ).toBeInTheDocument()
  })

  it('shows no mark for text from the document itself', async () => {
    server.use(
      http.get('/api/v1/admin/search/chunks/:chunkId', () =>
        HttpResponse.json(chunk({ anchor: { pageFrom: 2, quoteStart: 'Nr.' } })),
      ),
    )
    renderWithProviders(<ChunkPreviewDialog chunkId="chunk-scan" onClose={() => {}} />)

    const dialog = await screen.findByRole('dialog', { name: 'Chunk-Vorschau' })
    expect(await within(dialog).findByText('Chunk-Index: 4')).toBeInTheDocument()
    expect(within(dialog).queryByTestId('recognized-text-notice')).not.toBeInTheDocument()
  })
})
