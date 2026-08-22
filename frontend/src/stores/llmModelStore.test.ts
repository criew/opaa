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

  it('creates a model and reloads the list', async () => {
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
