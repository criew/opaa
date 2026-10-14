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
 * Die Gruppen, die man selbst als Empfänger eines Rechts benennen darf — jede
 * Anbietergruppe der Organisation, eine interne Gruppe erst nach ihrer Freigabe. Die Regel setzt
 * der Dienst durch; diese Liste ist die Bequemlichkeit, nie die Durchsetzung. Eine geschützte
 * Gruppe antwortet nur auf ihre vollständige Bezeichnung.
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
 * Eine Gruppe über ihre Kennung, unter derselben Sichtbarkeitsregel wie die Suche. Der
 * Kennungsweg löst damit auf, bevor ein Recht erteilt wird: Herkunft und Anbieter erreichen die
 * Oberfläche auch dort. Was sich nicht auflösen lässt, antwortet „nicht gefunden".
 */
export async function resolveSelectableGroup(groupId: string): Promise<SelectableGroupResponse> {
  try {
    const { data } = await client.get<SelectableGroupResponse>(`/v1/groups/selectable/${groupId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
