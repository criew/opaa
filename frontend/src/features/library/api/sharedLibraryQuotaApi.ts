import type { SharedLibraryQuotaResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const QUOTA = '/v1/admin/shared-libraries/quota'

/** The house-wide limit per shared library and the operator's upper limit (system administration only). */
export async function getSharedLibraryQuota(): Promise<SharedLibraryQuotaResponse> {
  try {
    const { data } = await client.get<SharedLibraryQuotaResponse>(QUOTA)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Sets the limit in bytes, at most the upper limit; `null` removes the own value. */
export async function updateSharedLibraryQuota(
  quotaBytes: number | null,
): Promise<SharedLibraryQuotaResponse> {
  try {
    const { data } = await client.put<SharedLibraryQuotaResponse>(QUOTA, { quotaBytes })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
