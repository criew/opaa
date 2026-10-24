import type { LinkTargets } from '../api/types'
import { apiClient as client, normalizeError } from '../api/api'

/** The allowed link targets; readable for every signed-in person, changed by the administration. */
export async function getLinkTargets(): Promise<LinkTargets> {
  try {
    const { data } = await client.get<LinkTargets>('/v1/link-targets')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
