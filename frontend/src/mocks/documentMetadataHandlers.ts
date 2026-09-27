import { http, HttpResponse } from 'msw'
import { mockUser } from './authFixtures'
import { mockLibraryDetails, mockLibraryDocuments } from './libraryFixtures'
import { mockDocumentMetadata, mockDocumentTypeVocabulary } from './libraryMetadataFixtures'
import type {
  BulkMetadataValueRequest,
  DocumentMetadataFieldResponse,
  MetadataValueRequest,
} from '../types/api'
import { CORE_METADATA_LABELS } from './libraryMetadataHandlers'
import { canManageMockLibrary } from './libraryHandlers'

function mockMetadataFieldsOf(documentId: string): DocumentMetadataFieldResponse[] {
  const stored = mockDocumentMetadata[documentId] ?? []
  return Object.entries(CORE_METADATA_LABELS).map(
    ([fieldKey, label]) =>
      stored.find((field) => field.fieldKey === fieldKey) ?? {
        fieldKey,
        label,
        state: 'EMPTY' as const,
      },
  )
}

function mockDisplayDate(iso: string, precision: string): string {
  const [year, month, day] = iso.split('-')
  if (precision === 'YEAR') return year
  if (precision === 'MONTH') return `${month}/${year}`
  return `${day}.${month}.${year}`
}

/** Validates like MetadataValueInput#validatedFor; returns the stored field or an error text. */
function mockManualField(
  fieldKey: string,
  value: MetadataValueRequest,
): DocumentMetadataFieldResponse | string {
  const label = CORE_METADATA_LABELS[fieldKey]
  if (!label) return `Unbekanntes Metadatenfeld: ${fieldKey}`
  const base = {
    fieldKey,
    label,
    state: 'SET' as const,
    origin: 'MANUAL' as const,
    actorUserId: mockUser.id,
    actorDisplayName: mockUser.displayName,
    updatedAt: new Date().toISOString(),
  }
  // the third state is the same operation without a value, for every core field.
  if (value.state === 'NOT_DETERMINABLE') {
    if (value.textValue || value.vocabularyCode || value.dateValue) {
      return `„Kein Wert ermittelbar“ wird ohne Wert gesetzt (Feld ${label})`
    }
    return { ...base, state: 'NOT_DETERMINABLE' as const }
  }
  if (fieldKey === 'title') {
    const text = value.textValue?.trim()
    if (!text) return 'Der Titel darf nicht leer sein'
    return { ...base, value: text, displayValue: text }
  }
  if (fieldKey === 'document_type') {
    const entry = mockDocumentTypeVocabulary.find((item) => item.code === value.vocabularyCode)
    if (!entry) return `Unbekannte Dokumentart: ${value.vocabularyCode ?? ''}`
    return { ...base, value: entry.code, displayValue: entry.label }
  }
  if (!value.dateValue || !value.datePrecision) {
    return 'Für das Datum ist eine Genauigkeit erforderlich'
  }
  const [year, month] = value.dateValue.split('-')
  const padded =
    value.datePrecision === 'YEAR'
      ? `${year}-01-01`
      : value.datePrecision === 'MONTH'
        ? `${year}-${month}-01`
        : value.dateValue
  return {
    ...base,
    value: padded,
    displayValue: mockDisplayDate(padded, value.datePrecision),
    datePrecision: value.datePrecision,
  }
}

function storeMockMetadataField(documentId: string, field: DocumentMetadataFieldResponse) {
  const current = (mockDocumentMetadata[documentId] ?? []).filter(
    (item) => item.fieldKey !== field.fieldKey,
  )
  mockDocumentMetadata[documentId] = [...current, field]
}

export const documentMetadataHandlers = [
  http.get('/api/v1/libraries/:libraryId/documents/:documentId/metadata', ({ params }) => {
    const libraryId = String(params.libraryId)
    const documentId = String(params.documentId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({ documentId, fields: mockMetadataFieldsOf(documentId) })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/documents/:documentId/metadata/:fieldKey',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      const documentId = String(params.documentId)
      if (!mockLibraryDetails[libraryId]) {
        return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
      }
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
        return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
      }
      const body = (await request.json()) as MetadataValueRequest
      const field = mockManualField(String(params.fieldKey), body)
      if (typeof field === 'string') {
        return HttpResponse.json({ error: field }, { status: 400 })
      }
      storeMockMetadataField(documentId, field)
      return HttpResponse.json(field)
    },
  ),

  http.delete(
    '/api/v1/libraries/:libraryId/documents/:documentId/metadata/:fieldKey',
    ({ params }) => {
      const libraryId = String(params.libraryId)
      const documentId = String(params.documentId)
      if (!mockLibraryDetails[libraryId]) {
        return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
      }
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
        return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
      }
      mockDocumentMetadata[documentId] = (mockDocumentMetadata[documentId] ?? []).filter(
        (item) => item.fieldKey !== String(params.fieldKey),
      )
      return new HttpResponse(null, { status: 204 })
    },
  ),

  http.post('/api/v1/libraries/:libraryId/documents/metadata/bulk', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const body = (await request.json()) as BulkMetadataValueRequest
    const field = mockManualField(body.fieldKey, body.value)
    if (typeof field === 'string') {
      return HttpResponse.json({ error: field }, { status: 400 })
    }
    const own = new Set((mockLibraryDocuments[libraryId] ?? []).map((doc) => doc.id))
    const requested = Array.from(new Set(body.documentIds))
    const rejectedDocumentIds = requested.filter((id) => !own.has(id))
    let updatedCount = 0
    let unchangedCount = 0
    for (const documentId of requested.filter((id) => own.has(id))) {
      const current = (mockDocumentMetadata[documentId] ?? []).find(
        (item) => item.fieldKey === field.fieldKey,
      )
      if (
        current?.origin === 'MANUAL' &&
        current.state === field.state &&
        current.value === field.value
      ) {
        unchangedCount += 1
        continue
      }
      storeMockMetadataField(documentId, field)
      updatedCount += 1
    }
    return HttpResponse.json({
      updatedCount,
      unchangedCount,
      rejectedDocumentIds,
      correlationRef: `metadata-bulk-${Date.now()}`,
    })
  }),
]
