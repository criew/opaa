import type {
  GroupListResponse,
  GroupMemberResponse,
  GroupResponse,
  GroupStewardResponse,
  SelectableGroupResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

export async function getMyGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/me/groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die Gruppen, die man selbst als Empfänger eines Rechts benennen darf (#1820) — jede
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
 * Eine Gruppe über ihre Kennung, unter derselben Sichtbarkeitsregel wie die Suche (#1820). Der
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

export async function getGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/admin/groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getGroup(groupId: string): Promise<GroupResponse> {
  try {
    const { data } = await client.get<GroupResponse>(`/v1/groups/${groupId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** What a new group starts with besides its name - everything unset means the defaults. */
export interface GroupCreationOptions {
  releasedForUse?: boolean
  protectedGroup?: boolean
  stewardIds?: string[]
}

export async function createGroup(
  name: string,
  description: string,
  options: GroupCreationOptions = {},
): Promise<GroupResponse> {
  try {
    const { data } = await client.post<GroupResponse>('/v1/groups', {
      name,
      description,
      ...options,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateGroup(
  groupId: string,
  name: string,
  description: string,
): Promise<GroupResponse> {
  try {
    const { data } = await client.put<GroupResponse>(`/v1/groups/${groupId}`, {
      name,
      description,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteGroup(groupId: string): Promise<void> {
  try {
    await client.delete(`/v1/groups/${groupId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die Mitgliederliste einer Gruppe. Für die Systemverwaltung ohne eigene Verantwortung ist der
 * Abruf ein Audit-Ereignis (ADR-0036, Entscheidung 9) und dies ihr einziger Weg zu den Namen -
 * {@link getGroup} liefert ihr die Liste nicht.
 */
export async function listGroupMembers(groupId: string): Promise<GroupMemberResponse[]> {
  try {
    const { data } = await client.get<GroupMemberResponse[]>(`/v1/groups/${groupId}/members`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function addGroupMember(
  groupId: string,
  userId: string,
): Promise<GroupMemberResponse> {
  try {
    const { data } = await client.post<GroupMemberResponse>(`/v1/groups/${groupId}/members`, {
      userId,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeGroupMember(groupId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/groups/${groupId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die internen Gruppen, für die das eigene Konto verantwortlich ist (#1814) - die Grundlage von
 * „Meine Gruppen". Bewusst getrennt von {@link getMyGroups}: verantwortlich zu sein heißt nicht,
 * Mitglied zu sein.
 */
export async function getMyStewardedGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/me/stewarded-groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function appointGroupSteward(
  groupId: string,
  userId: string,
): Promise<GroupStewardResponse> {
  try {
    const { data } = await client.post<GroupStewardResponse>(`/v1/groups/${groupId}/stewards`, {
      userId,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function dismissGroupSteward(groupId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/groups/${groupId}/stewards/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function setGroupRelease(
  groupId: string,
  releasedForUse: boolean,
): Promise<GroupResponse> {
  try {
    const { data } = await client.put<GroupResponse>(`/v1/groups/${groupId}/release`, {
      releasedForUse,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setGroupProtection(
  groupId: string,
  protectedGroup: boolean,
): Promise<GroupResponse> {
  try {
    const { data } = await client.put<GroupResponse>(`/v1/groups/${groupId}/protection`, {
      protectedGroup,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
