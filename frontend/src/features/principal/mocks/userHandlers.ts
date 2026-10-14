import { http, HttpResponse } from 'msw'
import { mockUsers } from './userFixtures'

export const userHandlers = [
  http.get('/api/v1/admin/users', () => {
    return HttpResponse.json(mockUsers)
  }),

  // GET /v1/users, reachable for any authenticated user (unlike /v1/admin/users above),
  // powers the member/grant pickers - returns id/email/displayName, no systemRole.
  http.get('/api/v1/users', () => {
    return HttpResponse.json(
      mockUsers.map(({ id, email, displayName }) => ({ id, email, displayName })),
    )
  }),
]
