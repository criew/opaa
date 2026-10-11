import type { DiscoveredLlmModel } from '../../../types/api'

export type EndpointPresetKey = 'ollama-host' | 'ollama-compose' | 'openai' | 'custom'

export interface EndpointPreset {
  key: EndpointPresetKey
  label: string
  description: string
  /** `null` for the free address. */
  baseUrl: string | null
  /** Whether the endpoint answers without an access key, so the list can be fetched at once. */
  keyless: boolean
}

export const ENDPOINT_PRESETS: readonly EndpointPreset[] = [
  {
    key: 'ollama-host',
    label: 'Ollama auf diesem Rechner',
    description: 'Ollama läuft auf dem Rechner, auf dem OPAA im Container läuft.',
    baseUrl: 'http://host.docker.internal:11434/v1',
    keyless: true,
  },
  {
    key: 'ollama-compose',
    label: 'Ollama im Compose-Stack',
    description: 'Der Ollama-Dienst aus dem mitgelieferten Docker-Compose-Stack.',
    baseUrl: 'http://ollama:11434/v1',
    keyless: true,
  },
  {
    key: 'openai',
    label: 'OpenAI',
    description: 'Modelle von OpenAI. Braucht einen Zugangsschlüssel.',
    baseUrl: 'https://api.openai.com/v1',
    keyless: false,
  },
  {
    key: 'custom',
    label: 'Eigene Adresse',
    description: 'vLLM, LiteLLM, Azure oder ein anderer Server mit derselben Schnittstelle.',
    baseUrl: null,
    keyless: false,
  },
]

function normalized(baseUrl: string): string {
  return baseUrl.trim().replace(/\/+$/, '')
}

/** The preset an address belongs to; any other non-empty address is a custom one. */
export function presetOf(baseUrl: string): EndpointPresetKey | null {
  const address = normalized(baseUrl)
  if (address === '') return null
  return ENDPOINT_PRESETS.find((preset) => preset.baseUrl === address)?.key ?? 'custom'
}

/** A display name from an identifier: without the default `:latest` tag and a vendor path. */
export function displayNameFor(identifier: string): string {
  const withoutVendor = identifier.slice(identifier.lastIndexOf('/') + 1)
  return withoutVendor.replace(/:latest$/, '')
}

const MIN_SUGGESTED_TOKENS = 256
export const MAX_SUGGESTED_TOKENS = 8192

/**
 * The suggested answer length for a context window: a quarter of it, so question, chat history and
 * retrieved passages keep the rest - at least 256 and at most 8192 tokens.
 */
export function suggestedMaxTokens(contextLength: number): number {
  return Math.min(
    MAX_SUGGESTED_TOKENS,
    Math.max(MIN_SUGGESTED_TOKENS, Math.floor(contextLength / 4)),
  )
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

export function formatTokens(tokens: number): string {
  return integer.format(tokens)
}
