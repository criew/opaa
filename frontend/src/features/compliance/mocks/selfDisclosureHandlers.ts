import { http, HttpResponse } from 'msw'
import type {
  SelfDisclosureSettings,
  SelfDisclosureSettingsUpdate,
  SelfDisclosureStatus,
} from '@/shared/api/types'

/** A person who has not exported today and may start one. */
export function availableSelfDisclosureStatus(): SelfDisclosureStatus {
  return {
    enabled: true,
    dailyLimit: 1,
    completedToday: 0,
    startsToday: 0,
    remainingExports: 1,
    remainingStarts: 5,
    running: false,
    blocked: null,
    nextPossibleAt: null,
    lastCompletedAt: null,
    lastResult: null,
    retryPossible: false,
  }
}

function initialSettings(): SelfDisclosureSettings {
  return { enabled: true, dailyLimit: 1, updatedAt: '2026-10-23T08:00:00Z' }
}

let status = availableSelfDisclosureStatus()
let settings = initialSettings()
/** Whether the mock treats the sign-in as recent; the browser worker has no `auth_time`. */
let recentSignIn = true

export function resetSelfDisclosureMockState() {
  status = availableSelfDisclosureStatus()
  settings = initialSettings()
  recentSignIn = true
}

export function setMockSelfDisclosureStatus(next: SelfDisclosureStatus) {
  status = next
}

export function setMockRecentSignIn(recent: boolean) {
  recentSignIn = recent
}

function error(status: number, message: string, code?: string) {
  return HttpResponse.json(
    { error: message, status, timestamp: new Date().toISOString(), ...(code ? { code } : {}) },
    { status },
  )
}

export const selfDisclosureHandlers = [
  http.get('/api/v1/me/self-disclosure', () => HttpResponse.json(status)),

  http.post('/api/v1/me/self-disclosure/tickets', () => {
    if (!status.enabled) {
      return error(403, 'Die Selbstauskunft ist abgeschaltet.', 'SELF_DISCLOSURE_DISABLED')
    }
    if (!recentSignIn) {
      return error(
        403,
        'Für diesen Vorgang ist eine erneute Anmeldung erforderlich.',
        'RECENT_LOGIN_REQUIRED',
      )
    }
    if (status.blocked === 'DAILY_LIMIT') {
      return error(429, 'Tagesgrenze erreicht.', 'SELF_DISCLOSURE_DAILY_LIMIT')
    }
    return HttpResponse.json({ ticket: 'mock-ticket', expiresInSeconds: 60 }, { status: 201 })
  }),

  http.get('/api/v1/admin/self-disclosure/settings', () => HttpResponse.json(settings)),

  http.put('/api/v1/admin/self-disclosure/settings', async ({ request }) => {
    const update = (await request.json()) as SelfDisclosureSettingsUpdate
    const limit = update.dailyLimit
    if (limit != null && (limit < 1 || limit > 10)) {
      return error(400, 'Die Tagesgrenze liegt zwischen 1 und 10.')
    }
    settings = {
      enabled: update.enabled ?? settings.enabled,
      dailyLimit: limit ?? settings.dailyLimit,
      updatedAt: new Date().toISOString(),
    }
    status = {
      ...status,
      enabled: settings.enabled,
      dailyLimit: settings.dailyLimit,
      blocked: settings.enabled ? null : 'DISABLED',
    }
    return HttpResponse.json(settings)
  }),
]
