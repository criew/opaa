import { http, HttpResponse } from 'msw'
import {
  mockHealthResponse,
  mockIndexingIdle,
  mockIndexingCompleted,
  getRandomMockResponse,
  mockErrorResponse,
} from './fixtures'
import type { IndexingStatusResponse, QueryRequest } from '../types/api'

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
    message: `Indexing in progress... ${Math.round(TOTAL_DOCUMENTS * progress)} documents processed`,
    timestamp: new Date().toISOString(),
  }
}

export const handlers = [
  http.get('/api/health', () => {
    return HttpResponse.json(mockHealthResponse)
  }),

  http.post('/api/v1/indexing/trigger', () => {
    indexingPollCount = 0
    indexingActive = true
    return HttpResponse.json(
      {
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
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
    return HttpResponse.json(getRandomMockResponse())
  }),
]
