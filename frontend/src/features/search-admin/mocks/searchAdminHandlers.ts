import { http, HttpResponse } from 'msw'
import {
  mockSearchStatus,
  mockSearchDiagnosisContext,
  mockSearchDiagnosis,
  mockChunkInspections,
  mockDocumentChunks,
} from './searchAdminFixtures'

export const searchAdminHandlers = [
  http.get('/api/v1/admin/search/status', () => {
    return HttpResponse.json(mockSearchStatus)
  }),

  http.get('/api/v1/admin/search/diagnosis-context', () => {
    return HttpResponse.json(mockSearchDiagnosisContext)
  }),

  http.post('/api/v1/admin/search/diagnosis', async ({ request }) => {
    const body = (await request.json()) as {
      question?: string
      contextType?: string
      permissionProfileId?: string
      targetUserId?: string
      justification?: string
      trackedDocumentId?: string
    }
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json({ error: 'Die Testfrage darf nicht leer sein.' }, { status: 400 })
    }
    // Mirrors the endpoint: the person context is refused without the befugnis, whatever the
    // client sends, and it is never executed without a justification.
    if (body.contextType === 'USER') {
      if (!mockSearchDiagnosisContext.personContextAvailable) {
        return HttpResponse.json(
          {
            error:
              'Fuer „Sicht als“ ist eine eigene, befristete Befugnis noetig; Sie halten keine.',
          },
          { status: 403 },
        )
      }
      if (!body.justification || body.justification.trim() === '') {
        return HttpResponse.json({ error: 'Begruendung ist erforderlich' }, { status: 400 })
      }
      return HttpResponse.json({
        ...mockSearchDiagnosis,
        question: body.question,
        contextType: 'USER',
        contextLabel: 'Rechtekontext einer Person',
        lockedLibraryCount: 1,
      })
    }
    return HttpResponse.json({
      ...mockSearchDiagnosis,
      question: body.question,
      contextType: body.contextType === 'SELF' ? 'SELF' : 'PERMISSION_PROFILE',
      contextLabel:
        body.contextType === 'SELF' ? 'Eigener Rechtekontext' : mockSearchDiagnosis.contextLabel,
      trackedDocument: body.trackedDocumentId
        ? {
            documentId: body.trackedDocumentId,
            fileName: 'antrag-befreiung.pdf',
            libraryId: 'lib-formulare',
            libraryName: 'Formulare',
            outcome: 'DISPLACED',
            displacedAtStage: 'RANK_FUSION',
            displacedReason: 'OUTSIDE_FUSION_BUDGET',
            retrievedChunkCount: 1,
            selectedChunkCount: 0,
          }
        : null,
    })
  }),

  http.get('/api/v1/admin/search/chunks/:chunkId', ({ params }) => {
    const chunk = mockChunkInspections[params.chunkId as string]
    if (!chunk) {
      return HttpResponse.json({ error: 'Der Chunk wurde nicht gefunden.' }, { status: 404 })
    }
    return HttpResponse.json(chunk)
  }),

  http.get('/api/v1/admin/search/documents/:documentId/chunks', ({ params }) => {
    if (params.documentId !== mockDocumentChunks.documentId) {
      return HttpResponse.json({ error: 'Das Dokument wurde nicht gefunden.' }, { status: 404 })
    }
    return HttpResponse.json(mockDocumentChunks)
  }),
]
