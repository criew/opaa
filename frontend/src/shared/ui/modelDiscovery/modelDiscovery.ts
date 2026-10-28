import type { DiscoveredLlmModel } from '@/shared/api/types'

export type EndpointPresetKey = 'ollama' | 'openai' | 'anthropic'

export interface EndpointPreset {
  key: EndpointPresetKey
  label: string
  description: string
  /** The address the preset fills in. */
  baseUrl: string
  /** The address as shown in the suggestion; differs where a part is still to be confirmed. */
  pattern: string
  /** The part of `baseUrl` the person has to confirm or overwrite; it is selected after choosing. */
  placeholderHost: string | null
  /** Whether the endpoint needs an access key before it lists its models. */
  needsKey: boolean
}

export const ENDPOINT_PRESETS: readonly EndpointPreset[] = [
  {
    key: 'ollama',
    label: 'Ollama',
    description: 'Den Host anpassen, auf dem Ollama läuft.',
    baseUrl: 'http://ollama:11434/v1',
    pattern: 'http://<host>:11434/v1',
    placeholderHost: 'ollama',
    needsKey: false,
  },
  {
    key: 'openai',
    label: 'OpenAI',
    description: 'Braucht einen Zugangsschlüssel.',
    baseUrl: 'https://api.openai.com/v1',
    pattern: 'https://api.openai.com/v1',
    placeholderHost: null,
    needsKey: true,
  },
  {
    key: 'anthropic',
    label: 'Anthropic (Claude)',
    description: 'Braucht einen Zugangsschlüssel. Temperatur leer lassen.',
    baseUrl: 'https://api.anthropic.com/v1',
    pattern: 'https://api.anthropic.com/v1',
    placeholderHost: null,
    needsKey: true,
  },
]

function normalized(baseUrl: string): string {
  return baseUrl.trim().replace(/\/+$/, '')
}

/** The preset whose address this is exactly; `null` for any other address. */
export function presetOf(baseUrl: string): EndpointPreset | null {
  const address = normalized(baseUrl)
  return ENDPOINT_PRESETS.find((preset) => preset.baseUrl === address) ?? null
}

/** A display name from an identifier: without the default `:latest` tag and a vendor path. */
export function displayNameFor(identifier: string): string {
  const withoutVendor = identifier.slice(identifier.lastIndexOf('/') + 1)
  return withoutVendor.replace(/:latest$/, '')
}

/** The answer length a new entry starts with; reasoning models count their reasoning against it. */
export const DEFAULT_MAX_TOKENS = 8000

const MIN_SUGGESTED_TOKENS = 256

/**
 * The suggested answer length for a context window: the default, unless it exceeds half the
 * window - question, chat history and retrieved passages need the other half. Never below 256.
 */
export function suggestedMaxTokens(contextLength: number): number {
  return Math.max(MIN_SUGGESTED_TOKENS, Math.min(DEFAULT_MAX_TOKENS, Math.floor(contextLength / 2)))
}

const CAPABILITY_LABELS: Record<string, string> = {
  tools: 'Werkzeuge',
  vision: 'Bilder',
  thinking: 'Denkmodus',
  embedding: 'Einbettung',
}

const decimal = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 })
const integer = new Intl.NumberFormat('de-DE')

function parameterLabel(parameterSize: string): string {
  const match = /^(\d+(?:\.\d+)?)\s*([BMK])$/i.exec(parameterSize.trim())
  if (!match) return `${parameterSize} Parameter`
  const amount = decimal.format(Number(match[1]))
  const unit = { B: 'Mrd.', M: 'Mio.', K: 'Tsd.' }[match[2].toUpperCase() as 'B' | 'M' | 'K']
  return `${amount} ${unit} Parameter`
}

function sizeLabel(bytes: number): string {
  if (bytes >= 1e9) return `${decimal.format(bytes / 1e9)} GB`
  return `${integer.format(Math.round(bytes / 1e6))} MB`
}

/** The facts the endpoint reported about a model, as short German phrases. */
export function describeModel(model: DiscoveredLlmModel): string[] {
  const parts: string[] = []
  if (model.parameterSize) parts.push(parameterLabel(model.parameterSize))
  if (model.sizeBytes) parts.push(sizeLabel(model.sizeBytes))
  if (model.contextLength) parts.push(`Kontext ${integer.format(model.contextLength)} Token`)
  for (const capability of model.capabilities) {
    const label = CAPABILITY_LABELS[capability]
    if (label) parts.push(label)
  }
  return parts
}

/** The role a discovered model is chosen for. */
export type DiscoveryPurpose = 'chat' | 'embedding' | 'rerank'

const EMBEDDING_NAME = /embed|bge|bert|minilm|mpnet/i
const RERANK_NAME = /rerank/i

/**
 * Whether a model can serve as the embedding model: reported capabilities decide when there are
 * any, otherwise its identifier or family naming an embedding model.
 */
export function embeddingSuitable(model: DiscoveredLlmModel): boolean {
  if (model.capabilities.length > 0) return model.capabilities.includes('embedding')
  return EMBEDDING_NAME.test(`${model.identifier} ${model.family ?? ''}`)
}

/**
 * Whether a model's identifier or family names a reranker; no endpoint reports a rerank
 * capability, so the name is all there is - the probe decides.
 */
export function rerankSuitable(model: DiscoveredLlmModel): boolean {
  return RERANK_NAME.test(`${model.identifier} ${model.family ?? ''}`)
}

export function formatTokens(tokens: number): string {
  return integer.format(tokens)
}
