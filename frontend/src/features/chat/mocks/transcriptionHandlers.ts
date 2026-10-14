import { http, HttpResponse } from 'msw'
import { mockAuthConfig } from '@/shared/mocks/authFixtures'

/** The text every mocked recording is recognised as. */
export const MOCK_TRANSCRIPT = 'Welche Unterlagen brauche ich für einen Bauantrag?'

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
]
