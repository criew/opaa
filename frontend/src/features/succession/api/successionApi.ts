import type { SuccessionKind, SuccessionListResponse } from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * The life cycle's operations list (ADR-0036 Entscheidung 6). The order is fixed
 * (oldest entry first), and there is deliberately no parameter for the previous owner or
 * the acting person — neither here nor in the interface.
 */
export async function getSuccessionEntries(
  kind: SuccessionKind,
  page: number,
  size: number,
): Promise<SuccessionListResponse> {
  try {
    const { data } = await apiClient.get<SuccessionListResponse>('/v1/admin/succession', {
      params: { kind, page, size },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
