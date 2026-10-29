import type { AuditorRoleRequestResponse } from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/** The open requests of the system administration for the AUDITOR role, oldest first. */
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

/** Grants the requested role; the backend refuses with 409 when it can no longer be granted. */
export async function confirmAuditorRoleRequest(id: string): Promise<void> {
  try {
    await apiClient.post(`/v1/audit/auditor-role-requests/${id}/confirm`)
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
