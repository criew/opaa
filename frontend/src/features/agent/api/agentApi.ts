import type {
  AgentCreateRequest,
  AgentResponse,
  AgentSettingsResponse,
  AgentSettingsUpdateRequest,
  AgentUpdateRequest,
  DocumentTemplateResponse,
  LibraryListResponse,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * The agent endpoints. Rights, derivation and space association of an agent go through the
 * asset-shell functions with `assetType = 'AGENT'` - there is no type-specific rights path.
 */
export async function getAgents(): Promise<AgentResponse[]> {
  try {
    const { data } = await apiClient.get<AgentResponse[]>('/v1/agents')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getAgent(agentId: string): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.get<AgentResponse>(`/v1/agents/${agentId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createAgent(request: AgentCreateRequest): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.post<AgentResponse>('/v1/agents', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateAgent(
  agentId: string,
  request: AgentUpdateRequest,
): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.put<AgentResponse>(`/v1/agents/${agentId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteAgent(agentId: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/agents/${agentId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/** The knowledge libraries the person may read - the only ones she can assign to an agent. */
export async function getReadableLibraries(): Promise<LibraryListResponse[]> {
  try {
    const { data } = await apiClient.get<LibraryListResponse[]>('/v1/libraries')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The document templates the caller may read: those an agent may be given. */
export async function getReadableTemplates(): Promise<DocumentTemplateResponse[]> {
  try {
    const { data } = await apiClient.get<DocumentTemplateResponse[]>('/v1/document-templates')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// /system/agents - SYSTEM_ADMIN only.
export async function getAgentSettings(): Promise<AgentSettingsResponse> {
  try {
    const { data } = await apiClient.get<AgentSettingsResponse>('/v1/system/agents')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateAgentSettings(
  request: AgentSettingsUpdateRequest,
): Promise<AgentSettingsResponse> {
  try {
    const { data } = await apiClient.put<AgentSettingsResponse>('/v1/system/agents', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
