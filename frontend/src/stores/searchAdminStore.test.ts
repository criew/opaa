import { beforeEach, describe, expect, it } from 'vitest'
import { delay, http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockDocumentChunks } from '../mocks/fixtures'
import { useSearchAdminStore } from './searchAdminStore'

describe('searchAdminStore', () => {
  beforeEach(() => {
    useSearchAdminStore.getState().reset()
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
