import type {
  Capability,
  CapabilityGrantRequest,
  CapabilityGrantResponse,
  CapabilityOverviewResponse,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * Die Anlegerechte der Installation (ADR-0036, Entscheidung 5). Die Übersicht liefert je Recht
 * eine Klartextzeile und die berechtigten Subjekte — auch für Rechte, die niemand hält.
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
