import type {
  DocumentMetadataFieldResponse,
  DocumentTypeVocabularyEntryResponse,
} from '../types/api'

export const mockDocumentTypeVocabulary: DocumentTypeVocabularyEntryResponse[] = [
  { code: 'SATZUNG_ORDNUNG', label: 'Satzung/Ordnung' },
  { code: 'DIENSTANWEISUNG', label: 'Dienstanweisung' },
  { code: 'VERMERK', label: 'Vermerk' },
  { code: 'PROTOKOLL', label: 'Protokoll' },
  { code: 'BESCHEID_VORLAGE', label: 'Bescheid-Vorlage' },
  { code: 'FORMULAR', label: 'Formular' },
  { code: 'GEBUEHRENVERZEICHNIS', label: 'Gebührenverzeichnis' },
  { code: 'PRAESENTATION', label: 'Präsentation' },
  { code: 'SONSTIGES', label: 'Sonstiges' },
]

// #1068: core metadata per document with its provenance - one deterministic, one derived and
// one manual value on the first fixture document, so every origin marking is exercised in
// mock/dev mode; documents without an entry read as three empty fields.
const INITIAL_DOCUMENT_METADATA: Record<string, DocumentMetadataFieldResponse[]> = {
  'document-dienstanweisung': [
    {
      fieldKey: 'title',
      label: 'Titel',
      state: 'SET',
      value: 'Dienstanweisung zur IT-Nutzung',
      displayValue: 'Dienstanweisung zur IT-Nutzung',
      origin: 'DETERMINISTIC',
      extractionVersion: 1,
      updatedAt: '2026-03-02T09:00:00Z',
    },
    {
      fieldKey: 'document_type',
      label: 'Dokumentart',
      state: 'SET',
      value: 'DIENSTANWEISUNG',
      displayValue: 'Dienstanweisung',
      origin: 'DERIVED',
      confidence: 0.82,
      modelId: 'mock-model',
      extractionVersion: 1,
      updatedAt: '2026-03-02T09:00:00Z',
    },
    {
      fieldKey: 'document_date',
      label: 'Datum/Stand',
      state: 'SET',
      value: '2024-01-01',
      displayValue: '2024',
      origin: 'MANUAL',
      datePrecision: 'YEAR',
      actorUserId: 'mock-user-id',
      actorDisplayName: 'Max Mustermann',
      updatedAt: '2026-03-03T14:30:00Z',
    },
  ],
}

export let mockDocumentMetadata: Record<string, DocumentMetadataFieldResponse[]> =
  structuredClone(INITIAL_DOCUMENT_METADATA)

export function resetMockDocumentMetadata() {
  mockDocumentMetadata = structuredClone(INITIAL_DOCUMENT_METADATA)
}
