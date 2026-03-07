import axios, { AxiosError } from 'axios'
import type {
  HealthResponse,
  IndexingStatusResponse,
  IndexingTriggerRequest,
  QueryRequest,
  QueryResponse,
  WorkspaceDocumentResponse,
  WorkspaceListResponse,
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
