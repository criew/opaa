import type {
  SearchStatusResponse,
  SearchDiagnosisContextResponse,
  SearchDiagnosisRequest,
  SearchDiagnosisResponse,
  ChunkInspectionResponse,
  DocumentChunksResponse,
  ContextPrefixRerunRequest,
  ContextPrefixRerunResponse,
  EmbeddingReindexStatusResponse,
  MetadataBackfillRequest,
  MetadataBackfillResponse,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

export async function getSearchStatus(): Promise<SearchStatusResponse> {
  try {
    const { data } = await client.get<SearchStatusResponse>('/v1/admin/search/status')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The rights contexts this administrator may choose between - the profiles, and whether they hold
 * the "Sicht als" befugnis for the person context. The answer only shapes the form; the diagnosis
 * endpoint checks the befugnis again on every run.
 */
export async function getSearchDiagnosisContext(): Promise<SearchDiagnosisContextResponse> {
  try {
    const { data } = await client.get<SearchDiagnosisContextResponse>(
      '/v1/admin/search/diagnosis-context',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Runs one diagnosis. A POST because a test question does not belong in a URL - the call writes
 * nothing, and there is no field in the request that could name an existing chat.
 */
export async function runSearchDiagnosis(
  request: SearchDiagnosisRequest,
): Promise<SearchDiagnosisResponse> {
  try {
    const { data } = await client.post<SearchDiagnosisResponse>(
      '/v1/admin/search/diagnosis',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** One stored chunk as the index holds it - text and metadata, never the embedding. */
export async function getSearchChunk(chunkId: string): Promise<ChunkInspectionResponse> {
  try {
    const { data } = await client.get<ChunkInspectionResponse>(
      `/v1/admin/search/chunks/${encodeURIComponent(chunkId)}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Every stored chunk of one document in chunk order, with the count the document entity recorded. */
export async function getDocumentChunks(documentId: string): Promise<DocumentChunksResponse> {
  try {
    const { data } = await client.get<DocumentChunksResponse>(
      `/v1/admin/search/documents/${encodeURIComponent(documentId)}/chunks`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * One batch of the deterministic core-metadata backfill of a library. Repeated until
 * `done`; the remaining work is re-derived server-side on every call, so stopping the repetition
 * is the pause and the next call the resumption.
 */
export async function runMetadataBackfillBatch(
  request: MetadataBackfillRequest,
): Promise<MetadataBackfillResponse> {
  try {
    const { data } = await client.post<MetadataBackfillResponse>(
      '/v1/admin/indexing/metadata-backfill',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * One batch of the Kontextpräfix-Nachlauf of a library, repeated until `done` exactly like the
 * backfill above: stopping the repetition is the pause, the next call the resumption.
 */
export async function runContextPrefixRerunBatch(
  request: ContextPrefixRerunRequest,
): Promise<ContextPrefixRerunResponse> {
  try {
    const { data } = await client.post<ContextPrefixRerunResponse>(
      '/v1/admin/indexing/context-prefix-rerun',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** the vector index against the configured embedding model, and its re-embedding. */
export async function getEmbeddingReindexStatus(): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.get<EmbeddingReindexStatusResponse>(
      '/v1/admin/indexing/embedding-reindex',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** starts or resumes the re-embedding; it runs in the background on the server. */
export async function startEmbeddingReindex(): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.post<EmbeddingReindexStatusResponse>(
      '/v1/admin/indexing/embedding-reindex',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** stops the running re-embedding; what it embedded is kept for the next start. */
export async function cancelEmbeddingReindex(): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.delete<EmbeddingReindexStatusResponse>(
      '/v1/admin/indexing/embedding-reindex',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
