import axios, { AxiosError, isAxiosError, isCancel } from 'axios'
import type { AxiosAdapter, AxiosResponse } from 'axios'
import type { ErrorResponse, MetadataFilter, QueryResponse } from '@/shared/api/types'
import { isErrorResponse } from '@/shared/api/types'
import type { components } from '@/shared/api/generated/api'
import { apiClient as client, normalizeError } from '@/shared/api/api'
import { queryRequest, sendQuery } from './queryApi'

type QueryStreamEvent = components['schemas']['QueryStreamEvent']

export interface StreamQueryOptions {
  /** The provisional answer so far - unvalidated, citation markers included. */
  onProvisional: (text: string) => void
  signal?: AbortSignal
}

export const STREAM_BROKEN_MESSAGE =
  'Die Verbindung zur Antwort ist abgebrochen. Bitte die Frage erneut senden.'

/**
 * The fetch adapter, with the body of a refusal read as JSON: the request asks for a stream, but
 * a status before the first event is an ordinary error envelope, which the session interceptors
 * (401, 403 with a code) and normalizeError expect parsed.
 */
const streamAdapter: AxiosAdapter = async (config) => {
  const fetchAdapter = axios.getAdapter('fetch')
  // fetch outside a browser resolves no relative address; in one this changes nothing.
  const baseURL = new URL(config.baseURL ?? '', `${window.location.origin}/`).href
  try {
    return await fetchAdapter({ ...config, baseURL })
  } catch (err) {
    if (isAxiosError(err) && err.response && err.response.data instanceof ReadableStream) {
      err.response.data = parseJson(await new Response(err.response.data).text())
    }
    throw err
  }
}

/**
 * Asks the question as POST /api/v1/query/stream and resolves to the same QueryResponse
 * POST /api/v1/query returns, telling `onProvisional` the provisional text on the way. A backend
 * without the stream (an older instance during an update) is asked over POST /api/v1/query
 * instead. An `error` event rejects like the status it names; aborting `signal` rejects with the
 * cancellation, which the caller recognises by the aborted signal.
 */
export async function streamQuery(
  question: string,
  chatId: string | undefined,
  useKnowledge: boolean,
  libraryIds: string[] | undefined,
  metadataFilter: MetadataFilter | null | undefined,
  usedPromptId: string | undefined,
  options: StreamQueryOptions,
): Promise<QueryResponse> {
  const request = queryRequest(
    question,
    chatId,
    useKnowledge,
    libraryIds,
    metadataFilter,
    usedPromptId,
  )
  let response: AxiosResponse<ReadableStream<Uint8Array>>
  try {
    response = await client.post<ReadableStream<Uint8Array>>('/v1/query/stream', request, {
      adapter: streamAdapter,
      responseType: 'stream',
      signal: options.signal,
      headers: { Accept: 'text/event-stream, application/json' },
    })
  } catch (err) {
    if (isCancel(err)) throw err
    const status = isAxiosError(err) ? err.response?.status : undefined
    if (status === 404 || status === 405) {
      return sendQuery(question, chatId, useKnowledge, libraryIds, metadataFilter, usedPromptId)
    }
    normalizeError(err)
  }
  return readEvents(response.data, options.onProvisional)
}

async function readEvents(
  body: ReadableStream<Uint8Array>,
  onProvisional: (text: string) => void,
): Promise<QueryResponse> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let provisional = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let boundary = frameBoundary(buffer)
      while (boundary) {
        const event = eventOf(buffer.slice(0, boundary.index))
        buffer = buffer.slice(boundary.index + boundary.length)
        boundary = frameBoundary(buffer)
        if (!event) continue
        switch (event.type) {
          case 'delta':
            provisional += event.text ?? ''
            onProvisional(provisional)
            break
          case 'reset':
            provisional = event.text ?? ''
            onProvisional(provisional)
            break
          case 'final':
            if (event.response) return event.response as QueryResponse
            break
          case 'error':
            throw streamedError(event.error)
        }
      }
      if (done) throw new Error(STREAM_BROKEN_MESSAGE)
    }
  } finally {
    // Whatever ends the reading - the closing event, a failure, the person cancelling - the body
    // is not read any further, and the connection is given up rather than drained.
    reader.cancel().catch(() => {})
  }
}

function frameBoundary(buffer: string): { index: number; length: number } | null {
  const match = /\r?\n\r?\n/.exec(buffer)
  return match ? { index: match.index, length: match[0].length } : null
}

/** One server-sent event, or null for a comment such as the keep-alive ping. */
function eventOf(frame: string): QueryStreamEvent | null {
  const data = frame
    .split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).replace(/^ /, ''))
    .join('\n')
  if (!data) return null
  const parsed = parseJson(data)
  return parsed && typeof parsed === 'object' && 'type' in parsed
    ? (parsed as QueryStreamEvent)
    : null
}

/** The error of a stream, shaped like a refused request so that callers read its status. */
function streamedError(envelope: ErrorResponse | undefined): Error {
  if (!envelope || !isErrorResponse(envelope)) return new Error(STREAM_BROKEN_MESSAGE)
  const cause = new AxiosError(envelope.error, undefined, undefined, undefined, {
    status: envelope.status,
    data: envelope,
  } as AxiosResponse)
  return new Error(envelope.error, { cause })
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}
