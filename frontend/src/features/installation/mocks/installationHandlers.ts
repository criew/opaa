import { http, HttpResponse } from 'msw'
import {
  STEP_REQUEST_KEY,
  type InstallationChatModel,
  type InstallationEmbeddingModel,
  type InstallationFirstAdmin,
  type InstallationStepState,
} from '../api/types'
import {
  MOCK_EMBEDDING_DIMENSIONS,
  MOCK_INSTALLATION_TOKEN,
  MOCK_UNREACHABLE_MARKER,
  completeMockInstallation,
  isMockInstallationPending,
  mockInstallationState,
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
 * - completing refuses (409) while a mandatory step is unchecked.
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

  http.post(`${BASE}/complete`, ({ request }) => {
    const refused = refusal(request)
    if (refused) return refused
    const state = mockInstallationState()
    if (!state.completable) return error(409, 'Es sind noch nicht alle Pflichtschritte geprüft.')
    completeMockInstallation()
    return HttpResponse.json({ ...state, pending: false })
  }),
]
