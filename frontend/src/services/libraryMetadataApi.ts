import type {
  CoreContextPrefixRequest,
  CoreContextPrefixResponse,
  MetadataChangeImpactResponse,
  MetadataChangeKind,
  BulkMetadataValueRequest,
  BulkMetadataValueResponse,
  DocumentMetadataFieldResponse,
  DocumentMetadataResponse,
  DocumentTypeVocabularyResponse,
  CreateLibraryMetadataFieldRequest,
  LibraryMetadataFieldResponse,
  LibraryMetadataFieldValueRequest,
  LibraryMetadataFieldsResponse,
  MetadataFieldUsageResponse,
  RemapLibraryMetadataFieldValueResponse,
  LibraryMetadataSchemaRunResponse,
  UpdateLibraryMetadataFieldRequest,
  LibraryMetadataExtractionSettingsRequest,
  LibraryMetadataExtractionSettingsResponse,
  LibraryMetadataMaintenanceResponse,
  LibraryMetadataQualityResponse,
  LibraryMetadataSampleResponse,
  MetadataValueRequest,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// manual metadata correction - every core field of a document with its provenance.
export async function getDocumentMetadata(
  libraryId: string,
  documentId: string,
): Promise<DocumentMetadataResponse> {
  try {
    const { data } = await client.get<DocumentMetadataResponse>(
      `/v1/libraries/${libraryId}/documents/${documentId}/metadata`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setDocumentMetadataValue(
  libraryId: string,
  documentId: string,
  fieldKey: string,
  value: MetadataValueRequest,
): Promise<DocumentMetadataFieldResponse> {
  try {
    const { data } = await client.put<DocumentMetadataFieldResponse>(
      `/v1/libraries/${libraryId}/documents/${documentId}/metadata/${fieldKey}`,
      value,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteDocumentMetadataValue(
  libraryId: string,
  documentId: string,
  fieldKey: string,
): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/documents/${documentId}/metadata/${fieldKey}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function bulkSetDocumentMetadata(
  libraryId: string,
  request: BulkMetadataValueRequest,
): Promise<BulkMetadataValueResponse> {
  try {
    const { data } = await client.post<BulkMetadataValueResponse>(
      `/v1/libraries/${libraryId}/documents/metadata/bulk`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** the Pflege-Anker of a library - "N Dokumente ohne Wert" per core field. */
export async function getLibraryMetadataMaintenance(
  libraryId: string,
): Promise<LibraryMetadataMaintenanceResponse> {
  try {
    const { data } = await client.get<LibraryMetadataMaintenanceResponse>(
      `/v1/libraries/${libraryId}/metadata/maintenance`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** the two model-backed extraction switches of a library, with the chat role behind them. */
export async function getLibraryMetadataExtractionSettings(
  libraryId: string,
): Promise<LibraryMetadataExtractionSettingsResponse> {
  try {
    const { data } = await client.get<LibraryMetadataExtractionSettingsResponse>(
      `/v1/libraries/${libraryId}/metadata/extraction-settings`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLibraryMetadataExtractionSettings(
  libraryId: string,
  request: LibraryMetadataExtractionSettingsRequest,
): Promise<LibraryMetadataExtractionSettingsResponse> {
  try {
    const { data } = await client.put<LibraryMetadataExtractionSettingsResponse>(
      `/v1/libraries/${libraryId}/metadata/extraction-settings`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** the Extraktionsgüte per field plus the Zählwerk of the model-backed extraction. */
export async function getLibraryMetadataQuality(
  libraryId: string,
): Promise<LibraryMetadataQualityResponse> {
  try {
    const { data } = await client.get<LibraryMetadataQualityResponse>(
      `/v1/libraries/${libraryId}/metadata/quality`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** the Stichproben-Export for the Handauswertung; management right. */
export async function getLibraryMetadataSample(
  libraryId: string,
  size = 100,
): Promise<LibraryMetadataSampleResponse> {
  try {
    const { data } = await client.get<LibraryMetadataSampleResponse>(
      `/v1/libraries/${libraryId}/metadata/sample`,
      { params: { size } },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * the library's own metadata fields with their configured value lists. Schema, not an
 * aggregate - readable by everyone who may use the library.
 */
export async function listLibraryMetadataFields(
  libraryId: string,
): Promise<LibraryMetadataFieldsResponse> {
  try {
    const { data } = await client.get<LibraryMetadataFieldsResponse>(
      `/v1/libraries/${libraryId}/metadata-fields`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLibraryMetadataField(
  libraryId: string,
  request: CreateLibraryMetadataFieldRequest,
): Promise<LibraryMetadataFieldResponse> {
  try {
    const { data } = await client.post<LibraryMetadataFieldResponse>(
      `/v1/libraries/${libraryId}/metadata-fields`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLibraryMetadataField(
  libraryId: string,
  fieldKey: string,
  request: UpdateLibraryMetadataFieldRequest,
): Promise<LibraryMetadataFieldResponse> {
  try {
    const { data } = await client.put<LibraryMetadataFieldResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Retires the field and works its documents off in Chargen. 204 means the field is already gone,
 * 202 carries the remaining work - the caller then continues with
 * {@link runLibraryMetadataSchemaChanges}.
 */
export async function deleteLibraryMetadataField(
  libraryId: string,
  fieldKey: string,
): Promise<LibraryMetadataSchemaRunResponse | null> {
  try {
    const { data, status } = await client.delete<LibraryMetadataSchemaRunResponse | ''>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}`,
    )
    return status === 202 && data ? data : null
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * One Charge of the library's pending value mappings and field deletions, repeated until
 * `complete` exactly like the Nachlauf batches: stopping the repetition is the pause, the next
 * call the resumption, and a Charge over documents already rewritten does nothing.
 */
export async function runLibraryMetadataSchemaChanges(
  libraryId: string,
  batchSize?: number,
): Promise<LibraryMetadataSchemaRunResponse> {
  try {
    const { data } = await client.post<LibraryMetadataSchemaRunResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/schema-changes/run`,
      batchSize == null ? {} : { batchSize },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The Folgekosten of a deletion: how many documents carry a value for this field. */
export async function getLibraryMetadataFieldUsage(
  libraryId: string,
  fieldKey: string,
): Promise<MetadataFieldUsageResponse> {
  try {
    const { data } = await client.get<MetadataFieldUsageResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}/usage`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function addLibraryMetadataFieldValue(
  libraryId: string,
  fieldKey: string,
  request: LibraryMetadataFieldValueRequest,
): Promise<LibraryMetadataFieldResponse> {
  try {
    const { data } = await client.post<LibraryMetadataFieldResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}/values`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function relabelLibraryMetadataFieldValue(
  libraryId: string,
  fieldKey: string,
  code: string,
  label: string,
): Promise<LibraryMetadataFieldResponse> {
  try {
    const { data } = await client.patch<LibraryMetadataFieldResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}/values/${encodeURIComponent(code)}`,
      { label },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The number that stands before the confirmation of a mapping. */
export async function getLibraryMetadataFieldValueUsage(
  libraryId: string,
  fieldKey: string,
  code: string,
): Promise<MetadataFieldUsageResponse> {
  try {
    const { data } = await client.get<MetadataFieldUsageResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}/values/${encodeURIComponent(code)}/usage`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Removes a value together with its confirmed mapping; targetCode null maps onto "leer". */
export async function remapLibraryMetadataFieldValue(
  libraryId: string,
  fieldKey: string,
  code: string,
  targetCode: string | null,
): Promise<RemapLibraryMetadataFieldValueResponse> {
  try {
    const { data } = await client.post<RemapLibraryMetadataFieldValueResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/${fieldKey}/values/${encodeURIComponent(code)}/remap`,
      { targetCode },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The Folgekosten of a planned schema change, before it is saved: documents, chunks, embedding
 * calls and expected runtime. Read-only - asking changes nothing.
 */
export async function getMetadataChangeImpact(
  libraryId: string,
  fieldKey: string,
  change: MetadataChangeKind,
  valueCode?: string,
): Promise<MetadataChangeImpactResponse> {
  try {
    const { data } = await client.get<MetadataChangeImpactResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/change-impact`,
      { params: { fieldKey, change, ...(valueCode ? { valueCode } : {}) } },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Switches the Kontextpräfix-Wirkstelle of the two switchable core fields. Saving moves no
 * bestand; the Nachlauf is started separately on the administration page.
 */
export async function updateCoreContextPrefix(
  libraryId: string,
  request: CoreContextPrefixRequest,
): Promise<CoreContextPrefixResponse> {
  try {
    const { data } = await client.put<CoreContextPrefixResponse>(
      `/v1/libraries/${libraryId}/metadata-fields/core-context-prefix`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getDocumentTypeVocabulary(): Promise<DocumentTypeVocabularyResponse> {
  try {
    const { data } = await client.get<DocumentTypeVocabularyResponse>('/v1/metadata/document-types')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
