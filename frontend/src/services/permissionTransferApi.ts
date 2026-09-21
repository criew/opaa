import type {
  GroupEffectsResponse,
  PermissionTransferPreviewRequest,
  PermissionTransferPreviewResponse,
  PermissionTransferRequest,
  PermissionTransferResponse,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * Die Übertragung von Wirkungen (#1834, ADR-0036 Entscheidung 10). Die Vorschau ist Pflicht: Die
 * Ausführung verlangt deren `previewId` und eine ausdrückliche Bestätigung.
 */
export async function previewPermissionTransfer(
  request: PermissionTransferPreviewRequest,
): Promise<PermissionTransferPreviewResponse> {
  try {
    const { data } = await apiClient.post<PermissionTransferPreviewResponse>(
      '/v1/permission-transfers/preview',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function executePermissionTransfer(
  request: PermissionTransferRequest,
): Promise<PermissionTransferResponse> {
  try {
    const { data } = await apiClient.post<PermissionTransferResponse>(
      '/v1/permission-transfers',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** „Wo wirkt diese Gruppe" — ohne `providerId` alle Gruppen, mit ihr die Arbeitsliste (#1821). */
export async function getGroupEffects(providerId?: string): Promise<GroupEffectsResponse[]> {
  try {
    const { data } = await apiClient.get<GroupEffectsResponse[]>('/v1/admin/groups/effects', {
      params: providerId ? { providerId } : undefined,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
