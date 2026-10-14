import type { AccessAsOfObjectType, AccessAsOfPage } from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/** The parameters of the point-in-time report — exactly one named object per query. */
export interface AccessAsOfQuery {
  objectType: AccessAsOfObjectType
  objectId: string
  from: string
  to: string
  reason: string
  page?: number
}

/**
 * Who was allowed to read this object in this period. The backend rejects a window that is too
 * wide and a response that is too large instead of truncating, and logs every request — the
 * rejected one as well. The page size is set server-side; paging uses `page` (at most page 49).
 */
export async function getAccessAsOf(query: AccessAsOfQuery): Promise<AccessAsOfPage> {
  try {
    const { data } = await apiClient.get<AccessAsOfPage>('/v1/audit/access-as-of', {
      params: {
        objectType: query.objectType,
        objectId: query.objectId,
        from: query.from,
        to: query.to,
        reason: query.reason,
        page: query.page ?? 0,
      },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
