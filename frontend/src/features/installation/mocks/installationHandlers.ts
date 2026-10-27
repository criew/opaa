import { http, HttpResponse } from 'msw'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import { discoveryFailure } from '@/features/model/mocks/modelHandlers'
import {
  mockOllamaDiscoveredModels,
  mockOpenAiDiscoveredModels,
} from '@/features/model/mocks/modelFixtures'
import {
  STEP_REQUEST_KEY,
  type InstallationChatModel,
  type InstallationEmbeddingModel,
  type InstallationFirstAdmin,
  type InstallationModelDiscoveryRequest,
  type InstallationStepState,
} from '../api/types'
import {
  MOCK_EMBEDDING_DIMENSIONS,
  MOCK_INSTALLATION_TOKEN,
  MOCK_NO_MODEL_LIST_MARKER,
  MOCK_UNREACHABLE_MARKER,
  completeMockInstallation,
  isMockInstallationPending,
  mockInstallationState,
  recordMockDiscovery,
  updateMockStep,
} from './installationFixtures'

/**
 * The installation API as MSW handlers, after the fragment `installation.yaml`:
 *
 * - installed, every operation answers 404; pending, every one needs the token as Bearer (401),
 * - a save takes the part of the body named after the step, validates per field (400 with
 *   `fieldErrors`) and discards the step's check,
 * - a check answers 200 either way; for addresses containing {@link MOCK_UNREACHABLE_MARKER} it
 *   reports `checked: false` with a `checkMessage`,
 * - skipping discards a saved optional step and refuses a mandatory one (400),
 * - completing refuses (409) while a mandatory or a saved optional step is unchecked.
 */
const BASE = '/api/v1/installation'
const MIN_PASSWORD_LENGTH = 12

function error(status: number, message: string, fieldErrors?: unknown[]) {
  return HttpResponse.json(
    { error: message, status, timestamp: new Date().toISOString(), fieldErrors },
    { status },
  )
}

function refusal(request: Request): Response | null {
  if (!isMockInstallationPending()) return error(404, 'Nicht gefunden')
  if (request.headers.get('Authorization') !== `Bearer ${MOCK_INSTALLATION_TOKEN}`) {
    return error(401, 'Installations-Token fehlt oder ist ungültig')
  }
  return null
}

function findStep(step: string): InstallationStepState | undefined {
  return mockInstallationState().steps.find((s) => s.step === step)
}

function fieldError(field: string, code: string, message: string) {
  return { field, code, message }
}

function firstAdminErrors(values: InstallationFirstAdmin) {
  const errors = []
  if (values.email?.toLowerCase() === 'admin@opaa.local') {
    errors.push(fieldError('firstAdmin.email', 'RESERVED', 'Diese Adresse ist nicht zulässig.'))
  }
  if ((values.password ?? '').length < MIN_PASSWORD_LENGTH) {
    errors.push(
      fieldError(
        'firstAdmin.password',
        'TOO_SHORT',
        `Das Passwort braucht mindestens ${MIN_PASSWORD_LENGTH} Zeichen.`,
      ),
    )
  }
  return errors
}

/** The step's view after a save: secrets only as „gesetzt", never echoed. */
function storedView(step: InstallationStepState, part: unknown): Partial<InstallationStepState> {
  if (step.step === 'FIRST_ADMIN') {
    const { email, displayName } = part as InstallationFirstAdmin
    return { firstAdmin: { email, displayName, passwordSet: true } }
  }
  if (step.step === 'CHAT_MODEL') {
    const { apiKey, maxTokens, ...rest } = part as InstallationChatModel
    const keySet = apiKey === undefined ? Boolean(step.chatModel?.apiKeySet) : Boolean(apiKey)
    return { chatModel: { ...rest, maxTokens: maxTokens ?? 8000, apiKeySet: keySet } }
  }
  if (step.step === 'EMBEDDING_MODEL') {
    const { apiKey, maxInputTokens, ...rest } = part as InstallationEmbeddingModel
    const keySet = apiKey === undefined ? Boolean(step.embeddingModel?.apiKeySet) : Boolean(apiKey)
    return {
      embeddingModel: { ...rest, maxInputTokens: maxInputTokens ?? 2048, apiKeySet: keySet },
    }
  }
  return { signIn: part as InstallationStepState['signIn'] }
}

function checkMessageOf(step: InstallationStepState): string | null {
  const values = JSON.stringify(step.chatModel ?? step.embeddingModel ?? step.signIn ?? {})
  if (!values.includes(MOCK_UNREACHABLE_MARKER)) return null
  return step.step === 'SIGN_IN'
    ? 'Die Discovery-Adresse des Anbieters ist nicht erreichbar. Prüfen Sie die Issuer-URI.'
    : 'Das Modell antwortet nicht: Verbindung abgelehnt. Prüfen Sie Basis-Adresse und Modell-Kennung.'
}

