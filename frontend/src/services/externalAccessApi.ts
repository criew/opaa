import type {
  AdminExternalAccessTokenResponse,
  EligibleExternalAccessLibraryResponse,
  CreateExternalAccessTokenRequest,
  CreatedExternalAccessTokenResponse,
  ExternalAccessChannelInfoResponse,
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
  ExternalAccessTokenStatus,
  OwnExternalAccessTokenResponse,
} from '../types/api'
import { apiClient, normalizeError } from './api'

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
 * Die zwei Kanalwerte, die eine Person für ihre eigenen Tokens braucht: ob der Kanal offen ist und
 * wie lange ein Token höchstens gelten darf. Die übrigen Kanaleinstellungen bleiben der
 * Systemverwaltung vorbehalten.
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
 * Genau die Bibliotheken, die eine Ausstellung annimmt - lesbar und freigegeben. Bei geschlossenem
 * Kanal leer, weil dann auch keine Ausstellung angenommen würde.
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

/** Die Antwort trägt den Klartextwert — hier und in keiner anderen, je. */
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
 * Die Bestandsliste der Systemverwaltung. Gefiltert wird ausschließlich über Zustand und Ablauf —
 * einen Filter nach Person gibt es bewusst nicht.
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

/** Sperrt jedes noch wirksame Token einer Person; gibt zurück, wie viele das waren. */
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
