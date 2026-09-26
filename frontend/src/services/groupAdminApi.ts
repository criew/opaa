import type { GroupKind, GroupOrigin, GroupPageResponse, GroupState } from '../types/api'
import { apiClient, normalizeError } from './api'

/** The sort fields of the group list (#1978), as the server allow-lists them. */
export type GroupSortField = 'name' | 'kind' | 'origin' | 'memberCount' | 'state' | 'createdAt'

/** The group list's page size: the server's default, well under its maximum of 50. */
export const GROUP_PAGE_SIZE = 25

/** Search, filter, sort and page of the group list; `providerId` implies origin PROVIDER. */
export interface GroupListQuery {
  query?: string
  origin?: GroupOrigin | null
  providerId?: string | null
  kind?: GroupKind | null
  state?: GroupState | null
  sort?: GroupSortField
  direction?: 'asc' | 'desc'
  page?: number
  size?: number
}

function queryString(query: GroupListQuery): string {
  const params = new URLSearchParams()
  if (query.query) params.set('query', query.query)
  if (query.origin) params.set('origin', query.origin)
  if (query.providerId) params.set('providerId', query.providerId)
  if (query.kind) params.set('kind', query.kind)
  if (query.state) params.set('state', query.state)
  params.set('sort', query.sort ?? 'name')
  params.set('direction', query.direction ?? 'asc')
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? GROUP_PAGE_SIZE))
  return params.toString()
}

export async function listGroupPage(query: GroupListQuery): Promise<GroupPageResponse> {
  try {
    const { data } = await apiClient.get<GroupPageResponse>(
      `/v1/admin/groups/page?${queryString(query)}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
