import { http, HttpResponse } from 'msw'
import type {
  PrivateStorageQuotaRequest,
  PrivateStorageQuotaResponse,
  PrivateStorageSummaryResponse,
  PrivateStorageUsageResponse,
} from '../types/api'
import {
  MOCK_DEFAULT_PRIVATE_QUOTA_BYTES,
  mockMyPrivateStorage,
  mockPrivateStorageSummary,
} from './privateStorageFixtures'

/** The administration's own limit; `null` while the default applies. */
let ownQuotaBytes: number | null = null

function quotaResponse(): PrivateStorageQuotaResponse {
  return {
    quotaBytes: ownQuotaBytes ?? MOCK_DEFAULT_PRIVATE_QUOTA_BYTES,
    defaultQuotaBytes: MOCK_DEFAULT_PRIVATE_QUOTA_BYTES,
    overridden: ownQuotaBytes !== null,
  }
}

export const privateStorageHandlers = [
  http.get('/api/v1/me/private-storage', () =>
    HttpResponse.json<PrivateStorageUsageResponse>({
      ...mockMyPrivateStorage,
      quotaBytes: quotaResponse().quotaBytes,
    }),
  ),

  http.get('/api/v1/admin/private-libraries/quota', () => HttpResponse.json(quotaResponse())),

  http.put('/api/v1/admin/private-libraries/quota', async ({ request }) => {
    const body = (await request.json()) as PrivateStorageQuotaRequest
    const next = body.quotaBytes ?? null
    if (next !== null && next < 0) {
      return HttpResponse.json(
        { error: 'quotaBytes: muss größer-gleich 0 sein', status: 400 },
        { status: 400 },
      )
    }
    ownQuotaBytes = next
    return HttpResponse.json(quotaResponse())
  }),

  http.get('/api/v1/admin/private-libraries/summary', () =>
    HttpResponse.json<PrivateStorageSummaryResponse>({
      ...mockPrivateStorageSummary,
      quotaBytes: quotaResponse().quotaBytes,
    }),
  ),
]
