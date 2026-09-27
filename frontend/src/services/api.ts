import axios, { AxiosError } from 'axios'
import type { HealthResponse } from '../types/api'
import { isErrorResponse } from '../types/api'
import { setupAuthInterceptors } from './apiInterceptors'
import { useAuthStore } from '../stores/authStore'

const client = axios.create({
  baseURL: '/api',
})

/**
 * The one configured axios instance - interceptors, base URL, auth header. Every endpoint module
 * (`chatApi.ts`, `libraryApi.ts`, ...) shares it instead of building a second one that would miss
 * the token refresh.
 */
export const apiClient = client

setupAuthInterceptors(
  client,
  () => useAuthStore.getState().getAccessToken(),
  () => useAuthStore.getState().renewToken(),
  (reason) => useAuthStore.getState().expireSession(reason),
  (reason) => useAuthStore.getState().requirePasswordChange(reason),
)

//  (review): a bare 413 alone doesn't tell us the oversized body was a file - normalizeError is
// shared by every endpoint module, most of which only ever send small JSON payloads. Scoping
// the translated message to callers that actually upload a file (currently just uploadDocument)
// keeps it honest instead of guessing "Datei" for a hypothetical 413 on, say, updateSpaceDetails.
//
// Exported (only) so api.test.ts can exercise the context-scoping directly with a constructed
// AxiosError: a real multipart POST with a File/Blob body hangs indefinitely against msw/node in
// this project's jsdom test environment (reproduced independently of this change - plain JSON and
// urlencoded FormData bodies work fine, only a binary Blob/File part inside FormData hangs), so the
// upload-specific branch below cannot be exercised end-to-end through uploadDocument() in tests.
export function normalizeError(err: unknown, context?: 'upload'): never {
  if (err instanceof AxiosError) {
    const data = err.response?.data

    //  review: `cause` keeps the original AxiosError (and thus its response.status) reachable
    // for a caller that needs to distinguish e.g. 404 from any other failure - documentStore's
    // folder-not-found fallback is the first to rely on this; every other caller keeps using the
    // plain German message and can ignore cause entirely.
    if (isErrorResponse(data)) {
      throw new Error(data.error, { cause: err })
    }

    // the compose reverse proxy (frontend/nginx.conf) answers uploads above its own
    // client_max_body_size with a bare HTML 413 page, not the backend's JSON ErrorResponse -
    // isErrorResponse above is false for that body, so this would otherwise fall through to the
    // generic "HTTP 413: ..." message below, which is neither German nor understandable to users.
    if (err.response?.status === 413 && context === 'upload') {
      throw new Error('Die Datei ist zu groß für den Upload. Bitte eine kleinere Datei wählen.', {
        cause: err,
      })
    }

    if (err.response?.status) {
      throw new Error(`HTTP ${err.response.status}: ${err.message}`, { cause: err })
    }

    throw new Error(err.message, { cause: err })
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
