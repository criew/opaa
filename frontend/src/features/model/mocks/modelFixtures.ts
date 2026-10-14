import type {
  DiscoveredLlmModel,
  EmbeddingInfoResponse,
  LlmModelResponse,
} from '@/shared/api/types'

/**
 * Mutable so create/update/delete/activate handlers can reflect their effect on the next list GET,
 * same reasoning as mockBranding.
 */
export let mockLlmModels: LlmModelResponse[] = [
  {
    id: 'llm-model-ollama-lokal',
    displayName: 'Ollama lokal',
    baseUrl: 'http://ollama:11434/v1',
    modelIdentifier: 'phi3:mini',
    temperature: 0.7,
    maxTokens: 8000,
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
      maxTokens: 8000,
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

/** What a local Ollama with three chat models and one embedding model reports. */
export const mockOllamaDiscoveredModels: DiscoveredLlmModel[] = [
  {
    identifier: 'llama3.2:3b',
    family: 'llama',
    parameterSize: '3.2B',
    quantization: 'Q4_K_M',
    sizeBytes: 2019393189,
    contextLength: 131072,
    capabilities: ['completion', 'tools'],
    chatSuitable: true,
  },
  {
    identifier: 'mistral-small3.2:24b',
    family: 'mistral3',
    parameterSize: '24.0B',
    quantization: 'Q4_K_M',
    sizeBytes: 15177384862,
    contextLength: 131072,
    capabilities: ['completion', 'vision', 'tools'],
    chatSuitable: true,
  },
  {
    identifier: 'qwen3:8b',
    family: 'qwen3',
    parameterSize: '8.2B',
    quantization: 'Q4_K_M',
    sizeBytes: 5225388164,
    contextLength: 40960,
    capabilities: ['completion', 'tools', 'thinking'],
    chatSuitable: true,
  },
  {
    identifier: 'nomic-embed-text:latest',
    family: 'nomic-bert',
    parameterSize: '137M',
    quantization: 'F16',
    sizeBytes: 274302450,
    contextLength: 2048,
    capabilities: ['embedding'],
    chatSuitable: false,
  },
]

/** What an OpenAI-compatible endpoint reports from `/models` alone: identifiers, nothing else. */
export const mockOpenAiDiscoveredModels: DiscoveredLlmModel[] = [
  { identifier: 'gpt-4.1', capabilities: [], chatSuitable: true },
  { identifier: 'gpt-4.1-mini', capabilities: [], chatSuitable: true },
  { identifier: 'gpt-4o', capabilities: [], chatSuitable: true },
  { identifier: 'text-embedding-3-small', capabilities: [], chatSuitable: false },
]
