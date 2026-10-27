import { describe, expect, it } from 'vitest'
import type { DiscoveredLlmModel } from '@/shared/api/types'
import {
  describeModel,
  displayNameFor,
  embeddingSuitable,
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
      expect(presetOf(preset.baseUrl)).toBe(preset)
      expect(presetOf(`${preset.baseUrl}/`)).toBe(preset)
    }
  })

  it('knows no preset for any other or an empty address', () => {
    expect(presetOf('http://vllm.intern:8000/v1')).toBeNull()
    expect(presetOf('http://host.docker.internal:11434/v1')).toBeNull()
    expect(presetOf('   ')).toBeNull()
  })

  it('offers Ollama, OpenAI and Anthropic; only Ollama has a host to confirm', () => {
    expect(
      ENDPOINT_PRESETS.map((preset) => [preset.label, preset.baseUrl, preset.placeholderHost]),
    ).toEqual([
      ['Ollama', 'http://ollama:11434/v1', 'ollama'],
      ['OpenAI', 'https://api.openai.com/v1', null],
      ['Anthropic (Claude)', 'https://api.anthropic.com/v1', null],
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
  it('keeps the default answer length unless it exceeds half the context window', () => {
    expect(suggestedMaxTokens(131072)).toBe(8000)
    expect(suggestedMaxTokens(40960)).toBe(8000)
    expect(suggestedMaxTokens(8192)).toBe(4096)
    expect(suggestedMaxTokens(2048)).toBe(1024)
    expect(suggestedMaxTokens(300)).toBe(256)
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

describe('embeddingSuitable', () => {
  it('follows the reported capabilities when there are any', () => {
    expect(embeddingSuitable(model({ capabilities: ['embedding'] }))).toBe(true)
    expect(embeddingSuitable(model({ identifier: 'bge-m3', capabilities: ['completion'] }))).toBe(
      false,
    )
  })

  it('falls back to the name and family of a model without capabilities', () => {
    expect(embeddingSuitable(model({ identifier: 'text-embedding-3-small' }))).toBe(true)
    expect(embeddingSuitable(model({ identifier: 'bge-m3' }))).toBe(true)
    expect(embeddingSuitable(model({ identifier: 'custom', family: 'nomic-bert' }))).toBe(true)
    expect(embeddingSuitable(model({ identifier: 'gpt-4o' }))).toBe(false)
  })
})
