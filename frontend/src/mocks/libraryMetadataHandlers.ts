import { http, HttpResponse } from 'msw'
import type { LibraryMetadataSchemaChangeResponse } from '../types/api'
import { mockLibraryDetails, mockLibraryDocuments } from './libraryFixtures'
import { mockDocumentMetadata, mockDocumentTypeVocabulary } from './libraryMetadataFixtures'
import type { CreateLibraryMetadataFieldRequest, LibraryMetadataFieldResponse } from '../types/api'
import { canManageMockLibrary } from './libraryHandlers'

/**
 * Die laufenden Schemaaenderungen je Bibliothek (#1361) - im Mock genau wie im Backend: die
 * Bestaetigung legt sie an, jede Charge schreibt ein Dokument um, und erst die letzte entfernt den
 * Listenwert beziehungsweise das Feld.
 */
const mockSchemaChanges: Record<string, LibraryMetadataSchemaChangeResponse[]> = {}
const mockCoreContextPrefix: Record<
  string,
  { title: boolean; documentType: boolean; documentDate: boolean }
> = {}

// the three core fields of a document, empty ones included (mirrors
// DocumentMetadataCorrectionService#fieldsOf).
export const CORE_METADATA_LABELS: Record<string, string> = {
  title: 'Titel',
  document_type: 'Dokumentart',
  document_date: 'Datum/Stand',
}

/**
 * the library metadata field schema per library, in memory for the mock session - the
 * settings section writes it and the value-mapping dialog reads it back.
 */
const mockLibraryMetadataFields: Record<string, LibraryMetadataFieldResponse[]> = {}

// the model-backed extraction switches per library, off until a PUT turns them on (#1073).
const mockExtractionSettings: Record<
  string,
  { modelExtractionEnabled: boolean; keywordsEnabled: boolean }
> = {}

function extractionSettingsOf(libraryId: string) {
  const stored = mockExtractionSettings[libraryId] ?? {
    modelExtractionEnabled: false,
    keywordsEnabled: false,
  }
  return {
    libraryId,
    modelExtractionEnabled: stored.modelExtractionEnabled,
    keywordsEnabled: stored.keywordsEnabled,
    confidenceThreshold: 0.8,
    chatModel: {
      baseUrl: 'https://api.openai.com/v1',
      modelIdentifier: 'gpt-4o-mini',
      local: false,
    },
  }
}

/** Was eine fertig gelaufene Schemaaenderung mitnimmt: ihren Listenwert oder ihr ganzes Feld. */
function applyFinishedChange(libraryId: string, change: LibraryMetadataSchemaChangeResponse) {
  const fields = mockLibraryMetadataFields[libraryId] ?? []
  if (change.kind === 'FIELD_DELETION') {
    mockLibraryMetadataFields[libraryId] = fields.filter(
      (field) => field.fieldKey !== change.fieldKey,
    )
    return
  }
  const field = fields.find((candidate) => candidate.fieldKey === change.fieldKey)
  if (field) {
    field.values = field.values.filter((value) => value.code !== change.valueCode)
  }
}

