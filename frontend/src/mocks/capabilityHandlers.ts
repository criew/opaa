import { http, HttpResponse } from 'msw'
import { mockMyCapabilities } from './capabilityFixtures'

export const capabilityHandlers = [
  // The delivered state of ADR-0036, Entscheidung 5: all three creation capabilities, no group one.
  http.get('/api/v1/me/capabilities', () => {
    return HttpResponse.json(mockMyCapabilities)
  }),
]
