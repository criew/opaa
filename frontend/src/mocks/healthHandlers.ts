import { http, HttpResponse } from 'msw'
import { mockHealthResponse } from './healthFixtures'

export const healthHandlers = [
  http.get('/api/health', () => {
    return HttpResponse.json(mockHealthResponse)
  }),
]
