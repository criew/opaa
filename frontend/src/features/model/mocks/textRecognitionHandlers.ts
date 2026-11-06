import { delay, http, HttpResponse } from 'msw'
import type {
  TextRecognitionSettingsRequest,
  TextRecognitionSettingsResponse,
  TextRecognitionTestRequest,
  TextRecognitionTestResponse,
} from '@/shared/api/types'
import {
  currentMockScenario,
  scenarioLatency,
  type MockScenario,
} from '@/shared/mocks/mockScenario'

/**
 * The text recognition role (epic #2640) per mock scenario:
 * - `default`, `slow`: operation on, docling-serve bound and reachable
 * - `small`, `empty`: operation on, role untouched (factory default)
 * - `scale`: vision model with long address and model id
 * - `failed`: docling-serve bound but unreachable, every test refuses the key
 * - `partial`: operation switch off, an earlier configuration is still stored
 * Addresses with userinfo or an internal host other than `opaa-ocr` fail the address check (400,
 * code BLOCKED); an address containing `unerreichbar` is unreachable.
 */
function initial(scenario: MockScenario): TextRecognitionSettingsResponse {
  const factory: TextRecognitionSettingsResponse = {
    enabled: false,
    binding: 'DOCLING_SERVE',
    baseUrl: null,
    model: null,
    apiKeySet: false,
    state: 'DISABLED',
    operationEnabled: true,
    updatedAt: null,
    retiredVariables: [],
  }
  const bound: TextRecognitionSettingsResponse = {
    ...factory,
    enabled: true,
    baseUrl: 'http://opaa-ocr:5001',
    apiKeySet: true,
    state: 'ACTIVE',
    updatedAt: '2026-11-04T09:12:00Z',
  }
  switch (scenario) {
    case 'small':
    case 'empty':
      return factory
    case 'scale':
      return {
        ...bound,
        binding: 'OPENAI_VISION',
        baseUrl:
          'https://vision-gpu-cluster-01.rechenzentrum.landesverwaltung-rheinfurt.example/inference/v1',
        model: 'PaddlePaddle/PaddleOCR-VL-0.9B-Grundstuecksverkehrsgenehmigungsakte-feinabgestimmt',
      }
    case 'failed':
      return { ...bound, state: 'UNREACHABLE' }
    case 'partial':
      return { ...bound, enabled: false, state: 'DISABLED', operationEnabled: false }
    default:
      return bound
  }
}

let current: { scenario: MockScenario; settings: TextRecognitionSettingsResponse } | null = null

function settings(): TextRecognitionSettingsResponse {
  const scenario = currentMockScenario()
  if (!current || current.scenario !== scenario) current = { scenario, settings: initial(scenario) }
  return current.settings
}

export function resetTextRecognitionMockState() {
  current = null
}

const ALLOWED_INTERNAL_HOSTS = ['opaa-ocr']

/** The address check of model targets (ADR-0067): message for the field, or null. */
function addressRefusal(baseUrl: string): string | null {
  let url: URL
  try {
    url = new URL(baseUrl)
  } catch {
    return 'Die Adresse ist keine gültige http- oder https-Adresse.'
  }
  if (url.username || url.password) return 'Die Adresse darf keine Anmeldedaten enthalten.'
  const host = url.hostname
  const internal =
    host === 'localhost' ||
    /^(127|10)\./.test(host) ||
    /^192\.168\./.test(host) ||
    host.endsWith('.intern') ||
    (!host.includes('.') && !ALLOWED_INTERNAL_HOSTS.includes(host))
  if (internal) {
    return 'Dieses interne Ziel ist nicht freigegeben. Der Betrieb kann es in die Freigabeliste aufnehmen.'
  }
  return null
}

function fieldError(field: string, code: string, message: string) {
  return HttpResponse.json(
    { error: message, fieldErrors: [{ field: `textRecognition.${field}`, code, message }] },
    { status: 400 },
  )
}

function outcome(body: TextRecognitionTestRequest): TextRecognitionTestResponse {
  if (body.baseUrl.includes('unerreichbar')) {
    return {
      success: false,
      outcome: 'UNREACHABLE',
      message: 'Der Dienst antwortet nicht. Prüfen Sie Adresse und Port.',
    }
  }
  if (currentMockScenario() === 'failed') {
    return {
      success: false,
      outcome: 'KEY_REJECTED',
      message: 'Der Dienst hat den Schlüssel abgelehnt. Tragen Sie den Schlüssel des Dienstes ein.',
    }
  }
  if (body.binding === 'DOCLING_SERVE' && body.baseUrl.includes('docling-plain')) {
    return {
      success: false,
      outcome: 'ENGINE_MISSING',
      message: 'Der Dienst antwortet, kennt aber RapidOCR mit deutschen Modellen nicht.',
    }
  }
  return body.binding === 'DOCLING_SERVE'
    ? {
        success: true,
        outcome: 'OK',
        message: 'Verbindung erfolgreich: Testseite erkannt (1 Seite, 2,4 s).',
        pages: 1,
        durationMs: 2400,
      }
    : {
        success: true,
        outcome: 'OK',
        message: 'Verbindung erfolgreich: Das Modell hat die Testseite gelesen.',
        pages: 1,
        durationMs: 5100,
      }
}

export const textRecognitionHandlers = [
  http.get('/api/v1/admin/models/text-recognition', async () => {
    await scenarioLatency()
    return HttpResponse.json(settings())
  }),

  http.put('/api/v1/admin/models/text-recognition', async ({ request }) => {
    const body = (await request.json()) as TextRecognitionSettingsRequest
    const stored = settings()
    if (body.enabled && !stored.operationEnabled) {
      return fieldError(
        'enabled',
        'OPERATION_DISABLED',
        'Der Betrieb hat die Texterkennung nicht freigegeben.',
      )
    }
    const baseUrl = body.baseUrl?.trim() ?? ''
    if (body.enabled && baseUrl === '') {
      return fieldError('baseUrl', 'REQUIRED', 'Die Adresse fehlt.')
    }
    if (baseUrl !== '') {
      const refusal = addressRefusal(baseUrl)
      if (refusal) return fieldError('baseUrl', 'BLOCKED', refusal)
    }
    if (body.enabled && body.binding === 'OPENAI_VISION' && !body.model?.trim()) {
      return fieldError('model', 'REQUIRED', 'Die Modell-Kennung fehlt.')
    }
    const apiKeySet =
      body.apiKey === undefined || body.apiKey === null ? stored.apiKeySet : body.apiKey !== ''
    const next: TextRecognitionSettingsResponse = {
      ...stored,
      enabled: body.enabled,
      binding: body.binding,
      baseUrl: baseUrl || null,
      model: body.binding === 'OPENAI_VISION' ? body.model?.trim() || null : null,
      apiKeySet,
      state: !body.enabled
        ? 'DISABLED'
        : baseUrl.includes('unerreichbar') || currentMockScenario() === 'failed'
          ? 'UNREACHABLE'
          : 'ACTIVE',
      updatedAt: new Date().toISOString(),
    }
    current = { scenario: currentMockScenario(), settings: next }
    return HttpResponse.json(next)
  }),

  http.post('/api/v1/admin/models/text-recognition/test', async ({ request }) => {
    const body = (await request.json()) as TextRecognitionTestRequest
    const refusal = addressRefusal(body.baseUrl.trim())
    if (refusal) return fieldError('baseUrl', 'BLOCKED', refusal)
    if (currentMockScenario() === 'slow') await scenarioLatency()
    else await delay(600)
    return HttpResponse.json(outcome(body))
  }),
]
