import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { useLlmModelStore } from './llmModelStore'

describe('llmModelStore', () => {
  beforeEach(() => {
    useLlmModelStore.setState({
      models: [],
      embeddingInfo: null,
      isLoading: false,
      error: null,
    })
  })

  it('loads the configured models', async () => {
    await useLlmModelStore.getState().loadModels()

    const { models, isLoading } = useLlmModelStore.getState()
    expect(models).toHaveLength(1)
    expect(models[0].displayName).toBe('Ollama lokal')
    expect(models[0].active).toBe(true)
    expect(isLoading).toBe(false)
  })

  it('surfaces a load failure', async () => {
    server.use(http.get('/api/v1/admin/models', () => HttpResponse.error()))

    await useLlmModelStore.getState().loadModels()

    expect(useLlmModelStore.getState().error).toBeTruthy()
  })

  it('creates a model, patching the list from the response', async () => {
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Neues Modell',
      baseUrl: 'http://localhost:11434/v1',
      modelIdentifier: 'llama3',
      temperature: 0.5,
      maxTokens: 1000,
    })

    const { models } = useLlmModelStore.getState()
    expect(models.some((m) => m.displayName === 'Neues Modell')).toBe(true)
  })

  it('activates a model', async () => {
    await useLlmModelStore.getState().loadModels()
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Zweites Modell',
      baseUrl: 'http://localhost:11434/v1',
      modelIdentifier: 'llama3',
      temperature: 0.5,
      maxTokens: 1000,
    })
    const created = useLlmModelStore
      .getState()
      .models.find((m) => m.displayName === 'Zweites Modell')!

    await useLlmModelStore.getState().activateExistingModel(created.id)

    const { models } = useLlmModelStore.getState()
    expect(models.find((m) => m.id === created.id)?.active).toBe(true)
    expect(models.filter((m) => m.active)).toHaveLength(1)
  })

  /**
   * #759 review: the acceptance criterion for editing had no direct store coverage yet, and the
   * update must patch `models` in place rather than reloading (a reload flips `isLoading`, which
   * is exactly what made LlmModelManagementPage briefly unmount every open card after a save).
   */
  it('updates a model in place, without a reload in between', async () => {
    await useLlmModelStore.getState().loadModels()
    const existing = useLlmModelStore.getState().models[0]
    const isLoadingDuringUpdate: boolean[] = []
    const unsubscribe = useLlmModelStore.subscribe((state) =>
      isLoadingDuringUpdate.push(state.isLoading),
    )

    const updated = await useLlmModelStore.getState().updateExistingModel(existing.id, {
      displayName: 'Ollama umbenannt',
      baseUrl: existing.baseUrl,
      modelIdentifier: existing.modelIdentifier,
      temperature: existing.temperature,
      maxTokens: existing.maxTokens,
    })
    unsubscribe()

    expect(updated.displayName).toBe('Ollama umbenannt')
    // apiKeySet must not flip just because apiKey was omitted from the request.
    expect(updated.apiKeySet).toBe(existing.apiKeySet)
    expect(
      useLlmModelStore.getState().models.some((m) => m.displayName === 'Ollama umbenannt'),
    ).toBe(true)
    // Never a reload in between (#759 review): isLoading must stay false throughout, otherwise
    // LlmModelManagementPage would briefly swap the whole list for a loading message and unmount
    // every open card.
    expect(isLoadingDuringUpdate.every((value) => value === false)).toBe(true)
  })

  it('clears a stored key when apiKey is sent as an empty string', async () => {
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Mit Schlüssel',
      baseUrl: 'http://localhost:11434/v1',
      modelIdentifier: 'llama3',
      temperature: 0.5,
      maxTokens: 1000,
      apiKey: 'geheim',
    })
    const created = useLlmModelStore
      .getState()
      .models.find((m) => m.displayName === 'Mit Schlüssel')!
    expect(created.apiKeySet).toBe(true)

    const updated = await useLlmModelStore.getState().updateExistingModel(created.id, {
      displayName: created.displayName,
      baseUrl: created.baseUrl,
      modelIdentifier: created.modelIdentifier,
      temperature: created.temperature,
      maxTokens: created.maxTokens,
      apiKey: '',
    })

    expect(updated.apiKeySet).toBe(false)
  })

  it('rejects deleting the active model with the API message', async () => {
    await useLlmModelStore.getState().loadModels()
    const active = useLlmModelStore.getState().models.find((m) => m.active)!

    await expect(useLlmModelStore.getState().deleteExistingModel(active.id)).rejects.toThrow(
      /nicht gelöscht werden/,
    )
  })

  it('loads the embedding info', async () => {
    await useLlmModelStore.getState().loadEmbeddingInfo()

    const { embeddingInfo } = useLlmModelStore.getState()
    expect(embeddingInfo?.provider).toBe('ollama')
    expect(embeddingInfo?.model).toBe('nomic-embed-text')
    expect(embeddingInfo?.dimensions).toBe(1536)
  })
})
