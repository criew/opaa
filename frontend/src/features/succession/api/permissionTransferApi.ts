import type {
  GroupEffectsResponse,
  PermissionTransferPreviewRequest,
  PermissionTransferPreviewResponse,
  PermissionTransferRequest,
  PermissionTransferResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * The transfer of effects (ADR-0036 Entscheidung 10). The preview is mandatory: the
 * execution requires its `previewId` and an explicit confirmation.
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

/**
 * „Wo wirkt diese Gruppe": with `providerId` a provider's worklist, with
 * `groupIds` exactly the rows a list shows — without either, every group of the organization.
 */
export async function getGroupEffects(options?: {
  providerId?: string
  groupIds?: string[]
}): Promise<GroupEffectsResponse[]> {
  try {
    const { data } = await apiClient.get<GroupEffectsResponse[]>('/v1/admin/groups/effects', {
      params: {
        ...(options?.providerId ? { providerId: options.providerId } : {}),
        ...(options?.groupIds?.length ? { groupId: options.groupIds } : {}),
      },
      // A repeated parameter instead of comma or bracket form: `groupId=a&groupId=b` is what
      // Spring passes to a list binding.
      paramsSerializer: { indexes: null },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
