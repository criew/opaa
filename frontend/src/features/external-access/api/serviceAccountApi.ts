import type {
  CreatedServiceAccountTokenResponse,
  LibraryServiceAccountConsentListResponse,
  LibraryServiceAccountConsentResponse,
  ServiceAccountChoiceResponse,
  ServiceAccountDetailResponse,
  ServiceAccountRequest,
  ServiceAccountResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

const ADMIN_PATH = '/v1/admin/service-accounts'

export async function listServiceAccounts(): Promise<ServiceAccountResponse[]> {
  try {
    const { data } = await apiClient.get<{ serviceAccounts: ServiceAccountResponse[] }>(ADMIN_PATH)
    return data.serviceAccounts
  } catch (err) {
    normalizeError(err)
  }
}

export async function getServiceAccount(id: string): Promise<ServiceAccountDetailResponse> {
  try {
    const { data } = await apiClient.get<ServiceAccountDetailResponse>(`${ADMIN_PATH}/${id}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createServiceAccount(
  request: ServiceAccountRequest,
): Promise<ServiceAccountResponse> {
  try {
    const { data } = await apiClient.post<ServiceAccountResponse>(ADMIN_PATH, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateServiceAccount(
  id: string,
  request: ServiceAccountRequest,
): Promise<ServiceAccountResponse> {
  try {
    const { data } = await apiClient.put<ServiceAccountResponse>(`${ADMIN_PATH}/${id}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deactivateServiceAccount(id: string): Promise<ServiceAccountResponse> {
  try {
    const { data } = await apiClient.post<ServiceAccountResponse>(`${ADMIN_PATH}/${id}/deactivate`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createServiceAccountToken(
  id: string,
  name: string,
  expiresAt: string,
): Promise<CreatedServiceAccountTokenResponse> {
  try {
    const { data } = await apiClient.post<CreatedServiceAccountTokenResponse>(
      `${ADMIN_PATH}/${id}/tokens`,
      { name, expiresAt },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeServiceAccountToken(id: string, tokenId: string): Promise<void> {
  try {
    await apiClient.delete(`${ADMIN_PATH}/${id}/tokens/${tokenId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function listLibraryServiceAccountConsents(
  libraryId: string,
): Promise<LibraryServiceAccountConsentListResponse> {
  try {
    const { data } = await apiClient.get<LibraryServiceAccountConsentListResponse>(
      `/v1/libraries/${libraryId}/service-accounts`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setLibraryServiceAccountConsent(
  libraryId: string,
  serviceAccountId: string,
  expiresAt: string,
): Promise<LibraryServiceAccountConsentResponse> {
  try {
    const { data } = await apiClient.put<LibraryServiceAccountConsentResponse>(
      `/v1/libraries/${libraryId}/service-accounts/${serviceAccountId}`,
      { expiresAt },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeLibraryServiceAccountConsent(
  libraryId: string,
  serviceAccountId: string,
): Promise<void> {
  try {
    await apiClient.delete(`/v1/libraries/${libraryId}/service-accounts/${serviceAccountId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function listServiceAccountChoices(
  libraryId: string,
): Promise<ServiceAccountChoiceResponse[]> {
  try {
    const { data } = await apiClient.get<{ serviceAccounts: ServiceAccountChoiceResponse[] }>(
      `/v1/libraries/${libraryId}/service-account-choices`,
    )
    return data.serviceAccounts
  } catch (err) {
    normalizeError(err)
  }
}
