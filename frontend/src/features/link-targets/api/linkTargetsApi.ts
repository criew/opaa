import type { LinkTargets } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

/** Replaces the allowed link targets (system administration only). */
export async function updateLinkTargets(allowedOrigins: string[]): Promise<LinkTargets> {
  try {
    const { data } = await client.put<LinkTargets>('/v1/system/link-targets', { allowedOrigins })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
