import type {
  TranscriptionAvailabilityResponse,
  TranscriptionResponse,
  TranscriptionVocabulary,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'
import { useAuthStore } from '@/shared/session/authStore'
import { getDevUser } from '@/shared/session/devAuth'

/** The subprotocol of OPAA's live dictation; the credentials travel as further subprotocols. */
export const TRANSCRIPTION_STREAM_PROTOCOL = 'opaa-transcription.v1'

/**
 * Opens the live dictation's WebSocket on the page's own origin. A browser cannot set headers on a
 * WebSocket, so the access token and, in dev mode, the dev user go as subprotocols; the backend
 * turns them back into headers before its security chain and answers only the plain subprotocol.
 */
export async function openTranscriptionStream(): Promise<WebSocket> {
  const token = await useAuthStore.getState().getAccessToken()
  const devUser = getDevUser()
  const protocols = [TRANSCRIPTION_STREAM_PROTOCOL]
  if (token) protocols.push(`opaa-bearer.${token}`)
  if (devUser) protocols.push(`opaa-dev-user.${devUser}`)
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const socket = new WebSocket(
    `${scheme}://${window.location.host}/api/v1/transcriptions/stream`,
    protocols,
  )
  socket.binaryType = 'arraybuffer'
  return socket
}

/** Whether dictation works right now; answered from the role's last known state. */
export async function getTranscriptionAvailability(): Promise<TranscriptionAvailabilityResponse> {
  try {
    const { data } = await client.get<TranscriptionAvailabilityResponse>(
      '/v1/transcriptions/availability',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Sends one recording as the raw request body with its own audio content type - not multipart, so
 * the server keeps it in memory only - and answers the recognised text.
 */
export async function transcribeRecording(recording: Blob): Promise<string> {
  try {
    const { data } = await client.post<TranscriptionResponse>('/v1/transcriptions', recording, {
      headers: { 'Content-Type': recording.type || 'audio/webm' },
    })
    return data.text
  } catch (err) {
    normalizeError(err)
  }
}

/** The caller's own vocabulary list for dictation; empty when none is stored. */
export async function getMyTranscriptionVocabulary(): Promise<string> {
  try {
    const { data } = await client.get<TranscriptionVocabulary>('/v1/me/transcription-vocabulary')
    return data.vocabulary
  } catch (err) {
    normalizeError(err)
  }
}

/** Replaces the caller's own vocabulary list; an empty list removes it. */
export async function updateMyTranscriptionVocabulary(vocabulary: string): Promise<string> {
  try {
    const { data } = await client.put<TranscriptionVocabulary>('/v1/me/transcription-vocabulary', {
      vocabulary,
    })
    return data.vocabulary
  } catch (err) {
    normalizeError(err)
  }
}
