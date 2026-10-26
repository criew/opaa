import { http, HttpResponse } from 'msw'
import type {
  ModelRoleTestRequest,
  RerankModelSettingsRequest,
  TranscriptionModelSettingsRequest,
} from '@/shared/api/types'
import { mockModelRoles, resetMockModelRoles } from './modelRoleFixtures'

export function resetModelRoleMockState() {
  resetMockModelRoles()
}

type RoleRequest = RerankModelSettingsRequest & { language?: string | null }

/** The backend's required-field rule of a switched-on role, as field errors. */
function requiredErrors(prefix: string, body: RoleRequest) {
  if (!body.enabled) return []
  const errors = []
  if (!body.baseUrl?.trim()) {
    errors.push({
      field: `${prefix}.baseUrl`,
      code: 'REQUIRED',
      message: 'Die Basis-Adresse fehlt.',
    })
  }
  if (!body.model?.trim()) {
    errors.push({
      field: `${prefix}.model`,
      code: 'REQUIRED',
      message: 'Die Modell-Kennung fehlt.',
    })
  }
  return errors
}

function stored<T extends { apiKeySet: boolean }>(current: T, body: RoleRequest) {
  const apiKeySet =
    body.apiKey === undefined || body.apiKey === null ? current.apiKeySet : body.apiKey !== ''
  return {
    ...current,
    enabled: body.enabled,
    baseUrl: body.baseUrl?.trim() || null,
    model: body.model?.trim() || null,
    apiKeySet,
    state: body.enabled ? ('ACTIVE' as const) : ('DISABLED' as const),
    updatedAt: new Date().toISOString(),
  }
}

function testOutcome(body: ModelRoleTestRequest) {
  return body.baseUrl.includes('unerreichbar')
    ? { success: false, message: 'Die Verbindung wurde vom Server abgelehnt.' }
    : { success: true, message: 'Verbindung erfolgreich, der Endpunkt hat geantwortet.' }
}

// Registered before modelHandlers: '/api/v1/admin/models/:modelId' would match these paths too.
export const modelRoleHandlers = [
  http.get('/api/v1/admin/models/rerank', () => HttpResponse.json(mockModelRoles.rerank)),

  http.put('/api/v1/admin/models/rerank', async ({ request }) => {
    const body = (await request.json()) as RoleRequest
    const errors = requiredErrors('rerank', body)
    if (errors.length > 0) {
      return HttpResponse.json({ error: errors[0].message, fieldErrors: errors }, { status: 400 })
    }
    mockModelRoles.rerank = stored(mockModelRoles.rerank, body)
    return HttpResponse.json(mockModelRoles.rerank)
  }),

  http.post('/api/v1/admin/models/rerank/test', async ({ request }) =>
    HttpResponse.json(testOutcome((await request.json()) as ModelRoleTestRequest)),
  ),

  http.get('/api/v1/admin/models/transcription', () =>
    HttpResponse.json(mockModelRoles.transcription),
  ),

  http.put('/api/v1/admin/models/transcription', async ({ request }) => {
    const body = (await request.json()) as TranscriptionModelSettingsRequest
    const errors = requiredErrors('transcription', body)
    if (errors.length > 0) {
      return HttpResponse.json({ error: errors[0].message, fieldErrors: errors }, { status: 400 })
    }
    mockModelRoles.transcription = {
      ...stored(mockModelRoles.transcription, body),
      language: body.language?.trim().toLowerCase() ?? '',
    }
    return HttpResponse.json(mockModelRoles.transcription)
  }),

  http.post('/api/v1/admin/models/transcription/test', async ({ request }) =>
    HttpResponse.json(testOutcome((await request.json()) as ModelRoleTestRequest)),
  ),
]
