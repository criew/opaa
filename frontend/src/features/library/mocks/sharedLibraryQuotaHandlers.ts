import { http, HttpResponse } from 'msw'
import type { SharedLibraryQuotaRequest, SharedLibraryQuotaResponse } from '@/shared/api/types'
import {
  MOCK_LIBRARY_QUOTA_CAP_BYTES,
  MOCK_SHARED_LIBRARY_USAGE_BYTES,
} from './sharedLibraryQuotaFixtures'

/** The administration's own limit; `null` while the upper limit applies alone. */
let ownQuotaBytes: number | null = null

function quotaResponse(): SharedLibraryQuotaResponse {
  const quotaBytes =
    ownQuotaBytes === null
      ? MOCK_LIBRARY_QUOTA_CAP_BYTES
      : Math.min(ownQuotaBytes, MOCK_LIBRARY_QUOTA_CAP_BYTES)
  return {
    quotaBytes,
    ownQuotaBytes,
    capBytes: MOCK_LIBRARY_QUOTA_CAP_BYTES,
    librariesOverQuota: MOCK_SHARED_LIBRARY_USAGE_BYTES.filter((used) => used > quotaBytes).length,
  }
}

export const sharedLibraryQuotaHandlers = [
  http.get('/api/v1/admin/shared-libraries/quota', () => HttpResponse.json(quotaResponse())),

  http.put('/api/v1/admin/shared-libraries/quota', async ({ request }) => {
    const body = (await request.json()) as SharedLibraryQuotaRequest
    const next = body.quotaBytes ?? null
    if (next !== null && (next < 1 || next > MOCK_LIBRARY_QUOTA_CAP_BYTES)) {
      return HttpResponse.json(
        {
          error:
            'Das Speicherkontingent darf die Obergrenze des Betriebs von 10 GB nicht überschreiten.',
          status: 400,
        },
        { status: 400 },
      )
    }
    ownQuotaBytes = next
    return HttpResponse.json(quotaResponse())
  }),
]