/**
 * The model list of a step's endpoint, as the admin mock answers it: Ollama's list for an Ollama
 * address, OpenAI's otherwise. The stored key serves only the stored address (400 otherwise).
 */
function discoveryAnswer(step: InstallationStepState, body: InstallationModelDiscoveryRequest) {
  const address = body.baseUrl ?? ''
  const stored = step.chatModel ?? step.embeddingModel ?? null
  const usesStoredKey = body.apiKey === undefined || body.apiKey === null
  if (usesStoredKey && stored?.apiKeySet && stored.baseUrl !== address) {
    return error(
      400,
      'Die Basis-Adresse weicht von der gespeicherten ab. Der gespeicherte Zugangsschlüssel gilt nur für sie.',
    )
  }
  if (address.includes(MOCK_UNREACHABLE_MARKER)) {
    return HttpResponse.json(
      discoveryFailure(
        'UNREACHABLE',
        'Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).',
      ),
    )
  }
  if (address.includes(MOCK_NO_MODEL_LIST_MARKER)) {
    return HttpResponse.json(
      discoveryFailure(
        'NO_MODEL_LIST',
        'Der Endpunkt bietet keine Modellliste an. Tragen Sie die Modell-Kennung von Hand ein.',
      ),
    )
  }
  const hasKey = Boolean(body.apiKey?.trim()) || (usesStoredKey && Boolean(stored?.apiKeySet))
  if (address.includes('api.openai.com') && !hasKey) {
    return HttpResponse.json(
      discoveryFailure(
        'UNAUTHENTICATED',
        'Der Endpunkt verlangt einen Zugangsschlüssel (HTTP 401). Tragen Sie ihn ein und rufen Sie die Modelle erneut ab.',
      ),
    )
  }
  const ollama = address.includes(':11434') || address.includes('ollama')
  const models = ollama ? mockOllamaDiscoveredModels : mockOpenAiDiscoveredModels
  return HttpResponse.json({
    success: true,
    failure: null,
    message: `${models.length} Modelle gefunden.`,
    ollama,
    models,
  } satisfies LlmModelDiscoveryResponse)
}

export const installationHandlers = [
  http.get(BASE, ({ request }) => refusal(request) ?? HttpResponse.json(mockInstallationState())),

  http.put(`${BASE}/steps/:step`, async ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (!step) return error(400, 'Unbekannter Schritt')
    const body = (await request.json()) as Record<string, unknown>
    const part = body[STEP_REQUEST_KEY[step.step]]
    if (!part) return error(400, 'Die Angaben des Schritts fehlen.')
    const errors =
      step.step === 'FIRST_ADMIN' ? firstAdminErrors(part as InstallationFirstAdmin) : []
    if (errors.length > 0) return error(400, 'Die Angaben sind unvollständig.', errors)
    updateMockStep(step.step, {
      saved: true,
      checked: false,
      checkMessage: null,
      ...storedView(step, part),
    })
    return HttpResponse.json(findStep(step.step))
  }),

  http.post(`${BASE}/steps/:step/skip`, ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (!step) return error(400, 'Unbekannter Schritt')
    if (step.mandatory) return error(400, 'Ein Pflichtschritt lässt sich nicht überspringen.')
    updateMockStep(step.step, {
      saved: false,
      checked: false,
      checkMessage: null,
      chatModel: undefined,
      embeddingModel: undefined,
    })
    return HttpResponse.json(findStep(step.step))
  }),

  http.post(`${BASE}/steps/:step/check`, ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (!step) return error(400, 'Unbekannter Schritt')
    if (!step.saved) return error(409, 'Der Schritt ist noch nicht gespeichert.')
    const failure = checkMessageOf(step)
    updateMockStep(step.step, {
      checked: failure === null,
      checkMessage: failure ?? 'Prüfung erfolgreich.',
      ...(step.step === 'EMBEDDING_MODEL' && step.embeddingModel
        ? {
            embeddingModel: {
              ...step.embeddingModel,
              dimensions: failure === null ? MOCK_EMBEDDING_DIMENSIONS : null,
            },
          }
        : {}),
    })
    return HttpResponse.json(findStep(step.step))
  }),

  http.post(`${BASE}/steps/:step/discover`, async ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (step?.step !== 'CHAT_MODEL' && step?.step !== 'EMBEDDING_MODEL') {
      return error(400, 'Modelle lassen sich nur in den Modellschritten abrufen.')
    }
    const body = (await request.json()) as InstallationModelDiscoveryRequest
    recordMockDiscovery({ step: step.step, baseUrl: body.baseUrl, apiKey: body.apiKey })
    return discoveryAnswer(step, body)
  }),

  http.post(`${BASE}/complete`, ({ request }) => {
    const refused = refusal(request)
    if (refused) return refused
    const state = mockInstallationState()
    if (!state.completable) {
      return error(
        409,
        'Es sind noch nicht alle Pflichtschritte und ausgefüllten Schritte geprüft.',
      )
    }
    completeMockInstallation()
    return HttpResponse.json({ ...state, pending: false })
  }),
]
