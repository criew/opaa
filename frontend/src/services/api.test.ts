import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import { getHealth, sendQuery, updateSpaceDetails } from './api'

describe('api service', () => {
  describe('getHealth', () => {
    it('returns health status', async () => {
      const result = await getHealth()
      expect(result.status).toBe('UP')
    })
  })

  describe('sendQuery', () => {
    it('returns answer with sources', async () => {
      const result = await sendQuery('What is the architecture?')
      expect(result.answer).toBeDefined()
      expect(result.sources.length).toBeGreaterThanOrEqual(1)
      expect(result.metadata.model).toBe('gpt-4o')
    })
  })

  describe('updateSpaceDetails', () => {
    it('sends only name, description and visibility - no kind, ownerId or initialMembers', async () => {
      let capturedBody: unknown = null
      server.use(
        http.put('/api/v1/spaces/:spaceId', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-1',
            name: 'Renamed',
            kind: 'PROJECT',
            visibility: 'PRIVATE',
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await updateSpaceDetails('space-1', 'Renamed', 'A new description')

      expect(capturedBody).toEqual({
        name: 'Renamed',
        description: 'A new description',
        visibility: undefined,
      })
    })
  })

  describe('normalizeError', () => {
    it('throws error message from valid ErrorResponse JSON', async () => {
      server.use(
        http.get('/api/health', () => {
          return HttpResponse.json(
            { error: 'Service unavailable', status: 503, timestamp: new Date().toISOString() },
            { status: 503 },
          )
        }),
      )

      await expect(getHealth()).rejects.toThrow('Service unavailable')
    })

    it('falls back to HTTP status when response is not JSON ErrorResponse', async () => {
      server.use(
        http.get('/api/health', () => {
          return new HttpResponse('<html>Bad Gateway</html>', {
            status: 502,
            headers: { 'Content-Type': 'text/html' },
          })
        }),
      )

      await expect(getHealth()).rejects.toThrow(/HTTP 502/)
    })

    it('falls back to error message on network error', async () => {
      server.use(
        http.get('/api/health', () => {
          return HttpResponse.error()
        }),
      )

      await expect(getHealth()).rejects.toThrow()
    })
  })
})
