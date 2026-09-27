import { AxiosError } from 'axios'
import type {
  GroupMemberDisclosureResponse,
  SpaceAccessDerivationResponse,
  PermissionSubjectType,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRequest,
  SpaceRole,
  SpaceResponse,
  SpaceUpdateRequest,
  SpaceVisibility,
} from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { apiClient as client, normalizeError } from './api'

export async function getSpaces(): Promise<SpaceListResponse[]> {
  try {
    const { data } = await client.get<SpaceListResponse[]>('/v1/spaces')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getSpace(spaceId: string): Promise<SpaceResponse> {
  try {
    const { data } = await client.get<SpaceResponse>(`/v1/spaces/${spaceId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// the full member list (identities and display names) is only reachable here - SpaceResponse
// no longer carries it, and the backend restricts this endpoint to ADMIN, owner and system admins.
//  review, nit a: a 403 here is an expected, silent "not allowed to see this" for a caller
// without the role - it resolves to an empty list rather than an error, but every other failure
// (network error, 404, 500, ...) still throws through normalizeError so the store can tell the two
// apart instead of treating every failure alike.
export async function listSpaceMembers(spaceId: string): Promise<SpaceMemberResponse[]> {
  try {
    const { data } = await client.get<SpaceMemberResponse[]>(`/v1/spaces/${spaceId}/members`)
    return data
  } catch (err) {
    if (err instanceof AxiosError && err.response?.status === 403) {
      return []
    }
    normalizeError(err)
  }
}

// #1815: a space member is a person or a group, and every membership is addressed by its own id -
// so a person and a group carrying the same id can never be confused on the remove/role paths.
export async function addSpaceMember(
  spaceId: string,
  subjectType: PermissionSubjectType,
  subjectId: string,
  role?: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.post<SpaceMemberResponse>(`/v1/spaces/${spaceId}/members`, {
      subjectType,
      subjectId,
      role,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeSpaceMember(spaceId: string, membershipId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}/members/${membershipId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateSpaceMemberRole(
  spaceId: string,
  membershipId: string,
  role: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.put<SpaceMemberResponse>(
      `/v1/spaces/${spaceId}/members/${membershipId}/role`,
      { role },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function transferSpaceOwnership(spaceId: string, userId: string): Promise<void> {
  try {
    await client.post(`/v1/spaces/${spaceId}/transfer-ownership`, { userId })
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateSpaceDetails(
  spaceId: string,
  name: string,
  description: string,
  visibility?: SpaceVisibility,
): Promise<SpaceResponse> {
  try {
    const body: SpaceUpdateRequest = { name, description, visibility }
    const { data } = await client.put<SpaceResponse>(`/v1/spaces/${spaceId}`, body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createSpace(
  name: string,
  description: string,
  visibility?: SpaceVisibility,
  libraryIds?: string[],
): Promise<SpaceResponse> {
  try {
    const currentUserId = useAuthStore.getState().user?.id ?? null
    const body: SpaceRequest = {
      name,
      description,
      visibility,
      ownerId: currentUserId,
      initialMembers: [],
      // the assistant's Datenquellen step submits the creator's chosen libraries alongside
      // the space itself - the backend associates each one right after creation, requiring the
      // creator to already be able to read it (SpaceAssetAssociationService#associate), the same
      // rule the dedicated endpoints below enforce afterwards.
      libraryIds: libraryIds && libraryIds.length > 0 ? libraryIds : undefined,
    }
    const { data } = await client.post<SpaceResponse>('/v1/spaces', body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteSpace(spaceId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// the way out of a space fk_chats_space makes permanently undeletable because it still
// contains a chat authored by someone other than the space owner - see
// docs/features/spaces-and-assets.md#einen-space-stilllegen-archivieren-statt-löschen.
export async function archiveSpace(spaceId: string): Promise<SpaceResponse> {
  try {
    const { data } = await client.post<SpaceResponse>(`/v1/spaces/${spaceId}/archive`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Dasselbe für eine Gruppe, die Mitglied dieses Raums ist (#1880) — hinter derselben Schwelle wie
 * die Mitgliederliste selbst.
 */
export async function getSpaceGroupMembers(
  spaceId: string,
  groupId: string,
  offset = 0,
  limit = 50,
): Promise<GroupMemberDisclosureResponse> {
  try {
    const { data } = await client.get<GroupMemberDisclosureResponse>(
      `/v1/spaces/${spaceId}/members/groups/${groupId}/members`,
      { params: { offset, limit } },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die Herleitung an einem Space (#1822). Ohne `userId` geht es um die eigene Person; mit `userId`
 * um ein Mitglied, und das ist denen vorbehalten, die die Mitgliedschaft hier verwalten.
 */
export async function getSpaceAccessDerivation(
  spaceId: string,
  userId?: string,
): Promise<SpaceAccessDerivationResponse> {
  try {
    const { data } = await client.get<SpaceAccessDerivationResponse>(
      `/v1/spaces/${spaceId}/access-derivation`,
      userId ? { params: { userId } } : undefined,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
