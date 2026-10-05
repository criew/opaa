import type {
  PrivateStorageQuotaResponse,
  PrivateStorageSummaryResponse,
  PrivateStorageUsageResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

/** The caller's own use across all her private libraries and the limit in force (0 = unlimited). */
export async function getMyPrivateStorage(): Promise<PrivateStorageUsageResponse> {
  try {
    const { data } = await client.get<PrivateStorageUsageResponse>('/v1/me/private-storage')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

const QUOTA = '/v1/admin/private-libraries/quota'

/** The house-wide limit across all private libraries of a person (system administration only). */
export async function getPrivateStorageQuota(): Promise<PrivateStorageQuotaResponse> {
  try {
    const { data } = await client.get<PrivateStorageQuotaResponse>(QUOTA)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Sets the limit in bytes; `0` is unlimited, `null` returns to the configured default. */
export async function updatePrivateStorageQuota(
  quotaBytes: number | null,
): Promise<PrivateStorageQuotaResponse> {
  try {
    const { data } = await client.put<PrivateStorageQuotaResponse>(QUOTA, { quotaBytes })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The masked sums over the organization's private libraries (system administration only). */
export async function getPrivateStorageSummary(): Promise<PrivateStorageSummaryResponse> {
  try {
    const { data } = await client.get<PrivateStorageSummaryResponse>(
      '/v1/admin/private-libraries/summary',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
