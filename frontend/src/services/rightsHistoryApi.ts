import type { AccessAsOfObjectType, AccessAsOfPage } from '../types/api'
import { apiClient, normalizeError } from './api'

/** Die Parameter der Stichtagsauskunft (#1822) — genau ein benanntes Objekt je Abfrage. */
export interface AccessAsOfQuery {
  objectType: AccessAsOfObjectType
  objectId: string
  from: string
  to: string
  reason: string
  page?: number
}

/**
 * Wer durfte dieses Objekt in diesem Zeitraum lesen. Das Backend weist ein zu weites Fenster und
 * eine zu große Antwort ab, statt zu kürzen, und protokolliert jeden Abruf — auch den abgewiesenen.
 * Die Seitengröße ist serverseitig gesetzt; geblättert wird über `page` (höchstens Seite 49).
 */
export async function getAccessAsOf(query: AccessAsOfQuery): Promise<AccessAsOfPage> {
  try {
    const { data } = await apiClient.get<AccessAsOfPage>('/v1/audit/access-as-of', {
      params: {
        objectType: query.objectType,
        objectId: query.objectId,
        from: query.from,
        to: query.to,
        reason: query.reason,
        page: query.page ?? 0,
      },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
