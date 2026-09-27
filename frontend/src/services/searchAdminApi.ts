import type {
  SearchStatusResponse,
  SearchDiagnosisContextResponse,
  SearchDiagnosisRequest,
  SearchDiagnosisResponse,
  ChunkInspectionResponse,
  DocumentChunksResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

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
