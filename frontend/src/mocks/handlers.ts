import { http, HttpResponse } from 'msw'
import { mockIndexingStatus, mockQueryResponse, mockErrorResponse } from './fixtures'
import type { QueryRequest } from '../types/api'

export const handlers = [
  http.post('/api/v1/indexing/trigger', () => {
    return HttpResponse.json(mockIndexingStatus)
  }),

  http.get('/api/v1/indexing/status', () => {
    return HttpResponse.json(mockIndexingStatus)
  }),

  http.post('/api/v1/query', async ({ request }) => {
    const body = (await request.json()) as QueryRequest
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json(
        { ...mockErrorResponse, timestamp: new Date().toISOString() },
        { status: 400 },
      )
    }
    return HttpResponse.json(mockQueryResponse)
  }),
]
