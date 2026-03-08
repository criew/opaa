import axios, { AxiosError } from 'axios'
import type {
  HealthResponse,
  IndexingStatusResponse,
  IndexingTriggerRequest,
  QueryRequest,
  QueryResponse,
  UserInfo,
  WorkspaceDocumentResponse,
  WorkspaceListResponse,
  WorkspaceMemberResponse,
  WorkspaceRole,
  WorkspaceResponse,
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
  workspaceIds?: string[],
): Promise<QueryResponse> {
  try {
    const request: QueryRequest = { question, conversationId, workspaceIds }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getWorkspaces(): Promise<WorkspaceListResponse[]> {
  try {
    const { data } = await client.get<WorkspaceListResponse[]>('/v1/workspaces')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getWorkspace(workspaceId: string): Promise<WorkspaceResponse> {
  try {
    const { data } = await client.get<WorkspaceResponse>(`/v1/workspaces/${workspaceId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getWorkspaceDocuments(
  workspaceId: string,
): Promise<WorkspaceDocumentResponse[]> {
  try {
    const { data } = await client.get<WorkspaceDocumentResponse[]>(
      `/v1/workspaces/${workspaceId}/documents`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function addWorkspaceMember(
  workspaceId: string,
  userId: string,
  role?: WorkspaceRole,
): Promise<WorkspaceMemberResponse> {
  try {
    const { data } = await client.post<WorkspaceMemberResponse>(
      `/v1/workspaces/${workspaceId}/members`,
      {
        userId,
        role,
      },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeWorkspaceMember(workspaceId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/workspaces/${workspaceId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateWorkspaceMemberRole(
  workspaceId: string,
  userId: string,
  role: WorkspaceRole,
): Promise<WorkspaceMemberResponse> {
  try {
    const { data } = await client.put<WorkspaceMemberResponse>(
      `/v1/workspaces/${workspaceId}/members/${userId}/role`,
      { role },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function transferWorkspaceOwnership(
  workspaceId: string,
  userId: string,
): Promise<void> {
  try {
    await client.post(`/v1/workspaces/${workspaceId}/transfer-ownership`, { userId })
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateWorkspaceDetails(
  workspaceId: string,
  name: string,
  description: string,
): Promise<WorkspaceResponse> {
  try {
    const { data } = await client.put<WorkspaceResponse>(`/v1/workspaces/${workspaceId}`, {
      name,
      description,
      ownerId: null,
      initialMembers: [],
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createWorkspace(
  name: string,
  description: string,
): Promise<WorkspaceResponse> {
  try {
    const currentUserId = useAuthStore.getState().user?.id ?? null
    const { data } = await client.post<WorkspaceResponse>('/v1/workspaces', {
      name,
      description,
      ownerId: currentUserId,
      initialMembers: [],
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteWorkspace(workspaceId: string): Promise<void> {
  try {
    await client.delete(`/v1/workspaces/${workspaceId}`)
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
