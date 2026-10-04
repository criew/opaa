import { http, HttpResponse } from 'msw'
import type {
  ConnectionLogEntryResponse,
  ConnectionLogRetentionRequest,
  ConnectionLogRetentionResponse,
} from '../types/api'

const ORGANIZATION = '00000000-0000-4000-8000-000000000001'

const entries: ConnectionLogEntryResponse[] = [
  {
    eventId: 'connection-log-1',
    recordedAt: '2026-09-20T08:00:00Z',
    organizationId: ORGANIZATION,
    eventType: 'CONNECTED',
    actorRef: 'p-7f3a91',
    ownerKind: 'PERSON',
    personRef: 'p-7f3a91',
    libraryId: null,
    accountLabel: null,
    profileId: 'connection-profile-nextcloud-person',
    profileName: 'Zugang Nextcloud intern',
    cause: null,
  },
  {
    eventId: 'connection-log-2',
    recordedAt: '2026-09-25T03:00:00Z',
    organizationId: ORGANIZATION,
    eventType: 'EXPIRED',
    actorRef: 'SYSTEM',
    ownerKind: 'PERSON',
    personRef: 'p-2c18d0',
    libraryId: null,
    accountLabel: null,
    profileId: 'connection-profile-nextcloud-partner',
    profileName: 'Zugang Nextcloud Partner',
    cause: 'PROVIDER_REJECTED',
  },
]

function initialRetention(): ConnectionLogRetentionResponse {
  return { retentionMonths: 12, lastCutoff: null, updatedAt: '2026-10-01T00:00:00Z' }
}

let retention = initialRetention()

export function resetConnectionLogMockState() {
  retention = initialRetention()
}

function error(status: number, message: string) {
  return HttpResponse.json(
    { error: message, status, timestamp: new Date().toISOString() },
    { status },
  )
}

export const connectionLogHandlers = [
  http.get('/api/v1/audit/connection-log', ({ request }) => {
    const url = new URL(request.url)
    if (!url.searchParams.get('reason')?.trim()) {
      return error(400, 'Ein Anlass ist Pflicht')
    }
    const eventType = url.searchParams.get('eventType')
    const profileId = url.searchParams.get('profileId')
    const matching = entries.filter(
      (entry) =>
        (!eventType || entry.eventType === eventType) &&
        (!profileId || entry.profileId === profileId),
    )
    return HttpResponse.json({
      entries: matching,
      page: Number(url.searchParams.get('page') ?? 0),
      size: 50,
      hasMore: false,
    })
  }),

  http.get('/api/v1/admin/connection-log/retention', () => HttpResponse.json(retention)),

  http.put('/api/v1/admin/connection-log/retention', async ({ request }) => {
    const body = (await request.json()) as ConnectionLogRetentionRequest
    if (
      !Number.isInteger(body.retentionMonths) ||
      body.retentionMonths < 6 ||
      body.retentionMonths > 24
    ) {
      return error(400, 'retentionMonths muss zwischen 6 und 24 liegen')
    }
    retention = {
      ...retention,
      retentionMonths: body.retentionMonths,
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json(retention)
  }),
]
