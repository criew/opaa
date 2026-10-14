import type { SourceTypeDescriptor } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

/** every source type the backend has a connector for (ADR-0038), ordered by key. */
export async function listSourceTypes(): Promise<SourceTypeDescriptor[]> {
  try {
    const { data } = await client.get<SourceTypeDescriptor[]>('/v1/source-types')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