export const libraryMetadataHandlers = [
  // the Pflege-Anker of a library - counted over its mock documents on every call.
  http.get('/api/v1/libraries/:libraryId/metadata/maintenance', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const documents = mockLibraryDocuments[libraryId] ?? []
    const totalDocuments = documents.length
    return HttpResponse.json({
      libraryId,
      totalDocuments,
      fields: Object.entries(CORE_METADATA_LABELS).map(([fieldKey, label]) => {
        const states = documents.map(
          (doc) =>
            (mockDocumentMetadata[doc.id] ?? []).find((field) => field.fieldKey === fieldKey)
              ?.state ?? 'EMPTY',
        )
        const documentsWithoutValue = states.filter((state) => state === 'EMPTY').length
        return {
          fieldKey,
          label,
          totalDocuments,
          documentsWithoutValue,
          missingShare: totalDocuments === 0 ? 0 : documentsWithoutValue / totalDocuments,
          filledDocuments: states.filter((state) => state === 'SET').length,
          notDeterminableDocuments: states.filter((state) => state === 'NOT_DETERMINABLE').length,
        }
      }),
    })
  }),

  // the two model-backed extraction switches, kept in memory so the switch reflects its own PUT.
  http.get('/api/v1/libraries/:libraryId/metadata/extraction-settings', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(extractionSettingsOf(libraryId))
  }),

  http.put(
    '/api/v1/libraries/:libraryId/metadata/extraction-settings',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      if (!mockLibraryDetails[libraryId]) {
        return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
      }
      const body = (await request.json()) as {
        modelExtractionEnabled: boolean
        keywordsEnabled: boolean
      }
      mockExtractionSettings[libraryId] = {
        modelExtractionEnabled: body.modelExtractionEnabled,
        keywordsEnabled: body.keywordsEnabled,
      }
      return HttpResponse.json(extractionSettingsOf(libraryId))
    },
  ),

  // the Extraktionsgüte - counted over the same mock documents the Pflege-Anker uses.
  http.get('/api/v1/libraries/:libraryId/metadata/quality', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const documents = mockLibraryDocuments[libraryId] ?? []
    const totalDocuments = documents.length
    const settings = extractionSettingsOf(libraryId)
    return HttpResponse.json({
      libraryId,
      totalDocuments,
      modelExtractionEnabled: settings.modelExtractionEnabled,
      keywordsEnabled: settings.keywordsEnabled,
      confidenceThreshold: settings.confidenceThreshold,
      fields: Object.entries(CORE_METADATA_LABELS).map(([fieldKey, label]) => {
        const values = documents.map((doc) =>
          (mockDocumentMetadata[doc.id] ?? []).find((field) => field.fieldKey === fieldKey),
        )
        const countOf = (origin: string) =>
          values.filter((value) => value?.state === 'SET' && value.origin === origin).length
        const deterministicDocuments = countOf('DETERMINISTIC')
        const derivedDocuments = countOf('DERIVED')
        const manualDocuments = countOf('MANUAL')
        const notDeterminableDocuments = values.filter(
          (value) => value?.state === 'NOT_DETERMINABLE',
        ).length
        const emptyDocuments =
          totalDocuments -
          deterministicDocuments -
          derivedDocuments -
          manualDocuments -
          notDeterminableDocuments
        return {
          fieldKey,
          label,
          totalDocuments,
          deterministicDocuments,
          derivedDocuments,
          manualDocuments,
          notDeterminableDocuments,
          emptyDocuments,
          derivedShare: totalDocuments === 0 ? 0 : derivedDocuments / totalDocuments,
          emptyShare: totalDocuments === 0 ? 0 : emptyDocuments / totalDocuments,
        }
      }),
      modelExtraction: {
        calls: 12,
        acceptedValues: 8,
        rejectedBelowThreshold: 2,
        rejectedOutsideVocabulary: 1,
        failures: 1,
        rejectedPoolFull: 0,
        keywordsAssigned: 20,
        lastCallAt: '2026-09-01T06:05:00Z',
      },
    })
  }),

  // the library's own metadata fields. Kept in memory so the settings section, the
  // Abbildungsdialog and the filter popover all read the same schema in dev mode.
  http.get('/api/v1/libraries/:libraryId/metadata-fields', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({
      items: mockLibraryMetadataFields[libraryId] ?? [],
      coreContextPrefix: mockCoreContextPrefix[libraryId] ?? {
        title: true,
        documentType: false,
        documentDate: false,
      },
      documentsAwaitingContextPrefixRerun: 0,
      pendingSchemaChanges: mockSchemaChanges[libraryId] ?? [],
    })
  }),

  // Eine Charge des Nachlaufs: ein Dokument je Aufruf, damit der Fortschritt im Dev-Modus
  // sichtbar wird; die fertige Aenderung nimmt ihren Listenwert oder ihr Feld mit.
  http.post('/api/v1/libraries/:libraryId/metadata-fields/schema-changes/run', ({ params }) => {
    const libraryId = String(params.libraryId)
    const changes = mockSchemaChanges[libraryId] ?? []
    const change = changes[0]
    if (!change) {
      return HttpResponse.json({
        processedDocuments: 0,
        skippedDocuments: 0,
        remainingDocuments: 0,
        complete: true,
        pendingChanges: [],
      })
    }
    change.remainingDocuments = Math.max(change.remainingDocuments - 1, 0)
    change.processedDocuments += 1
    if (change.remainingDocuments === 0) {
      mockSchemaChanges[libraryId] = changes.slice(1)
      applyFinishedChange(libraryId, change)
    }
    const pendingChanges = mockSchemaChanges[libraryId] ?? []
    return HttpResponse.json({
      processedDocuments: 1,
      skippedDocuments: 0,
      remainingDocuments: pendingChanges.reduce(
        (sum, pending) => sum + pending.remainingDocuments,
        0,
      ),
      complete: pendingChanges.length === 0,
      pendingChanges,
    })
  }),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/change-impact', ({ request }) => {
    const change = new URL(request.url).searchParams.get('change')
    if (change === 'VALUE_ADDED' || change === 'FIELD_ADDED') {
      return HttpResponse.json({
        affectedDocuments: 0,
        affectedChunks: 0,
        embeddingCalls: 0,
        estimatedSeconds: 0,
        reembeddingRequired: false,
        rateSource: 'CONFIGURED',
      })
    }
    return HttpResponse.json({
      affectedDocuments: 12,
      affectedChunks: 4812,
      embeddingCalls: 4812,
      estimatedSeconds: 2400,
      reembeddingRequired: true,
      rateSource: 'MEASURED',
    })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/metadata-fields/core-context-prefix',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      const body = (await request.json()) as { documentType?: boolean; documentDate?: boolean }
      const next = {
        title: true,
        documentType: body.documentType === true,
        documentDate: body.documentDate === true,
      }
      mockCoreContextPrefix[libraryId] = next
      return HttpResponse.json(next)
    },
  ),

  http.post('/api/v1/libraries/:libraryId/metadata-fields', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const body = (await request.json()) as CreateLibraryMetadataFieldRequest
    if (!body.filter && !body.contextPrefix) {
      return HttpResponse.json(
        { error: 'Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken' },
        { status: 400 },
      )
    }
    const fields = (mockLibraryMetadataFields[libraryId] ??= [])
    if (fields.length >= 5) {
      return HttpResponse.json(
        { error: 'Eine Bibliothek führt höchstens 5 eigene Metadatenfelder' },
        { status: 409 },
      )
    }
    const field: LibraryMetadataFieldResponse = {
      fieldKey: body.fieldKey,
      documentFieldKey: `lib:${body.fieldKey}`,
      label: body.label,
      type: body.type,
      valuePattern: body.valuePattern ?? null,
      filter: body.filter ?? false,
      contextPrefix: body.contextPrefix ?? false,
      citationPosition: body.citationPosition ?? null,
      sortOrder: (fields.length + 1) * 10,
      deletionPending: false,
      values: (body.values ?? []).map((value) => ({
        code: value.code,
        label: value.label,
        retiring: false,
      })),
    }
    fields.push(field)
    return HttpResponse.json(field, { status: 201 })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey',
    async ({ params, request }) => {
      const body = (await request.json()) as {
        label: string
        filter?: boolean
        contextPrefix?: boolean
        citationPosition?: number | null
      }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      if (!body.filter && !body.contextPrefix) {
        return HttpResponse.json(
          { error: 'Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken' },
          { status: 400 },
        )
      }
      field.label = body.label
      field.filter = body.filter ?? false
      field.contextPrefix = body.contextPrefix ?? false
      field.citationPosition = body.citationPosition ?? null
      return HttpResponse.json(field)
    },
  ),

  http.patch(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code',
    async ({ params, request }) => {
      const body = (await request.json()) as { label: string }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      field.values = field.values.map((value) =>
        value.code === String(params.code) ? { ...value, label: body.label } : value,
      )
      return HttpResponse.json(field)
    },
  ),

  http.delete('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey', ({ params }) => {
    const libraryId = String(params.libraryId)
    const fieldKey = String(params.fieldKey)
    const field = (mockLibraryMetadataFields[libraryId] ?? []).find(
      (candidate) => candidate.fieldKey === fieldKey,
    )
    if (!field) {
      return new HttpResponse(null, { status: 204 })
    }
    field.deletionPending = true
    const change: LibraryMetadataSchemaChangeResponse = {
      kind: 'FIELD_DELETION',
      fieldKey,
      valueCode: null,
      targetCode: null,
      processedDocuments: 0,
      remainingDocuments: 2,
      correlationRef: 'metadata-field-delete-mock',
    }
    mockSchemaChanges[libraryId] = [...(mockSchemaChanges[libraryId] ?? []), change]
    return HttpResponse.json(
      {
        processedDocuments: 0,
        skippedDocuments: 0,
        remainingDocuments: change.remainingDocuments,
        complete: false,
        pendingChanges: mockSchemaChanges[libraryId],
      },
      { status: 202 },
    )
  }),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/usage', () =>
    HttpResponse.json({ documentCount: 2 }),
  ),

  http.post(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values',
    async ({ params, request }) => {
      const body = (await request.json()) as { code: string; label: string }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      field.values = [...field.values, { code: body.code, label: body.label, retiring: false }]
      return HttpResponse.json(field)
    },
  ),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code/usage', () =>
    HttpResponse.json({ documentCount: 3 }),
  ),

  http.post(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code/remap',
    async ({ params, request }) => {
      const body = (await request.json()) as { targetCode: string | null }
      const libraryId = String(params.libraryId)
      const code = String(params.code)
      const field = (mockLibraryMetadataFields[libraryId] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (field) {
        // Stillgelegt, nicht entfernt: der Wert bleibt gelistet, bis die letzte Charge durch ist.
        field.values = field.values.map((value) =>
          value.code === code
            ? { ...value, retiring: true, remapTargetCode: body.targetCode }
            : value,
        )
      }
      const change: LibraryMetadataSchemaChangeResponse = {
        kind: 'VALUE_REMAP',
        fieldKey: String(params.fieldKey),
        valueCode: code,
        targetCode: body.targetCode,
        processedDocuments: 1,
        remainingDocuments: 2,
        correlationRef: 'metadata-remap-mock',
      }
      mockSchemaChanges[libraryId] = [...(mockSchemaChanges[libraryId] ?? []), change]
      return HttpResponse.json({
        remappedDocuments: body.targetCode == null ? 0 : 1,
        clearedDocuments: body.targetCode == null ? 1 : 0,
        remainingDocuments: change.remainingDocuments,
        complete: false,
        correlationRef: change.correlationRef,
      })
    },
  ),

  // manual metadata correction - read, set, delete, bulk, plus the vocabulary.
  http.get('/api/v1/metadata/document-types', () =>
    HttpResponse.json({ items: mockDocumentTypeVocabulary }),
  ),
]
