import type {
  SuccessionCountsResponse,
  SuccessionKind,
  SuccessionListResponse,
  SuccessionSettingsRequest,
  SuccessionSettingsResponse,
} from '@/shared/api/types'
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

/** How many entries each tab holds - per kind of entry only, never per person. */
export async function getSuccessionCounts(): Promise<SuccessionCountsResponse> {
  try {
    const { data } = await apiClient.get<SuccessionCountsResponse>('/v1/admin/succession/counts')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** From which age an entry is highlighted, with factory default and bounds. */
export async function getSuccessionSettings(): Promise<SuccessionSettingsResponse> {
  try {
    const { data } = await apiClient.get<SuccessionSettingsResponse>(
      '/v1/admin/succession/settings',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Sets the aging threshold in months; `null` returns to the factory default. */
export async function updateSuccessionSettings(
  agingThresholdMonths: number | null,
): Promise<SuccessionSettingsResponse> {
  try {
    const body: SuccessionSettingsRequest = { agingThresholdMonths }
    const { data } = await apiClient.put<SuccessionSettingsResponse>(
      '/v1/admin/succession/settings',
      body,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
