import type {
  EmbeddingInfoResponse,
  EmbeddingModelChangeRequest,
  EmbeddingModelDraft,
  EmbeddingModelProbeResponse,
  EmbeddingReindexStatusResponse,
  LlmModelDiscoveryRequest,
  LlmModelDiscoveryResponse,
  LlmModelRequest,
  LlmModelResponse,
  LlmModelTestRequest,
  LlmModelTestResponse,
  ModelRoleTestRequest,
  RerankModelSettingsRequest,
  RerankModelSettingsResponse,
  TranscriptionModelSettingsRequest,
  TranscriptionModelSettingsResponse,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

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

/**
 * Lists the models the endpoint offers. A 200 either way: `success`/`failure` carry the outcome;
 * only a request the backend rejects (400 for the stored-key rule, 404 for an unknown model) throws.
 */
export async function discoverLlmModels(
  request: LlmModelDiscoveryRequest,
): Promise<LlmModelDiscoveryResponse> {
  try {
    const { data } = await client.post<LlmModelDiscoveryResponse>(
      '/v1/admin/models/discover',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// The rerank and transcription model roles: one stored entry each, SYSTEM_ADMIN only. apiKey
// follows the same three-way convention as the chat models; a response carries apiKeySet only.
export async function getRerankModelSettings(): Promise<RerankModelSettingsResponse> {
  try {
    const { data } = await client.get<RerankModelSettingsResponse>('/v1/admin/models/rerank')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateRerankModelSettings(
  request: RerankModelSettingsRequest,
): Promise<RerankModelSettingsResponse> {
  try {
    const { data } = await client.put<RerankModelSettingsResponse>(
      '/v1/admin/models/rerank',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testRerankModel(
  request: ModelRoleTestRequest,
): Promise<LlmModelTestResponse> {
  try {
    const { data } = await client.post<LlmModelTestResponse>(
      '/v1/admin/models/rerank/test',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getTranscriptionModelSettings(): Promise<TranscriptionModelSettingsResponse> {
  try {
    const { data } = await client.get<TranscriptionModelSettingsResponse>(
      '/v1/admin/models/transcription',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateTranscriptionModelSettings(
  request: TranscriptionModelSettingsRequest,
): Promise<TranscriptionModelSettingsResponse> {
  try {
    const { data } = await client.put<TranscriptionModelSettingsResponse>(
      '/v1/admin/models/transcription',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testTranscriptionModel(
  request: ModelRoleTestRequest,
): Promise<LlmModelTestResponse> {
  try {
    const { data } = await client.post<LlmModelTestResponse>(
      '/v1/admin/models/transcription/test',
      request,
    )
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

// The guided change of the embedding model (ADR-0061): probe and preview, confirmation, discard
// and endpoint change. The key is write-only: omitted keeps the stored one, '' sends none.
export async function probeEmbeddingModelChange(
  draft: EmbeddingModelDraft,
): Promise<EmbeddingModelProbeResponse> {
  try {
    const { data } = await client.post<EmbeddingModelProbeResponse>(
      '/v1/admin/models/embedding/probe',
      draft,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function startEmbeddingModelChange(
  request: EmbeddingModelChangeRequest,
): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.post<EmbeddingReindexStatusResponse>(
      '/v1/admin/models/embedding/change',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function discardEmbeddingModelChange(): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.delete<EmbeddingReindexStatusResponse>(
      '/v1/admin/models/embedding/change',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function changeEmbeddingModelEndpoint(
  draft: EmbeddingModelDraft,
): Promise<EmbeddingReindexStatusResponse> {
  try {
    const { data } = await client.put<EmbeddingReindexStatusResponse>(
      '/v1/admin/models/embedding/endpoint',
      draft,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
