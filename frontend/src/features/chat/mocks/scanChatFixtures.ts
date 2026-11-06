import type { ChatDetail, SourceReference } from '@/shared/api/types'
import { SCAN_REGION_DUE, SCAN_REGION_TARIFF_ROW_4 } from '@/features/library/mocks/mockScanPdf'

/** The scans the mocked answer cites; the content mock serves both as the same scanned PDF. */
export const SCAN_DOCUMENTS = {
  docling: { id: 'doc-scan', fileName: 'Verwaltungsgebuehrensatzung_2003_Scan.pdf' },
  vision: { id: 'doc-scan-vision', fileName: 'Gebuehrensatzung_Abschrift_Scan.pdf' },
} as const

/**
 * Sources of an answer from text recognition: a scan read by docling-serve, whose anchors carry
 * frames, and one read by a vision model, which knows only its page.
 */
const SCAN_SOURCES: SourceReference[] = [
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
        location: 'S. 2 · Abschn. Anlage › Gebührentarif',
        anchor: {
          pageFrom: 2,
          pageTo: 2,
          path: ['Anlage zur Verwaltungsgebührensatzung', 'Gebührentarif'],
          quoteStart: 'Nr. | Gegenstand | Gebühr 4 | Zweitausfertigung eines',
          quoteEnd: '4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
          regions: [SCAN_REGION_TARIFF_ROW_4],
          textOrigin: { connectionType: 'DOCLING_SERVE' },
        },
      },
      {
        chunkIndex: 2,
        location: 'S. 1 · Abschn. § 3 Entstehung und Fälligkeit',
        anchor: {
          pageFrom: 1,
          pageTo: 1,
          path: ['§ 3 Entstehung und Fälligkeit'],
          quoteStart: '(1) Die Gebührenschuld entsteht mit der Beendigung der',
          quoteEnd: 'Gebührenfestsetzung an den Schuldner fällig.',
          regions: [SCAN_REGION_DUE],
          textOrigin: { connectionType: 'DOCLING_SERVE' },
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
          quoteStart: 'Anlage zur Verwaltungsgebührensatzung Gebührentarif',
          textOrigin: { connectionType: 'OPENAI_VISION' },
        },
      },
    ],
  },
]

const SCAN_ANSWER =
  'Eine Zweitausfertigung eines Bescheids kostet nach dem Gebührentarif 46,00 Euro' +
  `【source: doc-scan#4 | ${SCAN_DOCUMENTS.docling.fileName}】. Die Gebühr wird mit der ` +
  'Bekanntgabe der Gebührenfestsetzung fällig' +
  `【source: doc-scan#2 | ${SCAN_DOCUMENTS.docling.fileName}】. Die Abschrift der Satzung ` +
  `nennt denselben Tarif【source: doc-scan-vision#1 | ${SCAN_DOCUMENTS.vision.fileName}】.`

/** A chat whose answer cites scanned PDFs, so Belegfenster and viewer show frames and marks. */
export const SCAN_CHAT: ChatDetail = {
  id: 'chat-scan',
  spaceId: 'space-phoenix',
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
      createdAt: '2026-11-05T10:00:00Z',
    },
    {
      id: 'message-scan-2',
      chatId: 'chat-scan',
      role: 'ASSISTANT',
      generatedByAi: true,
      content: SCAN_ANSWER,
      sources: SCAN_SOURCES,
      createdAt: '2026-11-05T10:00:06Z',
    },
  ],
  noteItems: [],
  createdAt: '2026-11-05T10:00:00Z',
  updatedAt: '2026-11-05T10:00:06Z',
}
