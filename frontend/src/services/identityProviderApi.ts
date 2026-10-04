import type {
  OidcProviderImpactResponse,
  OidcProviderOrderRequest,
  OidcProviderRequest,
  OidcProviderResponse,
  OidcProviderTestRequest,
  OidcProviderTestResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// identity providers (ADR-0025, #1329 admin API) - SYSTEM_ADMIN only. Public clients: there is
// no secret anywhere in these payloads.
export async function getOidcProviders(): Promise<OidcProviderResponse[]> {
  try {
    const { data } = await client.get<OidcProviderResponse[]>('/v1/admin/oidc-providers')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createOidcProvider(
  request: OidcProviderRequest,
): Promise<OidcProviderResponse> {
  try {
    const { data } = await client.post<OidcProviderResponse>('/v1/admin/oidc-providers', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateOidcProvider(
  providerId: string,
  request: OidcProviderRequest,
): Promise<OidcProviderResponse> {
  try {
    const { data } = await client.put<OidcProviderResponse>(
      `/v1/admin/oidc-providers/${providerId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The confirmations a switch-off or a deletion may need; only the set ones travel. */
export interface ProviderShutdownConfirmations {
  /** The last *enabled* OIDC provider: afterwards only local accounts can sign in (ADR-0033). */
  acknowledgeLastProvider?: boolean
  /** What happens to persons' connected accounts, as {@link getOidcProviderImpact} names it. */
  confirmConnections?: boolean
}

function confirmationParams({
  acknowledgeLastProvider,
  confirmConnections,
}: ProviderShutdownConfirmations) {
  const params = {
    ...(acknowledgeLastProvider ? { acknowledgeLastProvider: true } : {}),
    ...(confirmConnections ? { confirmConnections: true } : {}),
  }
  return Object.keys(params).length > 0 ? params : undefined
}

/** What disabling or deleting the provider does to persons' connected accounts (ADR-0041). */
export async function getOidcProviderImpact(
  providerId: string,
): Promise<OidcProviderImpactResponse> {
  try {
    const { data } = await client.get<OidcProviderImpactResponse>(
      `/v1/admin/oidc-providers/${providerId}/impact`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Without the confirmations it needs the backend answers 409 (`LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED`,
 * `PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED`).
 */
export async function deleteOidcProvider(
  providerId: string,
  confirmations: ProviderShutdownConfirmations = {},
): Promise<void> {
  try {
    await client.delete(`/v1/admin/oidc-providers/${providerId}`, {
      params: confirmationParams(confirmations),
    })
  } catch (err) {
    normalizeError(err)
  }
}

/** Disabling needs the same confirmations as deleting; enabling none. */
export async function setOidcProviderEnabled(
  providerId: string,
  enabled: boolean,
  confirmations: ProviderShutdownConfirmations = {},
): Promise<OidcProviderResponse> {
  try {
    const { data } = await client.post<OidcProviderResponse>(
      `/v1/admin/oidc-providers/${providerId}/${enabled ? 'enable' : 'disable'}`,
      null,
      { params: enabled ? undefined : confirmationParams(confirmations) },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function makeOidcProviderDefault(providerId: string): Promise<OidcProviderResponse> {
  try {
    const { data } = await client.post<OidcProviderResponse>(
      `/v1/admin/oidc-providers/${providerId}/default`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function reorderOidcProviders(providerIds: string[]): Promise<OidcProviderResponse[]> {
  try {
    const { data } = await client.put<OidcProviderResponse[]>('/v1/admin/oidc-providers/order', {
      providerIds,
    } satisfies OidcProviderOrderRequest)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testOidcProvider(
  request: OidcProviderTestRequest,
): Promise<OidcProviderTestResponse> {
  try {
    const { data } = await client.post<OidcProviderTestResponse>(
      '/v1/admin/oidc-providers/test',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
