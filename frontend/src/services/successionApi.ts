import type { SuccessionKind, SuccessionListResponse, SuccessionReviewResponse } from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * Die Betriebsliste des Lebenszyklus (#1819, ADR-0036 Entscheidung 6). Die Reihenfolge ist fest
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

/** Der Sichtungsvermerk „geprüft am …, weiterhin offen, Grund" — er löst sonst nichts aus. */
export async function reviewSuccessionCase(
  caseId: string,
  reason: string,
): Promise<SuccessionReviewResponse> {
  try {
    const { data } = await apiClient.post<SuccessionReviewResponse>(
      `/v1/admin/succession/${caseId}/reviews`,
      { reason },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
