import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import { getHealth, sendQuery } from './api'

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
