import type { ChatDetail, SourceReference } from '@/shared/api/types'
import { SCAN_REGION_DUE, SCAN_REGION_ROW4 } from '@/features/library/mocks/scanPdfFixture'

/** File names of the scanned fixtures (epic #2640); the content mock serves them by document id. */
export const SCAN_DOCUMENTS = {
  docling: { id: 'doc-scan', fileName: 'Verwaltungsgebuehrensatzung_2003_Scan.pdf' },
  vision: { id: 'doc-scan-vision', fileName: 'Bauakte_Hauptstrasse_12_1998_Scan.pdf' },
  text: { id: 'doc-kasse', fileName: 'Dienstanweisung_Stadtkasse_2024.pdf' },
} as const

/**
 * The cited sources of the OCR answer: a scan read by docling-serve (frames on the anchor), a
 * scan read by a vision model (pages only) and an ordinary text PDF for comparison.
 */
export const SCAN_SOURCES: SourceReference[] = [
  {
    fileName: SCAN_DOCUMENTS.docling.fileName,
    relevanceScore: 0.92,
    matchCount: 2,
    indexedAt: '2026-11-05T07:40:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: SCAN_DOCUMENTS.docling.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 4,
        location: 'S. 2 · Anlage › Gebührentarif',
        anchor: {
          pageFrom: 2,
          pageTo: 2,
          path: ['Anlage zur Verwaltungsgebührensatzung', 'Gebührentarif'],
          quoteStart: 'Nr. 4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
          regions: [SCAN_REGION_ROW4],
          textOrigin: { binding: 'DOCLING_SERVE' },
        },
      },
      {
        chunkIndex: 2,
        location: 'S. 1 · § 3 Entstehung und Fälligkeit',
        anchor: {
          pageFrom: 1,
          pageTo: 1,
          path: ['§ 3 Entstehung und Fälligkeit'],
          quoteStart: '(1) Die Gebührenschuld entsteht mit der Beendigung der Amtshandlung.',
          quoteEnd: 'Gebührenfestsetzung an den Schuldner fällig.',
          regions: [SCAN_REGION_DUE],
          textOrigin: { binding: 'DOCLING_SERVE' },
        },
      },
    ],
  },
  {
    fileName: SCAN_DOCUMENTS.vision.fileName,
    relevanceScore: 0.71,
    matchCount: 1,
    indexedAt: '2026-11-05T07:42:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: SCAN_DOCUMENTS.vision.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 1,
        location: 'S. 2',
        anchor: {
          pageFrom: 2,
          pageTo: 2,
          quoteStart: 'Zweitausfertigungen werden nach dem jeweils gültigen Gebührentarif',
          regions: null,
          textOrigin: { binding: 'OPENAI_VISION' },
        },
      },
    ],
  },
  {
    fileName: SCAN_DOCUMENTS.text.fileName,
    relevanceScore: 0.55,
    matchCount: 1,
    indexedAt: '2026-10-01T08:00:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: SCAN_DOCUMENTS.text.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 0,
        location: 'S. 1 · Abschn. 2 Zahlungswege',
        anchor: {
          pageFrom: 1,
          pageTo: 1,
          quoteStart: 'Gebühren werden bar, mit Karte oder per Überweisung',
          quoteEnd: 'an der Stadtkasse entrichtet.',
        },
      },
    ],
  },
]

export const SCAN_ANSWER =
  'Eine Zweitausfertigung eines Bescheids kostet nach dem Gebührentarif 46,00 Euro' +
  `【source: doc-scan#4 | ${SCAN_DOCUMENTS.docling.fileName}】. Die Gebühr wird mit der ` +
  'Bekanntgabe der Gebührenfestsetzung fällig' +
  `【source: doc-scan#2 | ${SCAN_DOCUMENTS.docling.fileName}】. Für ältere Bescheide aus ` +
  'der Bauakte gilt derselbe Tarif' +
  `【source: doc-scan-vision#1 | ${SCAN_DOCUMENTS.vision.fileName}】. Gezahlt wird an der ` +
  `Stadtkasse【source: doc-kasse#0 | ${SCAN_DOCUMENTS.text.fileName}】.`

/** A chat whose answer cites scanned PDFs, so Belegfenster and viewer show the OCR marking. */
export const SCAN_CHAT: ChatDetail = {
  id: 'chat-scan',
  spaceId: 'space-personal',
  authorId: 'mock-user-id',
  title: 'Gebühr für eine Zweitausfertigung',
  useKnowledge: true,
  referencedLibraryIds: [],
  status: 'PRIVATE',
  messages: [
    {
      id: 'message-scan-1',
      chatId: 'chat-scan',
      role: 'USER',
      generatedByAi: false,
      content: 'Was kostet eine Zweitausfertigung eines Bescheids?',
      createdAt: '2026-02-20T10:00:00Z',
    },
    {
      id: 'message-scan-2',
      chatId: 'chat-scan',
      role: 'ASSISTANT',
      generatedByAi: true,
      content: SCAN_ANSWER,
      sources: SCAN_SOURCES,
      createdAt: '2026-02-20T10:00:06Z',
    },
  ],
  noteItems: [],
  createdAt: '2026-02-20T10:00:00Z',
  updatedAt: '2026-02-20T10:00:06Z',
}
