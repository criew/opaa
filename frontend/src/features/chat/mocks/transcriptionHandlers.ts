import { http, HttpResponse } from 'msw'
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
    return HttpResponse.json({ available: true, realtimeAvailable: false })
  }),
  http.post('/api/v1/transcriptions', () => {
    if (mockAuthConfig.features?.speechInput === false) {
      return new HttpResponse(null, { status: 404 })
    }
    return HttpResponse.json({ text: MOCK_TRANSCRIPT })
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
      const message = 'Die Begriffsliste umfasst höchstens 500 Zeichen.'
      return HttpResponse.json(
        { error: message, fieldErrors: [{ field: 'vocabulary', message }] },
        { status: 400 },
      )
    }
    mockTranscriptionVocabulary.value = vocabulary
    return HttpResponse.json({ vocabulary })
  }),
]
