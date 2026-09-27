import type { IndexingRunListResponse, IndexingStatusResponse } from '../types/api'

export const mockIndexingIdle: IndexingStatusResponse = {
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  documentsFailed: 0,
  documentsIndexedTotal: 0,
  message: null,
  timestamp: '2025-01-15T10:00:00Z',
}

export const mockIndexingCompleted: IndexingStatusResponse = {
  status: 'COMPLETED',
  documentCount: 37,
  totalDocuments: 42,
  documentsSkipped: 5,
  documentsFailed: 0,
  documentsIndexedTotal: 37,
  message: 'Indizierung abgeschlossen: 37 verarbeitet, 5 übersprungen, 0 fehlgeschlagen',
  timestamp: '2025-01-15T10:30:00Z',
}

/** @deprecated Use mockIndexingCompleted instead */
export const mockIndexingStatus = mockIndexingCompleted

// #513: fixture for GET /api/v1/libraries/{libraryId}/indexing/runs - one run with a protocol
// entry, mirroring the issue's own motivating BMF case (a rejected RSS entry).
export const mockIndexingRuns: IndexingRunListResponse = {
  runs: [
    {
      id: '11111111-1111-1111-1111-111111111111',
      status: 'COMPLETED',
      triggeredBy: 'MANUAL',
      runMode: 'FULL',
      documentCount: 37,
      totalDocuments: 42,
      documentsSkipped: 5,
      documentsFailed: 0,
      documentsIndexedTotal: 37,
      message: 'Indizierung abgeschlossen: 37 verarbeitet, 5 übersprungen, 0 fehlgeschlagen',
      startedAt: '2025-01-15T10:29:00Z',
      completedAt: '2025-01-15T10:30:00Z',
      events: [
        {
          category: 'REJECTED',
          message:
            'Vom Quellserver abgewiesen (z. B. Bot-Schutz oder Weiterleitung auf einen fremden Host)',
          reference: 'https://example.org/aktuelles/pressemitteilung-42',
        },
      ],
      eventsTruncatedCount: 0,
    },
  ],
}
