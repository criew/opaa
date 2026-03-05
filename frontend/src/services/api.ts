import axios, { AxiosError } from 'axios'
import type {
  HealthResponse,
  IndexingStatusResponse,
  IndexingTriggerRequest,
  QueryRequest,
  QueryResponse,
} from '../types/api'
import { isErrorResponse } from '../types/api'

const client = axios.create({
  baseURL: '/api',
})

function normalizeError(err: unknown): never {
  if (err instanceof AxiosError) {
    const data = err.response?.data

    if (isErrorResponse(data)) {
      throw new Error(data.error)
    }

    if (err.response?.status) {
      throw new Error(`HTTP ${err.response.status}: ${err.message}`)
    }

    throw new Error(err.message)
  }
  throw err
}

export async function getHealth(): Promise<HealthResponse> {
  try {
    const { data } = await client.get<HealthResponse>('/health')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function sendQuery(question: string, conversationId?: string): Promise<QueryResponse> {
  try {
    const request: QueryRequest = { question, conversationId }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function triggerIndexing(
  request?: IndexingTriggerRequest,
): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>('/v1/indexing/trigger', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getIndexingStatus(): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.get<IndexingStatusResponse>('/v1/indexing/status')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
