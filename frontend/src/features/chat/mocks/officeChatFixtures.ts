import type { ChatDetail, SourceReference } from '@/shared/api/types'
import { OFFICE_DOCUMENTS } from '@/features/library/mocks/officeDocuments'

const { docx, csv, mail } = OFFICE_DOCUMENTS

/** Sources of an answer from a Word file, a spreadsheet and a mail. */
const OFFICE_SOURCES: SourceReference[] = [
  {
    fileName: docx.fileName,
    relevanceScore: 0.94,
    matchCount: 1,
    indexedAt: '2026-11-20T08:00:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: docx.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 2,
        location: 'Abschn. 2. Fristen',
        anchor: {
          path: ['2. Fristen'],
          quoteStart: 'Der Verwendungsnachweis ist spätestens sechs Monate nach Abschluss',
          quoteEnd: 'wenn der Antrag vor Ablauf der Frist eingeht.',
        },
      },
    ],
  },
  {
    fileName: csv.fileName,
    relevanceScore: 0.81,
    matchCount: 1,
    indexedAt: '2026-11-21T08:00:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: csv.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 0,
        location: 'Zeile 5',
        anchor: {
          rowFrom: 5,
          rowTo: 5,
          quoteStart: 'WF-2026-037 | Beleuchtung Schaufenster Am Tor 2 | Optik Brandt | 31.07.2026',
          quoteEnd: '31.01.2027 | offen',
        },
      },
    ],
  },
  {
    fileName: mail.fileName,
    relevanceScore: 0.77,
    matchCount: 1,
    indexedAt: '2026-11-16T09:50:00Z',
    cited: true,
    citationValid: true,
    privateSource: false,
    documentId: mail.id,
    sourceType: 'UPLOAD',
    chunkLocations: [
      {
        chunkIndex: 0,
        location: 'Mail vom 16.11.2026',
        anchor: {
          quoteStart: 'Können wir die Frist für den Verwendungsnachweis um drei Monate',
          quoteEnd: 'Sachbericht mit Fotos könnten wir schon jetzt schicken.',
        },
      },
    ],
  },
]

const OFFICE_ANSWER =
  'Optik Brandt muss den Verwendungsnachweis bis zum 31.01.2027 vorlegen' +
  `【source: ${csv.id}#0 | ${csv.fileName}】. Die Frist beträgt sechs Monate nach Abschluss ` +
  'der Maßnahme und lässt sich auf formlosen Antrag um bis zu drei Monate verlängern, wenn der ' +
  `Antrag vor Fristablauf eingeht【source: ${docx.id}#2 | ${docx.fileName}】. Frau Brandt hat ` +
  'diese Verlängerung am 16.11.2026 per Mail beantragt, also rechtzeitig' +
  `【source: ${mail.id}#0 | ${mail.fileName}】.`

/** A chat whose answer cites DOCX, CSV and EML, so each original view can be compared. */
export const OFFICE_CHAT: ChatDetail = {
  id: 'chat-office',
  spaceId: 'space-phoenix',
  authorId: 'mock-user-id',
  title: 'Frist für Optik Brandt',
  useKnowledge: true,
  referencedLibraryIds: [],
  status: 'PRIVATE',
  messages: [
    {
      id: 'message-office-1',
      chatId: 'chat-office',
      role: 'USER',
      generatedByAi: false,
      content: 'Bis wann muss Optik Brandt den Verwendungsnachweis vorlegen?',
      createdAt: '2026-11-20T10:00:00Z',
    },
    {
      id: 'message-office-2',
      chatId: 'chat-office',
      role: 'ASSISTANT',
      generatedByAi: true,
      content: OFFICE_ANSWER,
      sources: OFFICE_SOURCES,
      createdAt: '2026-11-20T10:00:07Z',
    },
  ],
  noteItems: [],
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:07Z',
}
