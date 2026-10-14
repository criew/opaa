import type {
  GroupDeactivationPreviewResponse,
  GroupListResponse,
  GroupMemberResponse,
  GroupResponse,
  GroupStewardResponse,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

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

/**
 * Was das Deaktivieren (oder Reaktivieren) einer internen Gruppe berührt, gezählt - die Zahlen der
 * Bestätigung (ADR-0036, Entscheidung 14). Nennt auch den Grund, aus dem die Gruppe gerade nicht
 * deaktiviert werden kann.
 */
export async function getGroupDeactivationPreview(
  groupId: string,
): Promise<GroupDeactivationPreviewResponse> {
  try {
    const { data } = await client.get<GroupDeactivationPreviewResponse>(
      `/v1/groups/${groupId}/deactivation`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setGroupDeactivation(
  groupId: string,
  deactivated: boolean,
): Promise<GroupResponse> {
  try {
    const { data } = await client.put<GroupResponse>(`/v1/groups/${groupId}/deactivation`, {
      deactivated,
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
