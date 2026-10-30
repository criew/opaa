import { http, HttpResponse } from 'msw'
import type { AuditorRoleRequestResponse } from '@/shared/api/types'

const initialRequests: AuditorRoleRequestResponse[] = [
  {
    id: 'auditor-request-1',
    userId: 'local-user-klein',
    displayName: 'Lena Klein',
    email: 'lena.klein@stadt.example',
    localAccount: true,
    requestedByDisplayName: 'Systemverwaltung Muster',
    requestedByProvider: false,
    requestedAt: '2026-10-28T09:30:00Z',
    emailChangedByAdministrationAt: '2026-10-27T14:00:00Z',
  },
]

let requests = [...initialRequests]

/** Restores the open requests, for tests that confirm or decline one. */
export function resetAuditorRoleRequests(): void {
  requests = [...initialRequests]
}

export const auditorRoleRequestHandlers = [
  http.get('/api/v1/audit/auditor-role-requests', () => HttpResponse.json(requests)),
  http.post('/api/v1/audit/auditor-role-requests/:id/confirm', ({ params }) => {
    const confirmed = requests.find((request) => request.id === params.id)
    requests = requests.filter((request) => request.id !== params.id)
    return HttpResponse.json({
      setupUrl: confirmed?.localAccount ? '/set-password?token=mock-revision-token' : null,
    })
  }),
  http.post('/api/v1/audit/auditor-role-requests/:id/decline', ({ params }) => {
    requests = requests.filter((request) => request.id !== params.id)
    return new HttpResponse(null, { status: 204 })
  }),
]
