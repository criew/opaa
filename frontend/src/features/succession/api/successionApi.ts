import type { SuccessionKind, SuccessionListResponse } from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * Die Betriebsliste des Lebenszyklus (ADR-0036 Entscheidung 6). Die Reihenfolge ist fest
 * (ältester Eintrag zuerst), und es gibt bewusst keinen Parameter für den früheren Eigentümer oder
 * die handelnde Person — weder hier noch in der Oberfläche.
 */
export async function getSuccessionEntries(
  kind: SuccessionKind,
  page: number,
  size: number,
): Promise<SuccessionListResponse> {
  try {
    const { data } = await apiClient.get<SuccessionListResponse>('/v1/admin/succession', {
      params: { kind, page, size },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
