import { http, HttpResponse } from 'msw'
import type { InstallationStep, StepInput } from '../api/types'
import {
  MOCK_INSTALLATION_TOKEN,
  MOCK_UNREACHABLE_MARKER,
  completeMockInstallation,
  isMockInstallationPending,
  mockInstallationState,
  updateMockStep,
} from './installationFixtures'

/**
 * The installation API as MSW handlers, after the sketch in
 * docs/features/installation-and-settings.md:
 *
 * - installed, every operation answers 404; pending, every one needs the token as Bearer (401),
 * - a save validates per field (400 with `fieldErrors`) and resets the step's check,
 * - a check fails (422) for addresses containing {@link MOCK_UNREACHABLE_MARKER},
 * - completing refuses (409) while a required step is unchecked.
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

function findStep(step: string): InstallationStep | undefined {
  return mockInstallationState().steps.find((s) => s.step === step)
}

function fieldError(field: string, code: string, message: string) {
  return { field, code, message }
}

function validate(step: string, body: Record<string, unknown>) {
  if (step === 'FIRST_ADMIN') {
    const values = body as unknown as StepInput['FIRST_ADMIN']
    const errors = []
    if (values.email?.toLowerCase() === 'admin@opaa.local') {
      errors.push(fieldError('email', 'RESERVED', 'Diese Adresse ist nicht zulässig.'))
    }
    const stored = findStep(step)?.values as { passwordSet?: boolean } | null
    if (values.password === undefined && !stored?.passwordSet) {
      errors.push(fieldError('password', 'REQUIRED', 'Bitte ein Passwort angeben.'))
    }
    if (values.password !== undefined && values.password.length < MIN_PASSWORD_LENGTH) {
      errors.push(
        fieldError(
          'password',
          'TOO_SHORT',
          `Das Passwort braucht mindestens ${MIN_PASSWORD_LENGTH} Zeichen.`,
        ),
      )
    }
    return errors
  }
  return []
}

/** What a stored step reads back: secrets only as „gesetzt". */
function storedValues(step: string, body: Record<string, unknown>) {
  const previous = (findStep(step)?.values ?? {}) as Record<string, unknown>
  if (step === 'FIRST_ADMIN') {
    const { password, ...rest } = body
    return { ...rest, passwordSet: password !== undefined || Boolean(previous.passwordSet) }
  }
  if (step === 'CHAT_MODEL') {
    const { apiKey, ...rest } = body
    return { ...rest, apiKeySet: apiKey ? true : Boolean(previous.apiKeySet) }
  }
  return body
}

function checkFailure(step: InstallationStep): string | null {
  const values = JSON.stringify(step.values ?? {})
  if (!values.includes(MOCK_UNREACHABLE_MARKER)) return null
  if (step.step === 'CHAT_MODEL') {
    return 'Das Modell antwortet nicht: Verbindung abgelehnt. Prüfen Sie Basis-Adresse und Modell-Kennung.'
  }
  if (step.step === 'SIGN_IN') {
    return 'Die Discovery-Adresse des Anbieters ist nicht erreichbar. Prüfen Sie die Issuer-URI.'
  }
  return 'Die Prüfung ist fehlgeschlagen.'
}

export const installationHandlers = [
  http.get(BASE, ({ request }) => refusal(request) ?? HttpResponse.json(mockInstallationState())),

  http.put(`${BASE}/steps/:step`, async ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (!step) return error(404, 'Unbekannter Schritt')
    const body = (await request.json()) as Record<string, unknown>
    const errors = validate(step.step, body)
    if (errors.length > 0) return error(400, 'Die Angaben sind unvollständig.', errors)
    updateMockStep(step.step, {
      saved: true,
      checked: false,
      values: storedValues(step.step, body),
    })
    return HttpResponse.json(findStep(step.step))
  }),

  http.post(`${BASE}/steps/:step/check`, ({ request, params }) => {
    const refused = refusal(request)
    if (refused) return refused
    const step = findStep(String(params.step))
    if (!step) return error(404, 'Unbekannter Schritt')
    if (!step.saved) return error(409, 'Der Schritt ist noch nicht gespeichert.')
    const failure = checkFailure(step)
    if (failure) return error(422, failure)
    updateMockStep(step.step, { checked: true })
    return HttpResponse.json(findStep(step.step))
  }),

  http.post(`${BASE}/complete`, ({ request }) => {
    const refused = refusal(request)
    if (refused) return refused
    const open = mockInstallationState().steps.filter((s) => s.required && !s.checked)
    if (open.length > 0) return error(409, 'Es sind noch nicht alle Pflichtschritte geprüft.')
    completeMockInstallation()
    return new HttpResponse(null, { status: 204 })
  }),
]
