import { describe, expect, it } from 'vitest'
import type { DiscoveredLlmModel } from '../../../types/api'
import {
  describeModel,
  displayNameFor,
  ENDPOINT_PRESETS,
  presetOf,
  suggestedMaxTokens,
} from './modelDiscovery'

function model(overrides: Partial<DiscoveredLlmModel> = {}): DiscoveredLlmModel {
  return { identifier: 'qwen3:8b', capabilities: [], chatSuitable: true, ...overrides }
}

describe('presetOf', () => {
  it('recognizes each preset by its address, with or without trailing slash', () => {
    for (const preset of ENDPOINT_PRESETS) {
      if (preset.baseUrl === null) continue
      expect(presetOf(preset.baseUrl)).toBe(preset.key)
      expect(presetOf(`${preset.baseUrl}/`)).toBe(preset.key)
    }
  })

  it('names every other address a custom one and an empty address none', () => {
    expect(presetOf('http://vllm.intern:8000/v1')).toBe('custom')
    expect(presetOf('   ')).toBeNull()
  })

  it('offers the three addresses the issue names', () => {
    expect(ENDPOINT_PRESETS.map((preset) => preset.baseUrl)).toEqual([
      'http://host.docker.internal:11434/v1',
      'http://ollama:11434/v1',
      'https://api.openai.com/v1',
      null,
    ])
  })
})

describe('displayNameFor', () => {
  it('drops the default tag and a vendor path, keeps everything else', () => {
    expect(displayNameFor('qwen3:8b')).toBe('qwen3:8b')
    expect(displayNameFor('llama3.2:latest')).toBe('llama3.2')
    expect(displayNameFor('meta-llama/Llama-3.1-8B-Instruct')).toBe('Llama-3.1-8B-Instruct')
    expect(displayNameFor('gpt-4o')).toBe('gpt-4o')
  })
})

describe('suggestedMaxTokens', () => {
  it('suggests a quarter of the context window, between 256 and 8192 tokens', () => {
    expect(suggestedMaxTokens(8192)).toBe(2048)
    expect(suggestedMaxTokens(40960)).toBe(8192)
    expect(suggestedMaxTokens(131072)).toBe(8192)
    expect(suggestedMaxTokens(512)).toBe(256)
  })
})

describe('describeModel', () => {
  it('lists parameters, size, context and capabilities in German', () => {
    expect(
      describeModel(
        model({
          parameterSize: '8.2B',
          sizeBytes: 5225388164,
          contextLength: 40960,
          capabilities: ['completion', 'tools', 'vision', 'thinking'],
        }),
      ),
    ).toEqual([
      '8,2 Mrd. Parameter',
      '5,2 GB',
      'Kontext 40.960 Token',
      'Werkzeuge',
      'Bilder',
      'Denkmodus',
    ])
  })

  it('names small models in millions and megabytes', () => {
    expect(describeModel(model({ parameterSize: '137M', sizeBytes: 274302450 }))).toEqual([
      '137 Mio. Parameter',
      '274 MB',
    ])
  })

  it('says nothing it does not know', () => {
    expect(describeModel(model())).toEqual([])
  })
})
