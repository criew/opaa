import type { RealtimeTranscriptionProtocol } from '@/shared/api/types'

/** A form's choice of live transcription; `live` false means dictation by recording only. */
export interface RealtimeDraft {
  live: boolean
  protocol: RealtimeTranscriptionProtocol | null
  url: string
  model: string
}

export const NO_REALTIME: RealtimeDraft = { live: false, protocol: null, url: '', model: '' }

/** One live dialect as the forms offer it: its name and what choosing it fills in. */
export interface RealtimeProtocolPreset {
  protocol: RealtimeTranscriptionProtocol
  label: string
  description: string
  url: string
  model: string
  /** Whether the preset names a service outside the house; the forms then show the notice. */
  external: boolean
}

/**
 * The three dialects the backend speaks (ADR-0070), in the order the forms list them. The
 * addresses are the providers' documented ones; the vLLM host is a placeholder to adjust.
 */
export const REALTIME_PROTOCOL_PRESETS: readonly RealtimeProtocolPreset[] = [
  {
    protocol: 'OPENAI',
    label: 'OpenAI',
    description: 'Realtime-API von OpenAI; braucht einen Zugangsschlüssel.',
    url: 'wss://api.openai.com/v1/realtime?intent=transcription',
    model: 'gpt-live-transcribe',
    external: true,
  },
  {
    protocol: 'VLLM',
    label: 'vLLM (eigener Server)',
    description:
      'Eigener vLLM-Server mit einem Live-Modell wie Voxtral Realtime; braucht eine GPU mit mindestens 16 GB Speicher. Den Host anpassen.',
    url: 'ws://vllm:8000/v1/realtime',
    model: 'mistralai/Voxtral-Mini-4B-Realtime-2602',
    external: false,
  },
  {
    protocol: 'MISTRAL',
    label: 'Mistral',
    description: 'Realtime-Transkription von Mistral (Voxtral); braucht einen Zugangsschlüssel.',
    url: 'wss://api.mistral.ai/v1/audio/transcriptions/realtime',
    model: 'voxtral-mini-transcribe-realtime-3',
    external: true,
  },
]

export function realtimePresetOf(
  protocol: RealtimeTranscriptionProtocol | null | undefined,
): RealtimeProtocolPreset | undefined {
  return REALTIME_PROTOCOL_PRESETS.find((preset) => preset.protocol === protocol)
}

/**
 * The address and model after switching the dialect: a field that is empty or still holds the
 * previous dialect's preset takes the new preset; what the person typed stays.
 */
export function afterProtocolSwitch(
  previous: RealtimeTranscriptionProtocol | null,
  next: RealtimeTranscriptionProtocol,
  url: string,
  model: string,
): { url: string; model: string } {
  const before = realtimePresetOf(previous)
  const after = realtimePresetOf(next)
  if (!after) return { url, model }
  const untouched = (value: string, presetValue: string | undefined) =>
    value.trim() === '' || value.trim() === presetValue
  return {
    url: untouched(url, before?.url) ? after.url : url,
    model: untouched(model, before?.model) ? after.model : model,
  }
}

const WEB_SOCKET_ADDRESS = /^wss?:\/\/[^\s/?#@]+/i

/** The forms' own check of a live address; the backend applies the full address check. */
export function realtimeUrlError(value: string): string | undefined {
  if (value.trim() === '') return 'Bitte die Live-Adresse angeben.'
  return WEB_SOCKET_ADDRESS.test(value.trim())
    ? undefined
    : 'Die Live-Adresse beginnt mit ws:// oder wss://.'
}

/** Scheme, host and port of an address, `ws`/`wss` read as `http`/`https`; null when unreadable. */
function originOf(address: string): string | null {
  try {
    return new URL(address.trim().replace(/^ws(s?):/i, 'http$1:')).origin
  } catch {
    return null
  }
}

/** Whether the live address has the scheme, host and port of the dictation endpoint. */
export function sharesOrigin(liveUrl: string, batchUrl: string): boolean {
  const live = originOf(liveUrl)
  return live !== null && live === originOf(batchUrl)
}

/**
 * Whether a live upstream would run without a key it needs, as the backend refuses it: OpenAI and
 * Mistral ask for one, and the key of the transcription model reaches the live address only on
 * the transcription model's origin.
 */
export function liveKeyMissing({
  protocol,
  liveUrl,
  ownKey,
  batchUrl,
  batchKey,
}: {
  protocol: RealtimeTranscriptionProtocol | null
  liveUrl: string
  ownKey: boolean
  batchUrl: string
  batchKey: boolean
}): boolean {
  if (protocol !== 'OPENAI' && protocol !== 'MISTRAL') return false
  if (ownKey) return false
  return !(batchKey && sharesOrigin(liveUrl, batchUrl))
}

/** A dictation endpoint the forms fill in for a live service; never carries a key. */
export interface BatchPrefill {
  baseUrl: string
  model: string
}

/**
 * The dictation endpoint that goes with a live service, for an installation that has only that
 * service: OpenAI's transcription API, or the vLLM server of the live address with the live model.
 * Mistral gets none, as its batch endpoint is not known to work with OPAA's transcription client.
 */
export function batchPrefillFor(
  protocol: RealtimeTranscriptionProtocol | null,
  liveUrl: string,
  liveModel: string,
): BatchPrefill | null {
  if (protocol === 'OPENAI') {
    return { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini-transcribe' }
  }
  if (protocol !== 'VLLM') return null
  const origin = originOf(liveUrl)
  return origin === null ? null : { baseUrl: `${origin}/v1`, model: liveModel.trim() }
}

/**
 * Whether the dictation endpoint may still be filled in: empty, or exactly what an earlier fill
 * left there.
 */
export function batchUntouched(
  baseUrl: string,
  model: string,
  previous: BatchPrefill | null,
): boolean {
  const address = baseUrl.trim()
  const identifier = model.trim()
  if (address === '' && identifier === '') return true
  return previous !== null && address === previous.baseUrl && identifier === previous.model
}

export const REALTIME_TEXTS = {
  sectionLabel: 'Live-Transkript (optional)',
  switchLabel: 'Live-Transkript einschalten',
  switchHint: 'Der Text erscheint schon beim Sprechen; das Mikrofon im Chat nutzt Live von selbst.',
  alwaysDictation:
    'Das Diktat bleibt immer verfügbar. Fällt der Live-Dienst aus, wird die ganze Aufnahme wie beim Diktat verarbeitet.',
  protocolLabel: 'Live-Dienst',
  urlLabel: 'Live-Adresse',
  urlHint: 'WebSocket-Adresse des Dienstes, beginnt mit ws:// oder wss://.',
  modelLabel: 'Live-Modell',
  externalService:
    'Während des Sprechens geht die Aufnahme laufend an einen externen Dienst außerhalb des Hauses. Das braucht eine eigene datenschutzrechtliche Prüfung.',
  batchPrefilled:
    'Basis-Adresse und Modell-Kennung des Diktats sind passend zum Live-Dienst vorbelegt; das Diktat braucht immer einen eigenen Endpunkt.',
  keyLabel: 'Live-Schlüssel',
  keyHint:
    'Leer lassen, wenn der Dienst keinen Schlüssel verlangt oder unter derselben Adresse wie das Transkriptionsmodell läuft; dann gilt dessen Schlüssel.',
  keyMissing:
    'OpenAI und Mistral verlangen einen Zugangsschlüssel: Bitte einen Live-Schlüssel eintragen; der Schlüssel des Transkriptionsmodells gilt nur, wenn die Live-Adresse denselben Host hat.',
} as const
