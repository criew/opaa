import { http, HttpResponse } from 'msw'
import { mockAuthConfig, setMockQuickTourHintDone } from '@/shared/mocks/authFixtures'

/**
 * PUT /api/v1/me/quick-tour-hint: idempotent 204 that the next GET /api/v1/auth/me reflects;
 * with the quick tour switched off the route does not exist and answers 404 like the backend.
 */
export const quickTourHintHandlers = [
  http.put('/api/v1/me/quick-tour-hint', () => {
    if (mockAuthConfig.features?.quickTour === false) {
      return new HttpResponse(null, { status: 404 })
    }
    setMockQuickTourHintDone(true)
    return new HttpResponse(null, { status: 204 })
  }),
]
