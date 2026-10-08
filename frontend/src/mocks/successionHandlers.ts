import { http, HttpResponse } from 'msw'
import type { SuccessionKind } from '../types/api'
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
]
