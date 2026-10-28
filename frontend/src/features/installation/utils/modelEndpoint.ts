import {
  ENDPOINT_PRESETS,
  type DiscoveryPurpose,
  type EndpointPreset,
  type EndpointPresetKey,
} from '@/shared/ui/modelDiscovery/modelDiscovery'
import type { InstallationModelDiscoveryRequest } from '../api/types'
import { urlError } from './validation'

/** A preset's provider, or an address of one's own. */
export type ProviderKey = EndpointPresetKey | 'custom'

const OLLAMA_ADDRESS = /:11434\/v1$/i

/**
 * The presets a role offers: Anthropic serves no embeddings, and of the three only OpenAI serves
 * transcriptions.
 */
export function providerPresets(purpose: DiscoveryPurpose): readonly EndpointPreset[] {
  if (purpose === 'chat') return ENDPOINT_PRESETS
  if (purpose === 'transcription') {
    return ENDPOINT_PRESETS.filter((preset) => preset.key === 'openai')
  }
  return ENDPOINT_PRESETS.filter((preset) => preset.key !== 'anthropic')
}

function normalized(baseUrl: string): string {
  return baseUrl.trim().replace(/\/+$/, '')
}

/** The provider an address belongs to: a preset's own address, Ollama's port, else one's own. */
export function providerOf(baseUrl: string, purpose: DiscoveryPurpose): ProviderKey | null {
  const address = normalized(baseUrl)
  if (address === '') return null
  const presets = providerPresets(purpose)
  const preset = presets.find((candidate) => candidate.baseUrl === address)
  if (preset) return preset.key
  const ollamaOffered = presets.some((candidate) => candidate.key === 'ollama')
  return ollamaOffered && OLLAMA_ADDRESS.test(address) ? 'ollama' : 'custom'
}

function endpointKey(baseUrl: string): string {
  const url = new URL(baseUrl.trim())
  return `${url.protocol}//${url.host}${url.pathname.replace(/\/+$/, '')}`
}

/** Scheme, host and port of an address, or `null` for no valid address. */
export function originOf(baseUrl: string): string | null {
  try {
    return new URL(baseUrl.trim()).origin
  } catch {
    return null
  }
}

/** Whether a stored key may serve `next`: scheme, host, port and path as stored, as the backend. */
export function sameEndpoint(stored: string, next: string): boolean {
  try {
    return endpointKey(stored) === endpointKey(next)
  } catch {
    return false
  }
}

export interface EndpointEntries {
  baseUrl: string
  provider: ProviderKey | null
  /** The key as typed in this visit; empty when nothing was typed. */
  apiKey: string
  /** The origin the address had when the key was typed; `null` when there was none. */
  keyOrigin?: string | null
  /** The stored key is to be removed with the next save. */
  removeKey: boolean
  /** The step's stored endpoint and whether a key is stored with it. */
  stored: { baseUrl: string; apiKeySet: boolean } | null
}

export interface DiscoveryPlan {
  /** What to send; `null` while nothing can be fetched. */
  request: InstallationModelDiscoveryRequest | null
  /** Whether to fetch without being asked: the address and, where needed, the key are in. */
  automatic: boolean
  /** What the person does next while nothing has been fetched. */
  hint: string
}

const ENTER_ADDRESS =
  'Anbieter wählen oder Basis-Adresse eintragen; dann ruft der Assistent die angebotenen Modelle ab.'
const ENTER_KEY = 'Zugangsschlüssel eintragen; dann ruft der Assistent die angebotenen Modelle ab.'
const KEY_LEFT_BEHIND =
  'Die Basis-Adresse weicht von der gespeicherten ab, der gespeicherte Schlüssel gilt nur für sie. Den Schlüssel neu eintragen oder entfernen, dann „Modelle abrufen“.'
const READY = '„Modelle abrufen“ zeigt, welche Modelle der Endpunkt anbietet.'
const KEY_FOR_OTHER_ADDRESS =
  'Der eingetragene Schlüssel wurde für eine andere Adresse eingegeben. „Modelle abrufen“ sendet ihn an diese Adresse.'

/**
 * When and with which key the endpoint's models are fetched. The stored key is sent only for the
 * address it was stored with; a typed key wins, a requested removal sends none.
 */
export function discoveryPlan(entries: EndpointEntries): DiscoveryPlan {
  const baseUrl = entries.baseUrl.trim()
  if (urlError(baseUrl, 'die Basis-Adresse') !== undefined) {
    return { request: null, automatic: false, hint: ENTER_ADDRESS }
  }
  const typedKey = entries.apiKey.trim()
  const storedKey = Boolean(entries.stored?.apiKeySet) && !entries.removeKey
  if (typedKey !== '') {
    // A typed key goes out on its own only to the origin it was typed for.
    const ownOrigin = !entries.keyOrigin || entries.keyOrigin === originOf(baseUrl)
    return {
      request: { baseUrl, apiKey: typedKey },
      automatic: ownOrigin,
      hint: ownOrigin ? READY : KEY_FOR_OTHER_ADDRESS,
    }
  }
  if (entries.removeKey) {
    return { request: { baseUrl, apiKey: '' }, automatic: true, hint: READY }
  }
  if (storedKey) {
    return entries.stored && sameEndpoint(entries.stored.baseUrl, baseUrl)
      ? { request: { baseUrl }, automatic: true, hint: READY }
      : { request: null, automatic: false, hint: KEY_LEFT_BEHIND }
  }
  const needsKey = ENDPOINT_PRESETS.some(
    (preset) => preset.key === entries.provider && preset.needsKey,
  )
  return needsKey
    ? { request: { baseUrl }, automatic: false, hint: ENTER_KEY }
    : { request: { baseUrl }, automatic: true, hint: READY }
}
