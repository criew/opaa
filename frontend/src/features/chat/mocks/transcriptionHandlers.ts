import { delay, http, HttpResponse } from 'msw'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { LAB_SPOKEN_TEXT } from './mockSpeech'
import { mockAuthConfig } from '@/shared/mocks/authFixtures'

/** The text every mocked recording is recognised as. */
export const MOCK_TRANSCRIPT = 'Welche Unterlagen brauche ich für einen Bauantrag?'

/** The mocked person's own vocabulary list. */
export const mockTranscriptionVocabulary = { value: '' }

export const transcriptionHandlers = [
  // Like the backend (ADR-0044): with the feature switched off the routes do not exist.
  http.get('/api/v1/transcriptions/availability', () => {
    if (mockAuthConfig.features?.speechInput === false) {
      return new HttpResponse(null, { status: 404 })
    }
    const variant = currentUiLabVariant()
    return HttpResponse.json({
      available: true,
      realtimeAvailable: variant?.endsWith('-live') ?? false,
    })
  }),
  http.post('/api/v1/transcriptions', async () => {
    if (mockAuthConfig.features?.speechInput === false) {
      return new HttpResponse(null, { status: 404 })
    }
    if (!import.meta.env.VITE_ENABLE_MOCKS) return HttpResponse.json({ text: MOCK_TRANSCRIPT })
    await delay(1400)
    return HttpResponse.json({ text: LAB_SPOKEN_TEXT })
  }),
  http.get('/api/v1/me/transcription-vocabulary', () => {
    if (mockAuthConfig.features?.speechInput === false) {
      return new HttpResponse(null, { status: 404 })
    }
    return HttpResponse.json({ vocabulary: mockTranscriptionVocabulary.value })
  }),
  http.put('/api/v1/me/transcription-vocabulary', async ({ request }) => {
    const body = (await request.json()) as { vocabulary: string }
    const vocabulary = body.vocabulary.trim()
    if (vocabulary.length > 500) {
      // Like the backend: bean validation refuses the request without field errors.
      return HttpResponse.json(
        { error: 'vocabulary: size must be between 0 and 500' },
        { status: 400 },
      )
    }
    mockTranscriptionVocabulary.value = vocabulary
    return HttpResponse.json({ vocabulary })
  }),
]
