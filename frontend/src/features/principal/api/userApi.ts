import type { UserSummary } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

// Reachable for any authenticated organization member, unlike the administration's account list
// (GET /v1/admin/users): the member/grant pickers (UserPicker, SubjectPicker) search here.
//
// The backend requires `query` (min. 2 characters) and caps the result at 20 rows - it does not
// answer an unqualified "list everyone" call. A missing/blank query is
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
