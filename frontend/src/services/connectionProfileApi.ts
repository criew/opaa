import type {
  ConnectionProfileCreateRequest,
  ConnectionProfileImpactResponse,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
  ConnectorTypeStateResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'
import { apiErrorCode } from './apiErrorDetails'

const ADMIN = '/v1/admin/connection-profiles'

/** The code of a change that would discard secrets and needs a confirmation first. */
export const CONFIRMATION_REQUIRED = 'CONNECTION_PROFILE_CONFIRMATION_REQUIRED'

/** Whether `err` is the refusal of a change that discards secrets without confirmation. */
export function needsConfirmation(err: unknown): boolean {
  return apiErrorCode(err) === CONFIRMATION_REQUIRED
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

/** Locks or unlocks a profile: no new library, no run; the content stays searchable. */
export async function lockConnectionProfile(
  profileId: string,
  locked: boolean,
): Promise<ConnectionProfileResponse> {
  try {
    const { data } = await client.put<ConnectionProfileResponse>(`${ADMIN}/${profileId}/lock`, {
      locked,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Every connector type that reaches a source, with its lock. */
export async function listConnectorTypeStates(): Promise<ConnectorTypeStateResponse[]> {
  try {
    const { data } = await client.get<ConnectorTypeStateResponse[]>('/v1/admin/connector-types')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Locks or unlocks a connector type, for every library of it. */
export async function lockConnectorType(
  sourceType: string,
  locked: boolean,
): Promise<ConnectorTypeStateResponse> {
  try {
    const { data } = await client.put<ConnectorTypeStateResponse>(
      `/v1/admin/connector-types/${sourceType}/lock`,
      { locked },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
