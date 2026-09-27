import type { UserInfo, UserSummary } from '../types/api'
import { apiClient as client, normalizeError } from './api'

export async function getUsers(): Promise<UserInfo[]> {
  try {
    const { data } = await client.get<UserInfo[]>('/v1/admin/users')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// unlike getUsers() above (GET /v1/admin/users, SYSTEM_ADMIN only), this is reachable for
// any authenticated organization member - the member/grant pickers (UserPicker, SubjectPicker,
// SpaceCreatePage) need to search for a user to add,
// and the caller reaching those pages is not necessarily a system admin.
//
//  review, finding 4: the backend requires `query` (min. 2 characters) and caps the result at
// 20 rows - it no longer answers an unqualified "list everyone" call. A missing/blank query is
// passed straight through and yields an empty result (UserService#searchInOrganization), never a
// fallback list, so a caller must always supply the person's typed input here.
export async function getUserSummaries(query: string): Promise<UserSummary[]> {
  try {
    const { data } = await client.get<UserSummary[]>('/v1/users', { params: { query } })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
