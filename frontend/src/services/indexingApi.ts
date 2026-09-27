import type {
  ContextPrefixRerunRequest,
  ContextPrefixRerunResponse,
  MetadataBackfillRequest,
  MetadataBackfillResponse,
  IndexingRunListResponse,
  IndexingStatusResponse,
  IndexingRunMode,
  PushSecretResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// the trigger reduces to "index this library" - sourceType and every typed configuration
// field (url/proxy/credentials/insecureSsl) now live on the library itself (ADR-0018) and are no
// longer sent from the frontend.
/**
 * Starts a run; `runMode` (ADR-0023, Entscheidung 4) is optional - without it the backend picks
 * the library's own default (the only mode of a one-mode source type, or for Confluence the mode
 * its sync state calls for).
 */
export async function triggerIndexing(
  libraryId: string,
  runMode?: IndexingRunMode,
): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>(
      `/v1/libraries/${libraryId}/indexing`,
      undefined,
      runMode ? { params: { runMode } } : undefined,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * generates or rotates the push secret of a library whose connector offers a push intake
 * (ADR-0038). The secret is returned exactly once, together with the path the source notifies -
 * the caller shows it, the API never returns it again.
 */
export async function generatePushSecret(libraryId: string): Promise<PushSecretResponse> {
  try {
    const { data } = await client.post<PushSecretResponse>(`/v1/libraries/${libraryId}/push-secret`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** removes the push secret - the library's push intake rejects every call from now on. */
export async function removePushSecret(libraryId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/push-secret`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getIndexingStatus(libraryId: string): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.get<IndexingStatusResponse>(
      `/v1/libraries/${libraryId}/indexing/status`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// the last 10 runs for a library, each with its own protocol of skipped/rejected items and
// errors - distinct from getIndexingStatus above, which only ever names the single current/most
// recent run.
export async function getIndexingRuns(libraryId: string): Promise<IndexingRunListResponse> {
  try {
    const { data } = await client.get<IndexingRunListResponse>(
      `/v1/libraries/${libraryId}/indexing/runs`,
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
