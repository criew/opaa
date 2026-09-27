import type {
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

/**
 * `acknowledgeLastProvider` is the confirmation the backend demands for the last *enabled* OIDC
 * provider (409 `LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED` without it): afterwards only local
 * accounts can sign in (ADR-0033, Entscheidung 4).
 */
export async function deleteOidcProvider(
  providerId: string,
  acknowledgeLastProvider = false,
): Promise<void> {
  try {
    await client.delete(`/v1/admin/oidc-providers/${providerId}`, {
      params: acknowledgeLastProvider ? { acknowledgeLastProvider: true } : undefined,
    })
  } catch (err) {
    normalizeError(err)
  }
}

/** Disabling the last enabled OIDC provider needs the same acknowledgement as deleting it. */
export async function setOidcProviderEnabled(
  providerId: string,
  enabled: boolean,
  acknowledgeLastProvider = false,
): Promise<OidcProviderResponse> {
  try {
    const { data } = await client.post<OidcProviderResponse>(
      `/v1/admin/oidc-providers/${providerId}/${enabled ? 'enable' : 'disable'}`,
      null,
      {
        params: !enabled && acknowledgeLastProvider ? { acknowledgeLastProvider: true } : undefined,
      },
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
