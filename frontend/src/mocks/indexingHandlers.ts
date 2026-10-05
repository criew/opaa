import { http, HttpResponse } from 'msw'
import { mockIndexingIdle, mockIndexingCompleted, mockIndexingRuns } from './indexingFixtures'
import { mockSearchStatus } from './searchAdminFixtures'
import { mockLibraryDetails, mockSourceTypes } from './libraryFixtures'
import type { IndexingStatusResponse, PushSecretResponse } from '../types/api'

/** Per-library countdown of the mock metadata backfill; see the handler below. */
const mockMetadataBackfillRemaining = new Map<string, number>()
const mockContextPrefixRerunRemaining = new Map<string, number>()

let indexingPollCount = 0
let indexingActive = false

export function resetIndexingState() {
  indexingPollCount = 0
  indexingActive = false
}

/** Whether a mock run is in progress - a private library is then only marked for erasure. */
export function isMockIndexingActive(): boolean {
  return indexingActive
}

const INDEXING_POLL_STEPS = 5
const TOTAL_DOCUMENTS = 42

// Mirrors DocumentIndexingService#executorFor's exact German 409 text for an UPLOAD
// library (no run type at all). Duplicated as a literal - rather than imported from
// stores/indexingStore.ts, which defines the same constant for triggerIndexing's own message
// handling - to keep this mock module independent of application/store code.
const UPLOAD_LIBRARY_INDEXING_ERROR = 'Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf'

function getRunningStatus(step: number): IndexingStatusResponse {
  const progress = Math.min(step / INDEXING_POLL_STEPS, 1)
  const documentCount = Math.round(TOTAL_DOCUMENTS * progress)
  return {
    status: 'RUNNING',
    documentCount,
    totalDocuments: TOTAL_DOCUMENTS,
    documentsSkipped: 0,
    documentsFailed: 0,
    documentsIndexedTotal: documentCount,
    message: `Indexing in progress... ${documentCount} documents processed`,
    timestamp: new Date().toISOString(),
  }
}

export const indexingHandlers = [
  // the trigger reduces to "index this library" - libraryId is a path variable, not a
  // request body field, and sourceType/configuration come from the library itself (ADR-0018).
  http.post('/api/v1/libraries/:libraryId/indexing', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        {
          error: 'Bibliothek nicht gefunden',
          status: 404,
          timestamp: new Date().toISOString(),
        },
        { status: 404 },
      )
    }
    // Mirrors DocumentIndexingService#executorFor ( review, finding 5): UPLOAD has no
    // run type at all - the library is a valid indexing target, it simply has nothing to run.
    if (library.sourceType === 'UPLOAD') {
      return HttpResponse.json(
        {
          error: UPLOAD_LIBRARY_INDEXING_ERROR,
          status: 409,
          timestamp: new Date().toISOString(),
        },
        { status: 409 },
      )
    }

    if (library.erasureRequestedAt) {
      return HttpResponse.json(
        { error: 'Die Bibliothek wird gelöscht und wird nicht mehr indiziert', status: 409 },
        { status: 409 },
      )
    }

    indexingPollCount = 0
    indexingActive = true
    return HttpResponse.json(
      {
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        documentsFailed: 0,
        documentsIndexedTotal: 0,
        message: 'Indizierung gestartet',
        timestamp: new Date().toISOString(),
        libraryId,
      } satisfies IndexingStatusResponse,
      { status: 202 },
    )
  }),

  // the push secret is shown once; the mock keeps the yes/no on the library detail. Mirrors
  // KnowledgeLibraryService: only a type whose connector names a push intake has one (ADR-0038).
  http.post('/api/v1/libraries/:libraryId/push-secret', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        { error: 'Bibliothek nicht gefunden', status: 404, timestamp: new Date().toISOString() },
        { status: 404 },
      )
    }
    if (!mockSourceTypes.some((d) => d.type === library.sourceType && d.pushIntake)) {
      return HttpResponse.json(
        {
          error: `Für Bibliotheken vom Typ ${library.sourceType} gibt es keinen Push-Eingang`,
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    mockLibraryDetails[libraryId] = { ...library, pushSecretSet: true }
    return HttpResponse.json({
      secret: 'mock-push-secret-' + libraryId.slice(0, 8),
      path: `/api/v1/libraries/${libraryId}/push`,
    } satisfies PushSecretResponse)
  }),

  http.delete('/api/v1/libraries/:libraryId/push-secret', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        { error: 'Bibliothek nicht gefunden', status: 404, timestamp: new Date().toISOString() },
        { status: 404 },
      )
    }
    if (!mockSourceTypes.some((d) => d.type === library.sourceType && d.pushIntake)) {
      return HttpResponse.json(
        {
          error: `Für Bibliotheken vom Typ ${library.sourceType} gibt es keinen Push-Eingang`,
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    mockLibraryDetails[libraryId] = { ...library, pushSecretSet: false }
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/indexing/status', ({ params }) => {
    const libraryId = params.libraryId as string
    if (!indexingActive) {
      return HttpResponse.json({ ...mockIndexingIdle, libraryId })
    }

    indexingPollCount++

    if (indexingPollCount >= INDEXING_POLL_STEPS) {
      indexingActive = false
      return HttpResponse.json({ ...mockIndexingCompleted, libraryId })
    }

    return HttpResponse.json({ ...getRunningStatus(indexingPollCount), libraryId })
  }),

  http.get('/api/v1/libraries/:libraryId/indexing/runs', () => {
    return HttpResponse.json(mockIndexingRuns)
  }),

  // The fixture is static, so the remaining work is counted down here: every call processes one
  // document until the library's pending count is used up, then reports done - otherwise the
  // page's batch loop would never end in mock mode.
  http.post('/api/v1/admin/indexing/metadata-backfill', async ({ request }) => {
    const body = (await request.json()) as { libraryId?: string; batchSize?: number }
    const library = mockSearchStatus.libraries.find((l) => l.libraryId === body.libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const remaining =
      mockMetadataBackfillRemaining.get(library.libraryId) ??
      library.metadataBackfill.pendingDocuments -
        library.metadataBackfill.awaitingConnectorRunDocuments
    const processed = Math.min(remaining, 1)
    mockMetadataBackfillRemaining.set(library.libraryId, remaining - processed)
    return HttpResponse.json({
      processedDocuments: processed,
      markedForNextRun: 0,
      skippedDocuments: 0,
      done: processed === 0,
    })
  }),

  // Same countdown as the backfill above, so the page's batch loop terminates in mock mode.
  http.post('/api/v1/admin/indexing/context-prefix-rerun', async ({ request }) => {
    const body = (await request.json()) as { libraryId?: string; batchSize?: number }
    const library = mockSearchStatus.libraries.find((l) => l.libraryId === body.libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const remaining =
      mockContextPrefixRerunRemaining.get(library.libraryId) ??
      library.contextPrefixRerun.pendingDocuments
    const processed = Math.min(remaining, 1)
    mockContextPrefixRerunRemaining.set(library.libraryId, remaining - processed)
    return HttpResponse.json({
      processedDocuments: processed,
      skippedDocuments: 0,
      done: processed === 0,
    })
  }),
]
