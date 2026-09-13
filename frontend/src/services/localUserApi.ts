import type {
  LocalAuthSettingsResponse,
  LocalAuthSettingsUpdateRequest,
  LocalUserCreateRequest,
  LocalUserCreatedResponse,
  LocalUserGeneratedPasswordResponse,
  LocalUserHandoverResponse,
  LocalUserPasswordResetResponse,
  LocalUserResponse,
  LocalUserSummaryResponse,
  LocalUserUpdateRequest,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/** Rows per page; the backend refuses more than 50 (ADR-0033, Entscheidung 11). */
export const LOCAL_USER_PAGE_SIZE = 25

export async function getLocalUserSummary(): Promise<LocalUserSummaryResponse> {
  try {
    const { data } = await apiClient.get<LocalUserSummaryResponse>('/v1/admin/local-users/summary')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLocalUser(
  request: LocalUserCreateRequest,
): Promise<LocalUserCreatedResponse> {
  try {
    const { data } = await apiClient.post<LocalUserCreatedResponse>(
      '/v1/admin/local-users',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLocalUser(
  id: string,
  request: LocalUserUpdateRequest,
): Promise<LocalUserResponse> {
  try {
    const { data } = await apiClient.patch<LocalUserResponse>(
      `/v1/admin/local-users/${id}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLocalUser(id: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/admin/local-users/${id}`)
  } catch (err) {
    normalizeError(err)
  }
}

/** `reason` is a sentence for the person, quoted in the mail and never in the audit trail. */
export async function lockLocalUser(
  id: string,
  reason?: string | null,
): Promise<LocalUserResponse> {
  try {
    const { data } = await apiClient.post<LocalUserResponse>(`/v1/admin/local-users/${id}/lock`, {
      reason: reason?.trim() ? reason.trim() : null,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function unlockLocalUser(id: string): Promise<LocalUserResponse> {
  try {
    const { data } = await apiClient.post<LocalUserResponse>(`/v1/admin/local-users/${id}/unlock`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function requestLocalUserPasswordReset(
  id: string,
): Promise<LocalUserPasswordResetResponse> {
  try {
    const { data } = await apiClient.post<LocalUserPasswordResetResponse>(
      `/v1/admin/local-users/${id}/password-reset`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Starts the handover of a local account to a provider identity (ADR-0033, Entscheidung 12). The
 * administration names the provider and the reason and nothing else - the identity comes from the
 * person's own provider token when they redeem the link.
 */
export async function requestLocalUserHandover(
  id: string,
  providerId: string,
  reason: string,
): Promise<LocalUserHandoverResponse> {
  try {
    const { data } = await apiClient.post<LocalUserHandoverResponse>(
      `/v1/admin/local-users/${id}/handover`,
      { providerId, reason: reason.trim() },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function generateLocalUserPassword(
  id: string,
): Promise<LocalUserGeneratedPasswordResponse> {
  try {
    const { data } = await apiClient.post<LocalUserGeneratedPasswordResponse>(
      `/v1/admin/local-users/${id}/password`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLocalAuthSettings(): Promise<LocalAuthSettingsResponse> {
  try {
    const { data } = await apiClient.get<LocalAuthSettingsResponse>('/v1/admin/local-auth-settings')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Replaces the settings of the local account management. `enabled` is the switch itself; the
 * answer to a PUT that switched it off names how many accounts lost a session
 * (`revokedSessions`).
 */
export async function updateLocalAuthSettings(
  request: LocalAuthSettingsUpdateRequest,
): Promise<LocalAuthSettingsResponse> {
  try {
    const { data } = await apiClient.put<LocalAuthSettingsResponse>(
      '/v1/admin/local-auth-settings',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
