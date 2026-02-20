import axios, { AxiosError } from 'axios'
import type {
  ErrorResponse,
  HealthResponse,
  IndexingStatusResponse,
  QueryRequest,
  QueryResponse,
} from '../types/api'

const client = axios.create({
  baseURL: '/api',
})

function normalizeError(err: unknown): never {
  if (err instanceof AxiosError) {
    const data = err.response?.data as ErrorResponse | undefined
    throw new Error(data?.error ?? err.message)
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

export async function sendQuery(question: string): Promise<QueryResponse> {
  try {
    const request: QueryRequest = { question }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function triggerIndexing(): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>('/v1/indexing/trigger')
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
