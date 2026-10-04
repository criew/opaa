import type {
  ConnectionProfileCreateRequest,
  ConnectionProfileImpactResponse,
  ConnectionProfileOption,
  ConnectionProfileRequestCreateRequest,
  ConnectionProfileRequestPageResponse,
  ConnectionProfileRequestResolveRequest,
  ConnectionProfileRequestResponse,
  ConnectionProfileRequestState,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
  ConnectorProfileRequirementRequest,
  ConnectorProfileRequirementResponse,
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

/** The code of a change the connector refuses for at least one library on the profile. */
export const CHANGE_REJECTED = 'CONNECTION_PROFILE_CHANGE_REJECTED'

/** Whether `err` is the refusal of a change by the connector of a library on the profile. */
export function changeRejected(err: unknown): boolean {
  return apiErrorCode(err) === CHANGE_REJECTED
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

/**
 * What `request` - the body of an update - would do to the libraries on the profile, changing
 * nothing: the counts and every library whose connector refuses the change.
 */
export async function previewConnectionProfileChange(
  profileId: string,
  request: ConnectionProfileUpdateRequest,
): Promise<ConnectionProfileImpactResponse> {
  try {
    const { data } = await client.post<ConnectionProfileImpactResponse>(
      `${ADMIN}/${profileId}/impact`,
      request,
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

/**
 * The profiles a library of `sourceType` may be connected through - also the ones the caller may
 * not use, each with `creatable` and, where not, a notice naming who can change that. With
 * `libraryId` the managers of that library may ask without the right to create a library.
 */
export async function listConnectionProfileOptions(
  sourceType: string,
  libraryId?: string,
): Promise<ConnectionProfileOption[]> {
  try {
    const { data } = await client.get<ConnectionProfileOption[]>('/v1/connection-profiles', {
      params: libraryId ? { sourceType, libraryId } : { sourceType },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Whether the type may be made usable only through profiles, and whom switching it on affects. */
export async function getConnectorProfileRequirement(
  sourceType: string,
): Promise<ConnectorProfileRequirementResponse> {
  try {
    const { data } = await client.get<ConnectorProfileRequirementResponse>(
      `/v1/admin/connector-types/${sourceType}/profile-requirement`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Switches "Nur über Zugänge" on (with the choice for the libraries with their own address) or
 * off (without one); sent again while on, it changes that choice.
 */
export async function setConnectorProfileRequirement(
  sourceType: string,
  request: ConnectorProfileRequirementRequest,
): Promise<ConnectorTypeStateResponse> {
  try {
    const { data } = await client.put<ConnectorTypeStateResponse>(
      `/v1/admin/connector-types/${sourceType}/profile-requirement`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** A submitted request; `created` is false when the same request was already open. */
export interface ConnectionProfileRequestSubmission {
  request: ConnectionProfileRequestResponse
  created: boolean
}

/** Asks the system administration for a profile ("Zugangswunsch"). */
export async function submitConnectionProfileRequest(
  request: ConnectionProfileRequestCreateRequest,
): Promise<ConnectionProfileRequestSubmission> {
  try {
    const response = await client.post<ConnectionProfileRequestResponse>(
      '/v1/connection-profile-requests',
      request,
    )
    return { request: response.data, created: response.status === 201 }
  } catch (err) {
    normalizeError(err)
  }
}

/** The caller's own requests, newest first. */
export async function listMyConnectionProfileRequests(): Promise<
  ConnectionProfileRequestResponse[]
> {
  try {
    const { data } = await client.get<ConnectionProfileRequestResponse[]>(
      '/v1/me/connection-profile-requests',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** One page of the organization's requests, oldest first - SYSTEM_ADMIN only. */
export async function listConnectionProfileRequests(
  state: ConnectionProfileRequestState | null,
  page = 0,
  size = 25,
): Promise<ConnectionProfileRequestPageResponse> {
  try {
    const { data } = await client.get<ConnectionProfileRequestPageResponse>(
      '/v1/admin/connection-profile-requests',
      { params: { ...(state ? { state } : {}), page, size } },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Marks an open request done or declined - SYSTEM_ADMIN only. */
export async function resolveConnectionProfileRequest(
  requestId: string,
  request: ConnectionProfileRequestResolveRequest,
): Promise<ConnectionProfileRequestResponse> {
  try {
    const { data } = await client.put<ConnectionProfileRequestResponse>(
      `/v1/admin/connection-profile-requests/${requestId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
