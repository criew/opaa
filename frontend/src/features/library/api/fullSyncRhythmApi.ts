import type { FullSyncRhythmRequest, FullSyncRhythmResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const RHYTHMS = '/v1/admin/full-sync-rhythms'

/** The installation-wide full-sync rhythm of every source type that has one (administration). */
export async function listFullSyncRhythms(): Promise<FullSyncRhythmResponse[]> {
  try {
    const { data } = await client.get<FullSyncRhythmResponse[]>(RHYTHMS)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Sets the days of one source type; `null` returns to the factory default. */
export async function updateFullSyncRhythm(
  sourceType: string,
  intervalDays: number | null,
): Promise<FullSyncRhythmResponse> {
  try {
    const body: FullSyncRhythmRequest = { intervalDays }
    const { data } = await client.put<FullSyncRhythmResponse>(
      `${RHYTHMS}/${encodeURIComponent(sourceType)}`,
      body,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
