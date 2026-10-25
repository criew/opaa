import { http, HttpResponse } from 'msw'
import type { FullSyncRhythmRequest, FullSyncRhythmResponse } from '@/shared/api/types'
import { mockFullSyncRhythmDefaults } from './fullSyncRhythmFixtures'

/** The administration's own days per source type; absent while the factory default applies. */
const ownDays = new Map<string, number>()

function rhythmOf(entry: (typeof mockFullSyncRhythmDefaults)[number]): FullSyncRhythmResponse {
  const own = ownDays.get(entry.sourceType)
  return {
    ...entry,
    intervalDays: own ?? entry.defaultIntervalDays,
    overridden: own !== undefined,
  }
}

export const fullSyncRhythmHandlers = [
  http.get('/api/v1/admin/full-sync-rhythms', () =>
    HttpResponse.json(mockFullSyncRhythmDefaults.map(rhythmOf)),
  ),

  http.put('/api/v1/admin/full-sync-rhythms/:sourceType', async ({ params, request }) => {
    const entry = mockFullSyncRhythmDefaults.find((item) => item.sourceType === params.sourceType)
    if (!entry) {
      return HttpResponse.json(
        { error: 'Diese Quellart hat keinen Vollabgleich-Rhythmus', status: 404 },
        { status: 404 },
      )
    }
    const body = (await request.json()) as FullSyncRhythmRequest
    const days = body.intervalDays ?? null
    if (days !== null && (!Number.isInteger(days) || days < 1 || days > 365)) {
      return HttpResponse.json(
        { error: 'intervalDays: muss zwischen 1 und 365 liegen', status: 400 },
        { status: 400 },
      )
    }
    if (days === null) {
      ownDays.delete(entry.sourceType)
    } else {
      ownDays.set(entry.sourceType, days)
    }
    return HttpResponse.json(rhythmOf(entry))
  }),
]
