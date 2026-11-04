import type {
  TranscriptionAvailabilityResponse,
  TranscriptionResponse,
  TranscriptionVocabulary,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

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
