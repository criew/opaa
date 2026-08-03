import axios, { AxiosError } from 'axios'
import type {
  GroupListResponse,
  GroupMemberResponse,
  GroupResponse,
  HealthResponse,
  IndexingStatusResponse,
  IndexingTriggerRequest,
  QueryRequest,
  QueryResponse,
  SpaceKind,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRole,
  SpaceResponse,
  SpaceUpdateRequest,
  SpaceVisibility,
  UserInfo,
} from '../types/api'
import { isErrorResponse } from '../types/api'
import { setupAuthInterceptors } from './apiInterceptors'
import { useAuthStore } from '../stores/authStore'

const client = axios.create({
  baseURL: '/api',
})

setupAuthInterceptors(
  client,
  () => useAuthStore.getState().getAccessToken(),
  () => useAuthStore.getState().logout(),
)

function normalizeError(err: unknown): never {
  if (err instanceof AxiosError) {
    const data = err.response?.data

    if (isErrorResponse(data)) {
      throw new Error(data.error)
    }

    if (err.response?.status) {
      throw new Error(`HTTP ${err.response.status}: ${err.message}`)
    }

    throw new Error(err.message)
  }
  throw err
}

export async function getHealth(): Promise<HealthResponse> {
  try {
    const { data } = await client.get<HealthResponse>('/health')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function sendQuery(
  question: string,
  conversationId?: string,
  spaceIds?: string[],
): Promise<QueryResponse> {
  try {
    const request: QueryRequest = { question, conversationId, spaceIds }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

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

export async function addSpaceMember(
  spaceId: string,
  userId: string,
  role?: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.post<SpaceMemberResponse>(`/v1/spaces/${spaceId}/members`, {
      userId,
      role,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeSpaceMember(spaceId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateSpaceMemberRole(
  spaceId: string,
  userId: string,
  role: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.put<SpaceMemberResponse>(
      `/v1/spaces/${spaceId}/members/${userId}/role`,
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
  kind: SpaceKind = 'PROJECT',
): Promise<SpaceResponse> {
  try {
    const currentUserId = useAuthStore.getState().user?.id ?? null
    const { data } = await client.post<SpaceResponse>('/v1/spaces', {
      name,
      description,
      kind,
      ownerId: currentUserId,
      initialMembers: [],
    })
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

export async function triggerIndexing(
  request?: IndexingTriggerRequest,
): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>('/v1/indexing/trigger', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getIndexingStatus(): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.get<IndexingStatusResponse>('/v1/indexing/status')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getUsers(): Promise<UserInfo[]> {
  try {
    const { data } = await client.get<UserInfo[]>('/v1/admin/users')
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
    const { data } = await client.get<GroupResponse>(`/v1/admin/groups/${groupId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createGroup(name: string, description: string): Promise<GroupResponse> {
  try {
    const { data } = await client.post<GroupResponse>('/v1/admin/groups', { name, description })
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
    const { data } = await client.put<GroupResponse>(`/v1/admin/groups/${groupId}`, {
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
    await client.delete(`/v1/admin/groups/${groupId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function addGroupMember(
  groupId: string,
  userId: string,
): Promise<GroupMemberResponse> {
  try {
    const { data } = await client.post<GroupMemberResponse>(`/v1/admin/groups/${groupId}/members`, {
      userId,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeGroupMember(groupId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/admin/groups/${groupId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}
