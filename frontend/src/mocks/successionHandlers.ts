import { http, HttpResponse } from 'msw'
import type { SuccessionKind, SuccessionReviewRequest } from '../types/api'
import { mockSuccessionEntries } from './successionFixtures'

/** Die Endpunkte der Betriebsliste (#1819) für die Oberfläche aus #1821. */
export const successionHandlers = [
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

  http.post('/api/v1/admin/succession/:caseId/reviews', async ({ params, request }) => {
    const body = (await request.json()) as SuccessionReviewRequest
    if (!body.reason?.trim()) {
      return HttpResponse.json({ error: 'Ein Grund ist erforderlich' }, { status: 400 })
    }
    return HttpResponse.json(
      {
        id: `review-${crypto.randomUUID().slice(0, 8)}`,
        caseId: String(params.caseId),
        reviewedAt: new Date().toISOString(),
        reason: body.reason,
      },
      { status: 201 },
    )
  }),
]
