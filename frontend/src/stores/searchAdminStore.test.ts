import { beforeEach, describe, expect, it } from 'vitest'
import { delay, http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockDocumentChunks } from '../mocks/fixtures'
import { BATCH_RUN_STALLED_MESSAGE, useSearchAdminStore } from './searchAdminStore'

describe('searchAdminStore', () => {
  beforeEach(() => {
    useSearchAdminStore.getState().reset()
  })

  it('runs the metadata backfill against the default mock handler until it reports done', async () => {
    let batchCalls = 0
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/admin/indexing/metadata-backfill')) batchCalls += 1
    })

    await useSearchAdminStore.getState().startMetadataBackfill('lib-satzungen')

    const run = useSearchAdminStore.getState().metadataBackfillRuns['lib-satzungen']
    expect(run.running).toBe(false)
    expect(run.done).toBe(true)
    expect(run.error).toBeNull()
    // The fixture has 2 pending of which 1 waits for its connector run: one document per mock
    // batch, then done - a finite loop even in mock mode.
    expect(run.processedDocuments).toBe(1)
    expect(batchCalls).toBe(2)
  })

  it('stops a run that keeps answering "not done" without advancing anything', async () => {
    let batchCalls = 0
    server.use(
      http.post('/api/v1/admin/indexing/metadata-backfill', () => {
        batchCalls += 1
        return HttpResponse.json({
          processedDocuments: 0,
          markedForNextRun: 0,
          skippedDocuments: 1,
          done: false,
        })
      }),
    )

    await useSearchAdminStore.getState().startMetadataBackfill('lib-satzungen')

    const run = useSearchAdminStore.getState().metadataBackfillRuns['lib-satzungen']
    expect(run.running).toBe(false)
    expect(run.error).toBe(BATCH_RUN_STALLED_MESSAGE)
    expect(batchCalls).toBe(3)
  })

  it('keeps the chunks of the document requested last when an earlier answer arrives later', async () => {
    const SLOW_ID = '11111111-1111-4111-8111-111111111111'
    const FAST_ID = '22222222-2222-4222-8222-222222222222'
    server.use(
      http.get('/api/v1/admin/search/documents/:documentId/chunks', async ({ params }) => {
        const slow = params.documentId === SLOW_ID
        if (slow) await delay(200)
        return HttpResponse.json({
          ...mockDocumentChunks,
          documentId: params.documentId,
          documentTitle: slow ? 'langsam.pdf' : 'schnell.pdf',
        })
      }),
    )

    const first = useSearchAdminStore.getState().loadDocumentChunks(SLOW_ID)
    const second = useSearchAdminStore.getState().loadDocumentChunks(FAST_ID)
    await Promise.all([first, second])

    const { documentChunks, isLoadingDocumentChunks } = useSearchAdminStore.getState()
    expect(documentChunks?.documentTitle).toBe('schnell.pdf')
    expect(isLoadingDocumentChunks).toBe(false)
  })

  it('reports a not-found document as an error rather than an empty list', async () => {
    await useSearchAdminStore.getState().loadDocumentChunks('99999999-9999-4999-8999-999999999999')

    const { documentChunks, documentChunksError } = useSearchAdminStore.getState()
    expect(documentChunks).toBeNull()
    expect(documentChunksError).toBe('Das Dokument wurde nicht gefunden.')
  })
})
