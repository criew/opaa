import type {
  EmbeddingInfoResponse,
  LlmModelRequest,
  LlmModelResponse,
  LlmModelTestRequest,
  LlmModelTestResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// managed chat models ('s admin API) - SYSTEM_ADMIN only, same as the group/branding
// admin endpoints. The API key is write-only: LlmModelResponse never carries it, only
// apiKeySet (see LlmModelController's Javadoc).
export async function getLlmModels(): Promise<LlmModelResponse[]> {
  try {
    const { data } = await client.get<LlmModelResponse[]>('/v1/admin/models')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLlmModel(request: LlmModelRequest): Promise<LlmModelResponse> {
  try {
    const { data } = await client.post<LlmModelResponse>('/v1/admin/models', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLlmModel(
  modelId: string,
  request: LlmModelRequest,
): Promise<LlmModelResponse> {
  try {
    const { data } = await client.put<LlmModelResponse>(`/v1/admin/models/${modelId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLlmModel(modelId: string): Promise<void> {
  try {
    await client.delete(`/v1/admin/models/${modelId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function activateLlmModel(modelId: string): Promise<LlmModelResponse> {
  try {
    const { data } = await client.post<LlmModelResponse>(`/v1/admin/models/${modelId}/activate`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testLlmModel(request: LlmModelTestRequest): Promise<LlmModelTestResponse> {
  try {
    const { data } = await client.post<LlmModelTestResponse>('/v1/admin/models/test', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getEmbeddingInfo(): Promise<EmbeddingInfoResponse> {
  try {
    const { data } = await client.get<EmbeddingInfoResponse>('/v1/admin/models/embedding-info')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
