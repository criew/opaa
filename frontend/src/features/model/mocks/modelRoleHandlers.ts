import { http, HttpResponse } from 'msw'
import type {
  ModelRoleTestRequest,
  RerankModelSettingsRequest,
  TextRecognitionModelSettingsRequest,
  TextRecognitionTestRequest,
  TextRecognitionTestResponse,
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

const TEXT_RECOGNITION_OFF =
  'Die Texterkennung ist im Betrieb abgeschaltet (OPAA_OCR_ENABLED); die Rolle lässt sich deshalb weder einschalten noch testen.'

function fieldError(field: string, code: string, message: string) {
  return HttpResponse.json(
    { error: message, status: 400, fieldErrors: [{ field, code, message }] },
    { status: 400 },
  )
}

/**
 * The test outcome by a word in the draft, so every stage can be clicked through in the mocks:
 * an address with „gesperrt" fails the address check, „unerreichbar" is not reachable, a key
 * „falsch" is rejected, „ohne-engine" lacks the OCR engine and „leer" reads no text.
 */
function textRecognitionTestOutcome(body: TextRecognitionTestRequest): TextRecognitionTestResponse {
  if (body.baseUrl.includes('unerreichbar')) {
    return {
      success: false,
      outcome: 'UNREACHABLE',
      message: 'Die Verbindung wurde vom Server abgelehnt.',
    }
  }
  if (body.apiKey?.includes('falsch')) {
    return {
      success: false,
      outcome: 'KEY_REJECTED',
      message:
        'Der Dienst hat den Zugangsschlüssel abgelehnt (HTTP 401 oder 403). Prüfen Sie den Zugangsschlüssel.',
    }
  }
  if (body.baseUrl.includes('ohne-engine')) {
    return {
      success: false,
      outcome: 'ENGINE_MISSING',
      message:
        'Der Dienst ist erreichbar, kann die Testseite aber nicht mit RapidOCR lesen. Vermutlich fehlen die OCR-Engine oder ihre Modelle.',
    }
  }
  if (body.baseUrl.includes('leer')) {
    return {
      success: false,
      outcome: 'NO_TEXT',
      message: 'Der Dienst hat geantwortet, auf der Testseite aber keinen Text erkannt.',
    }
  }
  return {
    success: true,
    outcome: 'READY',
    message: 'Verbindung erfolgreich, der Dienst hat die Testseite gelesen.',
  }
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
      vocabulary: body.vocabulary?.trim() ?? mockModelRoles.transcription.vocabulary,
    }
    return HttpResponse.json(mockModelRoles.transcription)
  }),

  http.post('/api/v1/admin/models/transcription/test', async ({ request }) =>
    HttpResponse.json(testOutcome((await request.json()) as ModelRoleTestRequest)),
  ),

  http.get('/api/v1/admin/models/text-recognition', () =>
    HttpResponse.json(mockModelRoles.textRecognition),
  ),

  http.put('/api/v1/admin/models/text-recognition', async ({ request }) => {
    const body = (await request.json()) as TextRecognitionModelSettingsRequest
    const current = mockModelRoles.textRecognition
    if (body.enabled && !current.textRecognitionAvailable && !current.enabled) {
      return fieldError('textRecognition.enabled', 'BLOCKED', TEXT_RECOGNITION_OFF)
    }
    const vision = body.connectionType === 'OPENAI_VISION'
    const errors = requiredErrors('textRecognition', {
      ...body,
      model: vision ? body.model : 'not-needed',
    }).map((error) =>
      error.field.endsWith('.model')
        ? { ...error, message: 'Die Modell-Kennung fehlt; ein Vision-Modell braucht sie.' }
        : error,
    )
    if (errors.length > 0) {
      return HttpResponse.json({ error: errors[0].message, fieldErrors: errors }, { status: 400 })
    }
    mockModelRoles.textRecognition = {
      ...stored(current, { ...body, model: vision ? body.model : null }),
      connectionType: body.connectionType,
    }
    return HttpResponse.json(mockModelRoles.textRecognition)
  }),

  http.post('/api/v1/admin/models/text-recognition/test', async ({ request }) => {
    if (!mockModelRoles.textRecognition.textRecognitionAvailable) {
      return fieldError('textRecognition.enabled', 'BLOCKED', TEXT_RECOGNITION_OFF)
    }
    const body = (await request.json()) as TextRecognitionTestRequest
    if (body.baseUrl.includes('gesperrt')) {
      return fieldError(
        'textRecognition.baseUrl',
        'BLOCKED',
        'Die Adresse liegt in einem gesperrten Netzbereich.',
      )
    }
    return HttpResponse.json(textRecognitionTestOutcome(body))
  }),
]
