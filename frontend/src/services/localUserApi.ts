import type {
  LocalAuthSettingsResponse,
  LocalAuthSettingsUpdateRequest,
  LocalAccountState,
  LocalUserCreateRequest,
  LocalUserCreatedResponse,
  LocalUserGeneratedPasswordResponse,
  LocalUserPageResponse,
  LocalUserPasswordResetResponse,
  LocalUserResponse,
  LocalUserSummaryResponse,
  LocalUserUpdateRequest,
  SystemRole,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/** The four sort fields the backend allows; activity is deliberately not one of them (ADR-0033). */
export type LocalUserSortField = 'displayName' | 'email' | 'expiresAt' | 'createdAt'

export interface LocalUserQuery {
  /** Substring of address or display name; empty means no restriction. */
  query?: string
  status?: LocalAccountState | null
  role?: SystemRole | null
  withoutExpiry?: boolean
  inactive?: boolean
  sort?: LocalUserSortField
  direction?: 'asc' | 'desc'
  page?: number
  size?: number
}

/** Rows per page; the backend refuses more than 50 (ADR-0033, Entscheidung 11). */
export const LOCAL_USER_PAGE_SIZE = 25

function queryString(query: LocalUserQuery): string {
  const params = new URLSearchParams()
  if (query.query) params.set('query', query.query)
  if (query.status) params.set('status', query.status)
  if (query.role) params.set('role', query.role)
  if (query.withoutExpiry) params.set('withoutExpiry', 'true')
  if (query.inactive) params.set('inactive', 'true')
  params.set('sort', query.sort ?? 'displayName')
  params.set('direction', query.direction ?? 'asc')
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? LOCAL_USER_PAGE_SIZE))
  return params.toString()
}

/**
 * The account list of the local user management (#1537/#1541). Lists local accounts only - never
 * an account of an OIDC provider - and carries no activity timestamp and no export.
 */
export async function listLocalUsers(query: LocalUserQuery): Promise<LocalUserPageResponse> {
  try {
    const { data } = await apiClient.get<LocalUserPageResponse>(
      `/v1/admin/local-users?${queryString(query)}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

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
