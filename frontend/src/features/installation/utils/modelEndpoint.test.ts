import { describe, expect, it } from 'vitest'
import {
  discoveryPlan,
  providerOf,
  providerPresets,
  sameEndpoint,
  type EndpointEntries,
} from './modelEndpoint'

function entries(overrides: Partial<EndpointEntries> = {}): EndpointEntries {
  return {
    baseUrl: 'http://ollama:11434/v1',
    provider: 'ollama',
    apiKey: '',
    removeKey: false,
    stored: null,
    ...overrides,
  }
}

describe('providerOf', () => {
  it('names a preset by its address and Ollama by its port', () => {
    expect(providerOf('https://api.openai.com/v1/', 'chat')).toBe('openai')
    expect(providerOf('http://gpu-server:11434/v1', 'chat')).toBe('ollama')
    expect(providerOf('http://vllm:8000/v1', 'chat')).toBe('custom')
    expect(providerOf('  ', 'chat')).toBeNull()
  })

  it('offers no Anthropic preset for the embedding model', () => {
    expect(providerPresets('embedding').map((preset) => preset.key)).not.toContain('anthropic')
    expect(providerOf('https://api.anthropic.com/v1', 'embedding')).toBe('custom')
  })

  it('offers no preset for the rerank model, so even the Ollama port is an address of its own', () => {
    expect(providerPresets('rerank')).toEqual([])
    expect(providerOf('http://gpu-server:11434/v1', 'rerank')).toBe('custom')
    expect(providerOf('http://reranker:80', 'rerank')).toBe('custom')
  })
})

describe('sameEndpoint', () => {
  it('compares scheme, host, port and path like the backend', () => {
    expect(sameEndpoint('https://api.openai.com/v1', 'https://API.openai.com:443/v1/')).toBe(true)
    expect(sameEndpoint('https://api.openai.com/v1', 'https://api.openai.com/v2')).toBe(false)
    expect(sameEndpoint('https://api.openai.com/v1', 'http://api.openai.com/v1')).toBe(false)
    expect(sameEndpoint('https://api.openai.com/v1', 'kein-url')).toBe(false)
  })
})

describe('discoveryPlan', () => {
  it('waits for an address', () => {
    expect(discoveryPlan(entries({ baseUrl: '' })).request).toBeNull()
  })

  it('asks only for the address where the role offers no providers', () => {
    expect(discoveryPlan(entries({ baseUrl: '', offersProviders: false })).hint).toMatch(
      /^Basis-Adresse eintragen/,
    )
    expect(discoveryPlan(entries({ baseUrl: '' })).hint).toMatch(/^Anbieter wählen/)
  })

  it('fetches a keyless endpoint at once and sends a typed key', () => {
    expect(discoveryPlan(entries())).toMatchObject({
      request: { baseUrl: 'http://ollama:11434/v1' },
      automatic: true,
    })
    expect(discoveryPlan(entries({ apiKey: ' sk-1 ' })).request).toEqual({
      baseUrl: 'http://ollama:11434/v1',
      apiKey: 'sk-1',
    })
  })

  // regression guard: a typed key does not follow a hand-edited address on its own
  it('sends a typed key on its own only to the origin it was typed for', () => {
    const typed = { apiKey: 'sk-1', keyOrigin: 'http://vllm:8000' }
    expect(discoveryPlan(entries({ ...typed, baseUrl: 'http://vllm:8000/v1' })).automatic).toBe(
      true,
    )

    const moved = discoveryPlan(entries({ ...typed, baseUrl: 'http://anderer:8000/v1' }))
    expect(moved.automatic).toBe(false)
    expect(moved.request).toEqual({ baseUrl: 'http://anderer:8000/v1', apiKey: 'sk-1' })
    expect(moved.hint).toMatch(/für eine andere Adresse eingegeben/)
  })

  it('waits for the key a provider needs, but fetches when asked', () => {
    const plan = discoveryPlan(
      entries({ baseUrl: 'https://api.openai.com/v1', provider: 'openai' }),
    )
    expect(plan.automatic).toBe(false)
    expect(plan.request).toEqual({ baseUrl: 'https://api.openai.com/v1' })
    expect(plan.hint).toMatch(/Zugangsschlüssel eintragen/)
  })

  it('leaves the stored key to its own address and sends none once removal is asked', () => {
    const stored = { baseUrl: 'https://api.openai.com/v1', apiKeySet: true }
    expect(
      discoveryPlan(entries({ baseUrl: 'https://api.openai.com/v1', provider: 'openai', stored }))
        .request,
    ).toEqual({ baseUrl: 'https://api.openai.com/v1' })
    expect(discoveryPlan(entries({ stored })).request).toBeNull()
    expect(discoveryPlan(entries({ stored, removeKey: true })).request).toEqual({
      baseUrl: 'http://ollama:11434/v1',
      apiKey: '',
    })
  })
})
