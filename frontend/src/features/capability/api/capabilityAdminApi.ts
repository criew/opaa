import type {
  Capability,
  CapabilityGrantRequest,
  CapabilityGrantResponse,
  CapabilityOverviewResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * The installation's creation rights (ADR-0036, Entscheidung 5). The overview returns per right
 * a plain-text line and the entitled subjects — also for rights nobody holds.
 */
export async function getCapabilityOverview(): Promise<CapabilityOverviewResponse[]> {
  try {
    const { data } = await apiClient.get<CapabilityOverviewResponse[]>('/v1/admin/capabilities')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function grantCapability(
  capability: Capability,
  request: CapabilityGrantRequest,
): Promise<CapabilityGrantResponse> {
  try {
    const { data } = await apiClient.post<CapabilityGrantResponse>(
      `/v1/admin/capabilities/${capability}/grants`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeCapability(capability: Capability, grantId: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/admin/capabilities/${capability}/grants/${grantId}`)
  } catch (err) {
    normalizeError(err)
  }
}
