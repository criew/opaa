import type {
  AuditorRoleConfirmationResponse,
  AuditorRoleRequestResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/** The open requests for the AUDITOR role, oldest first. */
export async function listAuditorRoleRequests(): Promise<AuditorRoleRequestResponse[]> {
  try {
    const { data } = await apiClient.get<AuditorRoleRequestResponse[]>(
      '/v1/audit/auditor-role-requests',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Grants the requested role and answers the one link that sets the password of a local account -
 * `null` for an account of an identity provider. The backend refuses with 409 when the role can no
 * longer be granted.
 */
export async function confirmAuditorRoleRequest(id: string): Promise<string | null> {
  try {
    const { data } = await apiClient.post<AuditorRoleConfirmationResponse>(
      `/v1/audit/auditor-role-requests/${id}/confirm`,
    )
    return data.setupUrl
  } catch (err) {
    normalizeError(err)
  }
}

/** Ends the request without the role. */
export async function declineAuditorRoleRequest(id: string): Promise<void> {
  try {
    await apiClient.post(`/v1/audit/auditor-role-requests/${id}/decline`)
  } catch (err) {
    normalizeError(err)
  }
}
