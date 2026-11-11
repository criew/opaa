import {
  delay,
  getResponse,
  http,
  HttpResponse,
  isPassthroughResponse,
  type JsonBodyType,
  type RequestHandler,
} from 'msw'
import {
  currentMockScenario,
  scenarioErrorBody,
  slowScenarioDelayMs,
} from '@/shared/mocks/mockScenario'

/** Requests the shell needs to start at all; no scenario touches them. */
const SHELL_PATHS = [
  '/api/health',
  '/api/v1/auth/',
  '/api/v1/branding',
  '/api/v1/installation',
  '/api/v1/me/capabilities',
  '/api/v1/link-targets',
]

/** Fields under which a paged answer carries its entries. */
const ENTRY_FIELDS = ['content', 'items', 'entries'] as const

function isShellRequest(url: URL): boolean {
  return SHELL_PATHS.some((path) => url.pathname === path || url.pathname.startsWith(path))
}

/** A listing: a bare array or a paged object carrying its entries in one of {@link ENTRY_FIELDS}. */
function isListing(body: unknown): boolean {
  if (Array.isArray(body)) return true
  if (body === null || typeof body !== 'object') return false
  return ENTRY_FIELDS.some((field) => Array.isArray((body as Record<string, unknown>)[field]))
}

/** The same listing with no entries: arrays emptied, totals at zero, no further page. */
export function emptied(body: unknown): JsonBodyType {
  if (Array.isArray(body)) return []
  const copy: Record<string, unknown> = { ...(body as Record<string, unknown>) }
  for (const field of ENTRY_FIELDS) if (Array.isArray(copy[field])) copy[field] = []
  for (const field of ['total', 'totalElements', 'totalPages']) {
    if (typeof copy[field] === 'number') copy[field] = 0
  }
  if (typeof copy.hasMore === 'boolean') copy.hasMore = false
  return copy as JsonBodyType
}

async function jsonOf(response: Response): Promise<unknown> {
  if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
    return undefined
  }
  return response.clone().json()
}

/**
 * Applies the scenarios `empty`, `slow` and `failed` to every topic at once: it asks the topic
 * handlers for their answer and then empties a listing, fails it with 502, or holds a GET back
 * until the scenario's latency has passed - counted from the request, so a topic that waits on its
 * own is not delayed twice. An answer a topic already failed stays as it is. Other scenarios, other
 * methods and the requests the shell starts with fall through to the topic handlers unchanged.
 */
export function scenarioHandler(topicHandlers: RequestHandler[]) {
  return http.get('/api/*', async ({ request }) => {
    const scenario = currentMockScenario()
    if (scenario !== 'empty' && scenario !== 'slow' && scenario !== 'failed') return undefined
    if (isShellRequest(new URL(request.url))) return undefined

    const started = Date.now()
    const response = await getResponse(topicHandlers, request)
    if (!response || isPassthroughResponse(response)) return undefined

    if (scenario === 'slow') {
      await delay(Math.max(0, slowScenarioDelayMs() - (Date.now() - started)))
      return response
    }
    const body = await jsonOf(response)
    if (body === undefined || !isListing(body)) return response
    if (scenario === 'failed') {
      return HttpResponse.json(scenarioErrorBody(502, 'Der Dienst ist zurzeit nicht erreichbar.'), {
        status: 502,
      })
    }
    return HttpResponse.json(emptied(body), { status: response.status })
  })
}
