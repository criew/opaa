import { http, HttpResponse } from 'msw'
import { mockAuthConfig } from './authFixtures'
import { mockMyCapabilities } from './capabilityFixtures'

export const capabilityHandlers = [
  // The delivered state of ADR-0036, Entscheidung 5: all three creation capabilities, no group one.
  http.get('/api/v1/me/capabilities', () => {
    // Like the backend (ADR-0044): a switched-off feature's creation right is never held.
    if (mockAuthConfig.features?.promptLibraries === false) {
      return HttpResponse.json({
        ...mockMyCapabilities,
        capabilities: mockMyCapabilities.capabilities.filter(
          (capability) => capability !== 'CREATE_PROMPT_LIBRARY',
        ),
      })
    }
    return HttpResponse.json(mockMyCapabilities)
  }),
]
