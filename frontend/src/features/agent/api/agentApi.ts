import { apiClient, normalizeError } from '@/shared/api/api'
import type {
  AgentConfig,
  AgentModel,
  AgentResponse,
  AgentSystemLimits,
  DocumentTemplate,
} from '../types'

async function get<T>(path: string): Promise<T> {
  try {
    const { data } = await apiClient.get<T>(path)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export const getAgents = () => get<AgentResponse[]>('/v1/agents')
export const getAgent = (agentId: string) => get<AgentResponse>(`/v1/agents/${agentId}`)
export const getAgentModels = () => get<AgentModel[]>('/v1/agent-models')
export const getAgentSystemLimits = () => get<AgentSystemLimits>('/v1/agent-limits')
export const getDocumentTemplates = () => get<DocumentTemplate[]>('/v1/document-templates')

export async function createAgent(config: AgentConfig): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.post<AgentResponse>('/v1/agents', config)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateAgent(agentId: string, config: AgentConfig): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.put<AgentResponse>(`/v1/agents/${agentId}`, config)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function setAgentLocked(agentId: string, locked: boolean): Promise<AgentResponse> {
  try {
    const { data } = await apiClient.put<AgentResponse>(`/v1/agents/${agentId}/lock`, { locked })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
