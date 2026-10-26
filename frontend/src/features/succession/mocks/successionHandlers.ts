import { http, HttpResponse } from 'msw'
import type {
  SuccessionKind,
  SuccessionSettingsRequest,
  SuccessionSettingsResponse,
} from '@/shared/api/types'
import { mockSuccessionEntries } from './successionFixtures'

/** The months the administration set; `null` while the factory default applies. */
let ownMonths: number | null = null

export function resetMockSuccessionSettings() {
  ownMonths = null
}

function successionSettings(): SuccessionSettingsResponse {
  return {
    agingThresholdMonths: ownMonths ?? 12,
    defaultAgingThresholdMonths: 12,
    minAgingThresholdMonths: 1,
    maxAgingThresholdMonths: 120,
    overridden: ownMonths !== null,
  }
}

/** The endpoints of the operations list and of its aging threshold. */
export const successionHandlers = [
  http.get('/api/v1/admin/succession/settings', () => HttpResponse.json(successionSettings())),
  http.put('/api/v1/admin/succession/settings', async ({ request }) => {
    const months =
      ((await request.json()) as SuccessionSettingsRequest).agingThresholdMonths ?? null
    if (months !== null && (!Number.isInteger(months) || months < 1 || months > 120)) {
      return HttpResponse.json(
        {
          error: 'Die Schwelle muss zwischen 1 und 120 Monaten liegen',
          fieldErrors: [
            {
              field: 'agingThresholdMonths',
              code: 'OUT_OF_RANGE',
              message: 'Die Schwelle muss zwischen 1 und 120 Monaten liegen',
            },
          ],
        },
        { status: 400 },
      )
    }
    ownMonths = months
    return HttpResponse.json(successionSettings())
  }),
  http.get('/api/v1/admin/succession/counts', () =>
    HttpResponse.json({
      openSuccession: mockSuccessionEntries.OPEN_SUCCESSION.length,
      grantsWithoutRecipient: mockSuccessionEntries.GRANTS_WITHOUT_RECIPIENT.length,
      groupWithoutEffect: mockSuccessionEntries.GROUP_WITHOUT_EFFECT.length,
    }),
  ),
  http.get('/api/v1/admin/succession', ({ request }) => {
    const params = new URL(request.url).searchParams
    const kind = (params.get('kind') ?? 'OPEN_SUCCESSION') as SuccessionKind
    const entries = mockSuccessionEntries[kind] ?? []
    const size = Number(params.get('size') ?? 50)
    return HttpResponse.json({
      entries,
      page: Number(params.get('page') ?? 0),
      size,
      totalElements: entries.length,
      totalPages: entries.length === 0 ? 0 : Math.ceil(entries.length / size),
    })
  }),
]
