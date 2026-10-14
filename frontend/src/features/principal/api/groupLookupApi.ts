import type { GroupListResponse, SelectableGroupResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

/** The calling account's own groups. */
export async function getMyGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/me/groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The groups one may name as recipient of a right oneself — every
 * provider group of the organization, an internal group only after it is shared. The service
 * enforces the rule; this list is the convenience, never the enforcement. A protected
 * group answers only to its complete name.
 */
export async function searchSelectableGroups(query: string): Promise<SelectableGroupResponse[]> {
  try {
    const { data } = await client.get<SelectableGroupResponse[]>('/v1/groups/selectable', {
      params: { query },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * A group by its identifier, under the same visibility rule as the search. The
 * identifier path thus resolves before a right is granted: origin and provider reach the
 * interface there as well. Whatever cannot be resolved answers „nicht gefunden".
 */
export async function resolveSelectableGroup(groupId: string): Promise<SelectableGroupResponse> {
  try {
    const { data } = await client.get<SelectableGroupResponse>(`/v1/groups/selectable/${groupId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
