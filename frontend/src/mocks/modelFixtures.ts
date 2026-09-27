import type { EmbeddingInfoResponse, LlmModelResponse } from '../types/api'

/**
 * Mutable so create/update/delete/activate handlers can reflect their effect on the next list GET
 * (#759), same reasoning as mockBranding.
 */
export let mockLlmModels: LlmModelResponse[] = [
  {
    id: 'llm-model-ollama-lokal',
    displayName: 'Ollama lokal',
    baseUrl: 'http://ollama:11434/v1',
    modelIdentifier: 'phi3:mini',
    temperature: 0.7,
    maxTokens: 2000,
    apiKeySet: false,
    active: true,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export function resetMockLlmModels() {
  mockLlmModels = [
    {
      id: 'llm-model-ollama-lokal',
      displayName: 'Ollama lokal',
      baseUrl: 'http://ollama:11434/v1',
      modelIdentifier: 'phi3:mini',
      temperature: 0.7,
      maxTokens: 2000,
      apiKeySet: false,
      active: true,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ]
}

// "openai" names the wire protocol, not a vendor - since backend#762 it is the only connection
// path (Ollama included, via its own /v1 endpoint), so the backend never reports "ollama" here
// anymore.
export const mockEmbeddingInfo: EmbeddingInfoResponse = {
  provider: 'openai',
  model: 'nomic-embed-text',
  dimensions: 1536,
}
