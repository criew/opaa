import { http, HttpResponse } from 'msw'
import {
  mockEmbeddingChangeIndex,
  mockEmbeddingIndex,
  mockEmbeddingInfo,
  mockEmbeddingModelProbe,
  mockLlmModels,
  mockOllamaDiscoveredModels,
  mockOpenAiDiscoveredModels,
  resetMockLlmModels,
} from './modelFixtures'
import type {
  EmbeddingModelDraft,
  EmbeddingModelProbeResponse,
  LlmModelDiscoveryFailure,
  LlmModelDiscoveryRequest,
  LlmModelDiscoveryResponse,
  LlmModelRequest,
  LlmModelTestRequest,
} from '@/shared/api/types'

export function resetLlmModelMockState() {
  resetMockLlmModels()
}

export function discoveryFailure(
  failure: LlmModelDiscoveryFailure,
  message: string,
): LlmModelDiscoveryResponse {
  return { success: false, failure, message, ollama: false, models: [] }
}

export const modelHandlers = [
  // managed chat models ('s admin API). apiKey is never echoed back - only apiKeySet,
  // mirroring LlmModelController/LlmModelResponse in the backend.
  http.get('/api/v1/admin/models', () => {
    return HttpResponse.json(mockLlmModels)
  }),

  http.post('/api/v1/admin/models', async ({ request }) => {
    const body = (await request.json()) as LlmModelRequest
    if (!body.displayName || !body.baseUrl || !body.modelIdentifier) {
      return HttpResponse.json(
        { error: 'Anzeigename, Basis-Adresse und Modell-Kennung sind erforderlich' },
        { status: 400 },
      )
    }
    const now = new Date().toISOString()
    const created = {
      id: `llm-model-${crypto.randomUUID().slice(0, 8)}`,
      displayName: body.displayName,
      baseUrl: body.baseUrl,
      modelIdentifier: body.modelIdentifier,
      temperature: body.temperature ?? null,
      maxTokens: body.maxTokens,
      apiKeySet: Boolean(body.apiKey && body.apiKey.trim() !== ''),
      active: false,
      createdAt: now,
      updatedAt: now,
    }
    mockLlmModels.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.put('/api/v1/admin/models/:modelId', async ({ params, request }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as LlmModelRequest
    model.displayName = body.displayName
    model.baseUrl = body.baseUrl
    model.modelIdentifier = body.modelIdentifier
    model.temperature = body.temperature ?? null
    model.maxTokens = body.maxTokens
    // Mirrors LlmModelRequest's three-way apiKey convention: omitted leaves it unchanged, an
    // empty string clears it, anything else sets it.
    if (body.apiKey !== undefined && body.apiKey !== null) {
      model.apiKeySet = body.apiKey.trim() !== ''
    }
    model.updatedAt = new Date().toISOString()
    return HttpResponse.json(model)
  }),

  http.delete('/api/v1/admin/models/:modelId', ({ params }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    // Mirrors LlmModelService#deleteModel: the active model cannot be deleted, a chat cannot be
    // left without any model to answer with.
    if (model.active) {
      return HttpResponse.json(
        { error: 'Das aktive Chat-Modell kann nicht gelöscht werden.' },
        { status: 409 },
      )
    }
    const idx = mockLlmModels.findIndex((item) => item.id === modelId)
    mockLlmModels.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/models/:modelId/activate', ({ params }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    mockLlmModels.forEach((item) => {
      item.active = item.id === modelId
    })
    model.updatedAt = new Date().toISOString()
    return HttpResponse.json(model)
  }),

  http.post('/api/v1/admin/models/test', async ({ request }) => {
    const body = (await request.json()) as LlmModelTestRequest
    if (!body.baseUrl || !body.modelIdentifier) {
      return HttpResponse.json(
        { error: 'Basis-Adresse und Modell-Kennung sind erforderlich' },
        { status: 400 },
      )
    }
    // Mirrors the same-origin guard the real endpoint applies when reusing a stored key
    // (LlmModelController#testModel): a modelId whose stored baseUrl differs from the address
    // under test is rejected with 400 rather than silently testing a different address.
    if (body.modelId) {
      const stored = mockLlmModels.find((item) => item.id === body.modelId)
      if (!stored) {
        return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
      }
      if (
        (!body.apiKey || body.apiKey.trim() === '') &&
        new URL(body.baseUrl).origin !== new URL(stored.baseUrl).origin
      ) {
        return HttpResponse.json(
          { error: 'Die Adresse weicht vom gespeicherten Modell ab' },
          { status: 400 },
        )
      }
    }
    return HttpResponse.json({
      success: true,
      message: 'Verbindung erfolgreich, Modell hat geantwortet.',
    })
  }),

  // Mirrors LlmModelDiscoverer by address: Ollama's port or host answers with details, OpenAI
  // insists on a key, and the words "unerreichbar"/"ohne-liste" stand in for the two failures.
  http.post('/api/v1/admin/models/discover', async ({ request }) => {
    const body = (await request.json()) as LlmModelDiscoveryRequest
    const address = body.baseUrl ?? ''
    const hasKey = Boolean(body.apiKey && body.apiKey.trim() !== '')
    if (address.includes('unerreichbar')) {
      return HttpResponse.json(
        discoveryFailure(
          'UNREACHABLE',
          'Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).',
        ),
      )
    }
    if (address.includes('ohne-liste')) {
      return HttpResponse.json(
        discoveryFailure(
          'NO_MODEL_LIST',
          'Der Endpunkt bietet keine Modellliste an. Tragen Sie die Modell-Kennung von Hand ein.',
        ),
      )
    }
    if (address.includes('api.openai.com') && !hasKey) {
      return HttpResponse.json(
        discoveryFailure(
          'UNAUTHENTICATED',
          'Der Endpunkt verlangt einen Zugangsschlüssel (HTTP 401). Tragen Sie ihn ein und rufen Sie die Modelle erneut ab.',
        ),
      )
    }
    const ollama = address.includes(':11434') || address.includes('ollama')
    const models = ollama ? mockOllamaDiscoveredModels : mockOpenAiDiscoveredModels
    return HttpResponse.json({
      success: true,
      failure: null,
      message: `${models.length} Modelle gefunden.`,
      ollama,
      models,
    } satisfies LlmModelDiscoveryResponse)
  }),

  http.get('/api/v1/admin/models/embedding-info', () => {
    return HttpResponse.json(mockEmbeddingInfo)
  }),

  // The same model name is the installed model at another endpoint; any other is a model change.
  http.post('/api/v1/admin/models/embedding/probe', async ({ request }) => {
    const draft = (await request.json()) as EmbeddingModelDraft
    if (draft.model === mockEmbeddingInfo.model) {
      return HttpResponse.json({
        succeeded: true,
        message: 'Probeeinbettung erfolgreich: Das Modell liefert Vektoren mit 1536 Dimensionen.',
        dimensions: 1536,
        kind: 'ENDPOINT_CHANGE',
        similarity: 0.9999,
      } satisfies EmbeddingModelProbeResponse)
    }
    return HttpResponse.json(mockEmbeddingModelProbe)
  }),

  http.post('/api/v1/admin/models/embedding/change', () => {
    return HttpResponse.json(mockEmbeddingChangeIndex)
  }),

  http.delete('/api/v1/admin/models/embedding/change', () => {
    return HttpResponse.json(mockEmbeddingIndex)
  }),

  http.put('/api/v1/admin/models/embedding/endpoint', () => {
    return HttpResponse.json(mockEmbeddingIndex)
  }),
]
