import { AxiosError } from 'axios'
import type {
  ConnectionProfileCreateRequest,
  ConnectionProfileImpactResponse,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
} from '../types/api'
import { isErrorResponse } from '../types/api'
import { apiClient as client, normalizeError } from './api'

const ADMIN = '/v1/admin/connection-profiles'

/** The code of a change that would discard secrets and needs a confirmation first. */
export const CONFIRMATION_REQUIRED = 'CONNECTION_PROFILE_CONFIRMATION_REQUIRED'

/** Whether `err` is the refusal of a change that discards secrets without confirmation. */
export function needsConfirmation(err: unknown): boolean {
  const cause = err instanceof Error ? err.cause : undefined
  if (!(cause instanceof AxiosError)) return false
  const data: unknown = cause.response?.data
  return isErrorResponse(data) && data.code === CONFIRMATION_REQUIRED
}

// Connection profiles ("Zugänge", #2160) - SYSTEM_ADMIN only. The client secret is write-only:
// a response carries clientSecretSet, never the value.
export async function listConnectionProfiles(): Promise<ConnectionProfileResponse[]> {
  try {
    const { data } = await client.get<ConnectionProfileResponse[]>(ADMIN)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createConnectionProfile(
  request: ConnectionProfileCreateRequest,
): Promise<ConnectionProfileResponse> {
  try {
    const { data } = await client.post<ConnectionProfileResponse>(ADMIN, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateConnectionProfile(
  profileId: string,
  request: ConnectionProfileUpdateRequest,
): Promise<ConnectionProfileResponse> {
  try {
    const { data } = await client.put<ConnectionProfileResponse>(`${ADMIN}/${profileId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteConnectionProfile(profileId: string): Promise<void> {
  try {
    await client.delete(`${ADMIN}/${profileId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getConnectionProfileImpact(
  profileId: string,
): Promise<ConnectionProfileImpactResponse> {
  try {
    const { data } = await client.get<ConnectionProfileImpactResponse>(
      `${ADMIN}/${profileId}/impact`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function disconnectAllConnections(
  profileId: string,
): Promise<ConnectionProfileImpactResponse> {
  try {
    const { data } = await client.post<ConnectionProfileImpactResponse>(
      `${ADMIN}/${profileId}/disconnect-all`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
