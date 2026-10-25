import { http, HttpResponse } from 'msw'
import type {
  ChatAutoCleanupRetention,
  ChatAutoCleanupRetentionUpdate,
  IncidentScopeRetention,
  IncidentScopeRetentionUpdate,
  LockedAccountChatRetention,
  LockedAccountChatRetentionUpdate,
} from '@/shared/api/types'

const BASE = '/api/v1/admin/retention'

function chatDefaults(): ChatAutoCleanupRetention {
  return {
    archiveAfterDays: 90,
    deleteAfterDays: 365,
    defaultArchiveAfterDays: 90,
    defaultDeleteAfterDays: 365,
    overridden: false,
  }
}

function lockedDefaults(): LockedAccountChatRetention {
  return {
    enabled: true,
    deleteAfterDays: 365,
    defaultEnabled: true,
    defaultDeleteAfterDays: 365,
    overridden: false,
  }
}

function incidentDefaults(): IncidentScopeRetention {
  return { retentionYears: 1, defaultRetentionYears: 1, overridden: false }
}

let chat = chatDefaults()
let locked = lockedDefaults()
let incident = incidentDefaults()

export function resetRetentionMockState() {
  chat = chatDefaults()
  locked = lockedDefaults()
  incident = incidentDefaults()
}

function invalid(message: string) {
  return HttpResponse.json({ status: 400, error: 'Bad Request', message }, { status: 400 })
}

function within(value: number | null | undefined, min: number, max: number): boolean {
  return (
    value !== null && value !== undefined && Number.isInteger(value) && value >= min && value <= max
  )
}

export const retentionHandlers = [
  http.get(`${BASE}/chat-auto-cleanup`, () => HttpResponse.json(chat)),
  http.put(`${BASE}/chat-auto-cleanup`, async ({ request }) => {
    const body = (await request.json()) as ChatAutoCleanupRetentionUpdate
    if (body.archiveAfterDays == null && body.deleteAfterDays == null) {
      chat = chatDefaults()
      return HttpResponse.json(chat)
    }
    if (!within(body.archiveAfterDays, 90, 3650) || !within(body.deleteAfterDays, 30, 3650)) {
      return invalid('Die Fristen liegen außerhalb ihrer Grenzen')
    }
    chat = {
      ...chat,
      archiveAfterDays: body.archiveAfterDays as number,
      deleteAfterDays: body.deleteAfterDays as number,
      overridden: true,
    }
    return HttpResponse.json(chat)
  }),
  http.get(`${BASE}/locked-account-chats`, () => HttpResponse.json(locked)),
  http.put(`${BASE}/locked-account-chats`, async ({ request }) => {
    const body = (await request.json()) as LockedAccountChatRetentionUpdate
    if (body.enabled == null && body.deleteAfterDays == null) {
      locked = lockedDefaults()
      return HttpResponse.json(locked)
    }
    if (body.enabled == null || !within(body.deleteAfterDays, 90, 3650)) {
      return invalid('Die Frist nach einer Sperre muss zwischen 90 und 3650 Tagen liegen')
    }
    locked = {
      ...locked,
      enabled: body.enabled,
      deleteAfterDays: body.deleteAfterDays as number,
      overridden: true,
    }
    return HttpResponse.json(locked)
  }),
  http.get(`${BASE}/incident-scopes`, () => HttpResponse.json(incident)),
  http.put(`${BASE}/incident-scopes`, async ({ request }) => {
    const body = (await request.json()) as IncidentScopeRetentionUpdate
    if (body.retentionYears == null) {
      incident = incidentDefaults()
      return HttpResponse.json(incident)
    }
    if (!within(body.retentionYears, 1, 10)) {
      return invalid('Die Löschfrist muss zwischen 1 und 10 Jahren liegen')
    }
    incident = { ...incident, retentionYears: body.retentionYears, overridden: true }
    return HttpResponse.json(incident)
  }),
]
