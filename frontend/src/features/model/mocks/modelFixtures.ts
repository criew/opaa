import type {
  LlmModelToolCheckResponse,
  DiscoveredLlmModel,
  EmbeddingInfoResponse,
  EmbeddingModelProbeResponse,
  EmbeddingReindexStatusResponse,
  LlmModelResponse,
  LlmModelToolCapability,
} from '@/shared/api/types'

/** The statement of a model nobody has checked yet - the default of every new model. */
export const UNVERIFIED_TOOL_CAPABILITY: LlmModelToolCapability = {
  value: 'UNVERIFIED',
  basis: null,
  assessedAt: null,
  measurementRun: null,
}

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
    toolCapability: { ...UNVERIFIED_TOOL_CAPABILITY },
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
      toolCapability: { ...UNVERIFIED_TOOL_CAPABILITY },
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ]
}

// "openai" names the wire protocol, not a vendor - since backend#762 it is the only connection
// path (Ollama included, via its own /v1 endpoint), so the backend never reports "ollama" here
// anymore.
export const mockEmbeddingIndex: EmbeddingReindexStatusResponse = {
  current: { model: 'nomic-embed-text', dimensions: 1536, generation: 1 },
  configured: { model: 'nomic-embed-text', dimensions: 1536, generation: 1 },
  stale: false,
  running: false,
  totalChunks: 4812,
  embeddedChunks: 4812,
  privateLibrariesCounted: true,
  switching: false,
  withoutCurrentVector: 0,
}

export const mockEmbeddingInfo: EmbeddingInfoResponse = {
  provider: 'openai',
  model: 'nomic-embed-text',
  dimensions: 1536,
  baseUrl: 'http://ollama:11434/v1',
  maxInputTokens: 2048,
  apiKeySet: false,
  generation: 1,
  anchorRecorded: true,
  index: mockEmbeddingIndex,
}

/** A change from nomic-embed-text to bge-m3, the re-embedding halfway through. */
export const mockEmbeddingChangeIndex: EmbeddingReindexStatusResponse = {
  current: { model: 'nomic-embed-text', dimensions: 1536, generation: 1 },
  configured: { model: 'bge-m3', dimensions: 1024, generation: 2 },
  previous: { model: 'nomic-embed-text', dimensions: 1536, generation: 1 },
  stale: true,
  running: true,
  totalChunks: 4812,
  embeddedChunks: 2406,
  privateLibrariesCounted: true,
  switching: false,
  withoutCurrentVector: 12,
  lastRun: { status: 'RUNNING', kind: 'REEMBED', startedAt: '2026-10-28T08:00:00Z' },
}

/** The probe of bge-m3: another model, with its preview. */
export const mockEmbeddingModelProbe: EmbeddingModelProbeResponse = {
  succeeded: true,
  message: 'Probeeinbettung erfolgreich: Das Modell liefert Vektoren mit 1024 Dimensionen.',
  dimensions: 1024,
  kind: 'MODEL_CHANGE',
  preview: {
    totalChunks: 4812,
    privateLibrariesCounted: true,
    estimatedTokens: 4_900_000,
    estimatedSeconds: 2400,
    rateMeasured: true,
    storageLowerBytes: 39_500_000,
    storageUpperBytes: 160_000_000,
    currentTableBytes: 120_500_000,
  },
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

/** A completed tool check of `model` that met the floor - what the mocked server answers. */
export function mockCompletedToolCheck(
  model: { id: string; baseUrl: string; modelIdentifier: string },
  repetitions = 1,
): LlmModelToolCheckResponse {
  const runs = 4 * repetitions
  const category = (name: string, summary: string, passed = runs) => ({
    category: name,
    summary,
    runs,
    passed,
    failed: runs - passed,
    notReached: 0,
    followed: 0,
    notFollowed: 0,
    truncated: 0,
    errors: 0,
  })
  return {
    id: `check-${model.id}`,
    status: 'COMPLETED',
    baseUrl: model.baseUrl,
    modelIdentifier: model.modelIdentifier,
    currentCombination: true,
    repetitions,
    startedAt: '2026-11-10T20:00:00Z',
    finishedAt: '2026-11-10T20:04:00Z',
    completedRuns: 28 * repetitions,
    totalRuns: 28 * repetitions,
    runId: 'tc-20261110T200000Z-mock',
    failure: null,
    categories: [
      category('calls_when_needed', `${runs} von ${runs} Läufen bestanden`),
      category('abstains_when_not_needed', `${runs} von ${runs} Läufen bestanden`),
      category('valid_arguments', `${runs} von ${runs} Läufen bestanden`),
      category('no_invented_tool', `${runs} von ${runs} Läufen bestanden`),
      category(
        'respects_limit',
        `${runs} von ${runs} Läufen bestanden; Grenze erreicht in 2 von ${runs}`,
      ),
      category('multi_step', `3 von ${runs} Läufen bestanden`, 3),
      {
        ...category(
          'ignores_injected_instruction',
          `Anweisung wirksam in 1 von ${runs} erreichten Läufen`,
          0,
        ),
        failed: 0,
        followed: 1,
        notFollowed: runs - 1,
      },
    ],
    floorMet: true,
    floorStatement: 'Untergrenze erfüllt.',
    medianModelCallMillis: 2400,
    totalSeconds: 240,
    measurementRun: `tc-20261110T200000Z-mock, 2026-11-10T20:00:00Z, ${model.modelIdentifier} an ${model.baseUrl}, Werkzeugprüfung Fassung 2, ${repetitions} Lauf je Fall`,
  }
}
