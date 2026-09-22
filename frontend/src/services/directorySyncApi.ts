import type {
  DirectoryConnectorRequest,
  DirectoryConnectorResponse,
  DirectoryConnectorTestRequest,
  DirectorySyncPendingPlanResponse,
  DirectorySyncReportResponse,
  DirectorySyncStatusResponse,
  OidcProviderDirectorySyncRequest,
  OidcProviderResponse,
  OidcProviderTestResponse,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * Der Verzeichnisabgleich je Anbieter (#1816, #1817, ADR-0036 Entscheidung 3). Eigene Datei statt
 * `api.ts`: Abgleich, Konnektor und Plan gehören zusammen und werden nur von der Verwaltung
 * gebraucht.
 */
export async function getDirectorySyncStatus(): Promise<DirectorySyncStatusResponse[]> {
  try {
    const { data } = await apiClient.get<DirectorySyncStatusResponse[]>(
      '/v1/admin/directory-sync/status',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setDirectorySync(
  providerId: string,
  request: OidcProviderDirectorySyncRequest,
): Promise<OidcProviderResponse> {
  try {
    const { data } = await apiClient.put<OidcProviderResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-sync`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function saveDirectoryConnector(
  providerId: string,
  request: DirectoryConnectorRequest,
): Promise<DirectoryConnectorResponse> {
  try {
    const { data } = await apiClient.put<DirectoryConnectorResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-connector`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteDirectoryConnector(providerId: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/admin/oidc-providers/${providerId}/directory-connector`)
  } catch (err) {
    normalizeError(err)
  }
}

/** Probiert den Zugang, ohne etwas zu speichern; das Ergebnis steht im Rumpf, nicht im Status. */
export async function testDirectoryConnector(
  providerId: string,
  request: DirectoryConnectorTestRequest,
): Promise<OidcProviderTestResponse> {
  try {
    const { data } = await apiClient.post<OidcProviderTestResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-connector/test`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function runDirectorySyncDryRun(
  providerId: string,
): Promise<DirectorySyncReportResponse> {
  try {
    const { data } = await apiClient.post<DirectorySyncReportResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-sync/dry-run`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function runDirectorySync(providerId: string): Promise<DirectorySyncReportResponse> {
  try {
    const { data } = await apiClient.post<DirectorySyncReportResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-sync/run`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Der ausstehende Plan des Anbieters; `null`, wenn keiner wartet (404 ist hier die Antwort). */
export async function getPendingPlan(
  providerId: string,
): Promise<DirectorySyncPendingPlanResponse | null> {
  try {
    const { data } = await apiClient.get<DirectorySyncPendingPlanResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-sync/pending-plan`,
    )
    return data
  } catch (err) {
    if (isNotFound(err)) return null
    normalizeError(err)
  }
}

export async function confirmPendingPlan(
  providerId: string,
  planId: string,
  reason: string,
): Promise<DirectorySyncReportResponse> {
  try {
    const { data } = await apiClient.post<DirectorySyncReportResponse>(
      `/v1/admin/oidc-providers/${providerId}/directory-sync/pending-plan/${planId}/confirm`,
      { reason },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function discardPendingPlan(
  providerId: string,
  planId: string,
  reason: string,
): Promise<void> {
  try {
    await apiClient.post(
      `/v1/admin/oidc-providers/${providerId}/directory-sync/pending-plan/${planId}/discard`,
      { reason },
    )
  } catch (err) {
    normalizeError(err)
  }
}

function isNotFound(err: unknown): boolean {
  const status = (err as { response?: { status?: number } } | null)?.response?.status
  return status === 404
}
