import { http, HttpResponse } from 'msw'
import type { AccessAsOfObjectType, AccessAsOfPage } from '@/shared/api/types'

function error(status: number, message: string) {
  return HttpResponse.json(
    { error: message, status, timestamp: new Date().toISOString() },
    { status },
  )
}

/** One answer for any named object, so the mock mode shows a filled point-in-time report. */
export const rightsHistoryHandlers = [
  http.get('/api/v1/audit/access-as-of', ({ request }) => {
    const url = new URL(request.url)
    if (!url.searchParams.get('reason')?.trim()) return error(400, 'Ein Anlass ist Pflicht')
    const page: AccessAsOfPage = {
      objectType: (url.searchParams.get('objectType') ??
        'KNOWLEDGE_LIBRARY') as AccessAsOfObjectType,
      objectId: url.searchParams.get('objectId') ?? '',
      objectName: 'Personalvorgänge',
      from: url.searchParams.get('from') ?? '',
      to: url.searchParams.get('to') ?? '',
      retentionCutoff: '2023-04-01T00:00:00Z',
      beyondRetention: false,
      sourcesNotCovered: ['ACCOUNT_STATE'],
      entries: [
        {
          basis: 'GROUP_GRANT',
          userId: 'user-sommer',
          userName: 'Frau Sommer',
          groupId: 'group-referat-50',
          groupName: 'Referat 50',
          assetRole: 'VIEWER',
          validFrom: '2026-03-02T08:00:00Z',
          validTo: null,
        },
        {
          basis: 'DIRECT_GRANT',
          userId: 'user-weber',
          userName: 'Herr Weber',
          groupId: null,
          groupName: null,
          assetRole: 'EDITOR',
          validFrom: '2026-03-05T09:30:00Z',
          validTo: '2026-03-20T17:00:00Z',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 2,
      totalPages: 1,
    }
    return HttpResponse.json(page)
  }),
]
