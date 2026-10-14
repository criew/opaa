import type {
  AdminExternalAccessTokenResponse,
  EligibleExternalAccessLibraryResponse,
  ExternalAccessLibraryResponse,
  CreateExternalAccessTokenRequest,
  CreatedExternalAccessTokenResponse,
  ExternalAccessChannelInfoResponse,
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
  ExternalAccessTokenStatus,
  LibraryExternalAccessResponse,
  OwnExternalAccessTokenResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

const PATH = '/v1/system/external-access'
const TOKENS_PATH = '/v1/external-access/tokens'
const ADMIN_TOKENS_PATH = '/v1/admin/external-access/tokens'

export async function getExternalAccessSettings(): Promise<ExternalAccessSettingsResponse> {
  try {
    const { data } = await apiClient.get<ExternalAccessSettingsResponse>(PATH)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The endpoint replaces the settings wholesale, so every value travels along. */
export async function updateExternalAccessSettings(
  request: ExternalAccessSettingsUpdateRequest,
): Promise<ExternalAccessSettingsResponse> {
  try {
    const { data } = await apiClient.put<ExternalAccessSettingsResponse>(PATH, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The two channel values a person needs for their own tokens: whether the channel is open and
 * how long a token may be valid at most. The remaining channel settings stay reserved for the
 * system administration.
 */
export async function getExternalAccessChannelInfo(): Promise<ExternalAccessChannelInfoResponse> {
  try {
    const { data } = await apiClient.get<ExternalAccessChannelInfoResponse>(
      '/v1/external-access/settings',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Exactly the libraries an issuance accepts - readable and released. Empty when the channel is
 * closed, because then no issuance would be accepted either.
 */
export async function listEligibleExternalAccessLibraries(): Promise<
  EligibleExternalAccessLibraryResponse[]
> {
  try {
    const { data } = await apiClient.get<{
      libraries: EligibleExternalAccessLibraryResponse[]
    }>('/v1/external-access/eligible-libraries')
    return data.libraries
  } catch (err) {
    normalizeError(err)
  }
}

export async function listOwnExternalAccessTokens(): Promise<OwnExternalAccessTokenResponse[]> {
  try {
    const { data } = await apiClient.get<{ tokens: OwnExternalAccessTokenResponse[] }>(TOKENS_PATH)
    return data.tokens
  } catch (err) {
    normalizeError(err)
  }
}

/** The response carries the plain-text value — here and in no other, ever. */
export async function createExternalAccessToken(
  request: CreateExternalAccessTokenRequest,
): Promise<CreatedExternalAccessTokenResponse> {
  try {
    const { data } = await apiClient.post<CreatedExternalAccessTokenResponse>(TOKENS_PATH, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeOwnExternalAccessToken(tokenId: string): Promise<void> {
  try {
    await apiClient.delete(`${TOKENS_PATH}/${tokenId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The system administration's inventory list. Filtering is by state and expiry only —
 * a filter by person deliberately does not exist.
 */
export async function listAllExternalAccessTokens(filter: {
  status?: ExternalAccessTokenStatus
  expiringWithinDays?: number
}): Promise<AdminExternalAccessTokenResponse[]> {
  try {
    const { data } = await apiClient.get<{ tokens: AdminExternalAccessTokenResponse[] }>(
      ADMIN_TOKENS_PATH,
      {
        params: {
          status: filter.status,
          expiringWithinDays: filter.expiringWithinDays,
        },
      },
    )
    return data.tokens
  } catch (err) {
    normalizeError(err)
  }
}

export async function blockExternalAccessToken(tokenId: string): Promise<void> {
  try {
    await apiClient.post(`${ADMIN_TOKENS_PATH}/${tokenId}/block`)
  } catch (err) {
    normalizeError(err)
  }
}

/** Revokes every still effective token of a person; returns how many that were. */
export async function blockExternalAccessTokensOfOwner(ownerUserId: string): Promise<number> {
  try {
    const { data } = await apiClient.post<{ blocked: number }>(
      `${ADMIN_TOKENS_PATH}/block-by-owner`,
      { ownerUserId },
    )
    return data.blocked
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Sets or withdraws the library's release for external access. `expiresAt` is
 * mandatory when setting and must not be passed when withdrawing - the backend rejects both
 * instead of silently completing them.
 */
export async function updateLibraryExternalAccess(
  libraryId: string,
  enabled: boolean,
  expiresAt: string | null,
): Promise<LibraryExternalAccessResponse> {
  try {
    const { data } = await apiClient.put<LibraryExternalAccessResponse>(
      `/v1/libraries/${libraryId}/external-access`,
      enabled ? { enabled, expiresAt } : { enabled },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The libraries currently released for external access (system administration only). */
export async function getExternalAccessLibraries(): Promise<ExternalAccessLibraryResponse[]> {
  try {
    const { data } = await apiClient.get<ExternalAccessLibraryResponse[]>(
      '/v1/admin/external-access/libraries',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
