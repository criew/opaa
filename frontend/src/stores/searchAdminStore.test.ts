import { beforeEach, describe, expect, it, onTestFinished } from 'vitest'
import { delay, http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockDocumentChunks } from '../mocks/fixtures'
import { BATCH_RUN_STALLED_MESSAGE, useSearchAdminStore } from './searchAdminStore'

describe('searchAdminStore', () => {
  beforeEach(() => {
    useSearchAdminStore.getState().reset()
  })

  /**
   * Status und Diagnosekontext speisen verschiedene Reiter (#1616) und werden deshalb getrennt
   * geholt: Wer den Indexstatus ansieht, löst keinen Aufruf der Diagnose aus und umgekehrt.
   */
  it('fetches only the status, not the diagnosis context', async () => {
    const aufrufe: string[] = []
    const horcher = ({ request }: { request: Request }) => {
      if (request.url.includes('/admin/search/')) aufrufe.push(new URL(request.url).pathname)
    }
    server.events.on('request:start', horcher)
    onTestFinished(() => server.events.removeListener('request:start', horcher))

    await useSearchAdminStore.getState().loadStatus()

    expect(aufrufe).toEqual(['/api/v1/admin/search/status'])
    expect(useSearchAdminStore.getState().status).not.toBeNull()
  })

  it('fetches only the diagnosis context, not the status', async () => {
    const aufrufe: string[] = []
    const horcher = ({ request }: { request: Request }) => {
      if (request.url.includes('/admin/search/')) aufrufe.push(new URL(request.url).pathname)
    }
    server.events.on('request:start', horcher)
    onTestFinished(() => server.events.removeListener('request:start', horcher))

    await useSearchAdminStore.getState().loadDiagnosisContext()

    expect(aufrufe).toEqual(['/api/v1/admin/search/diagnosis-context'])
    expect(useSearchAdminStore.getState().status).toBeNull()
  })

  /**
   * Die beiden Fehlerzustände sind getrennt: Ein nicht erreichbarer Diagnosekontext darf den
   * Indexstatus nicht verdecken, den er gar nicht betrifft. Vorher teilten sich beide Aufrufe
   * einen Zustand, und der Fehler des einen erschien über dem Inhalt des anderen.
   */
  it('keeps a failing diagnosis context out of the status error', async () => {
    server.use(
      http.get(
        '/api/v1/admin/search/diagnosis-context',
        () => new HttpResponse(null, { status: 503 }),
      ),
    )

    await useSearchAdminStore.getState().loadStatus()
    await useSearchAdminStore.getState().loadDiagnosisContext()

    expect(useSearchAdminStore.getState().contextError).toBeTruthy()
    expect(useSearchAdminStore.getState().statusError).toBeNull()
    expect(useSearchAdminStore.getState().status).not.toBeNull()
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
