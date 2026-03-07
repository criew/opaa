import { http, HttpResponse } from 'msw'
import {
  mockHealthResponse,
  mockIndexingIdle,
  mockIndexingCompleted,
  getRandomMockResponse,
  mockErrorResponse,
  mockAuthConfig,
  mockLoginResponse,
  mockUser,
} from './fixtures'
import type { IndexingStatusResponse, QueryRequest } from '../types/api'
import type { LoginRequest } from '../types/auth'

let indexingPollCount = 0
let indexingActive = false

export function resetIndexingState() {
  indexingPollCount = 0
  indexingActive = false
}

const INDEXING_POLL_STEPS = 5
const TOTAL_DOCUMENTS = 42

function getRunningStatus(step: number): IndexingStatusResponse {
  const progress = Math.min(step / INDEXING_POLL_STEPS, 1)
  return {
    status: 'RUNNING',
    documentCount: Math.round(TOTAL_DOCUMENTS * progress),
    totalDocuments: TOTAL_DOCUMENTS,
    documentsSkipped: 0,
    message: `Indexing in progress... ${Math.round(TOTAL_DOCUMENTS * progress)} documents processed`,
    timestamp: new Date().toISOString(),
  }
}

export const handlers = [
  http.get('/api/health', () => {
    return HttpResponse.json(mockHealthResponse)
  }),

  http.post('/api/v1/indexing/trigger', async ({ request }) => {
    // Accept optional IndexingTriggerRequest body (ignored in mock)
    const contentType = request.headers.get('content-type')
    if (contentType?.includes('application/json')) {
      await request.json().catch(() => null)
    }

    indexingPollCount = 0
    indexingActive = true
    return HttpResponse.json(
      {
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        message: 'Indexing started',
        timestamp: new Date().toISOString(),
      } satisfies IndexingStatusResponse,
      { status: 202 },
    )
  }),

  http.get('/api/v1/indexing/status', () => {
    if (!indexingActive) {
      return HttpResponse.json(mockIndexingIdle)
    }

    indexingPollCount++

    if (indexingPollCount >= INDEXING_POLL_STEPS) {
      indexingActive = false
      return HttpResponse.json(mockIndexingCompleted)
    }

    return HttpResponse.json(getRunningStatus(indexingPollCount))
  }),

  http.post('/api/v1/query', async ({ request }) => {
    const body = (await request.json()) as QueryRequest
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json(
        { ...mockErrorResponse, timestamp: new Date().toISOString() },
        { status: 400 },
      )
    }
    const mockResponse = getRandomMockResponse()
    return HttpResponse.json({
      ...mockResponse,
      conversationId: body.conversationId ?? crypto.randomUUID(),
    })
  }),

  http.get('/api/v1/auth/config', () => {
    return HttpResponse.json(mockAuthConfig)
  }),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as LoginRequest
    if (body.username === 'admin' && body.password === 'admin') {
      return HttpResponse.json(mockLoginResponse)
    }
    return HttpResponse.json({ error: 'Invalid credentials' }, { status: 401 })
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
