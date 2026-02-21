import { describe, expect, it } from 'vitest'
import { mockIndexingStatus, mockQueryResponses } from './fixtures'

describe('MSW Handlers', () => {
  describe('GET /api/health', () => {
    it('returns health status', async () => {
      const response = await fetch('/api/health')
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.status).toBe('UP')
    })
  })

  describe('POST /api/v1/indexing/trigger', () => {
    it('returns indexing status', async () => {
      const response = await fetch('/api/v1/indexing/trigger', { method: 'POST' })
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.status).toBe(mockIndexingStatus.status)
      expect(data.documentCount).toBe(mockIndexingStatus.documentCount)
    })
  })

  describe('GET /api/v1/indexing/status', () => {
    it('returns indexing status', async () => {
      const response = await fetch('/api/v1/indexing/status')
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.status).toBe('COMPLETED')
    })
  })

  describe('POST /api/v1/query', () => {
    it('returns a random query response for valid question', async () => {
      const response = await fetch('/api/v1/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: 'What is the architecture?' }),
      })
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.answer).toBeTruthy()
      expect(data.sources.length).toBeGreaterThanOrEqual(1)
      expect(data.metadata.model).toBe('gpt-4o')

      const allAnswers = mockQueryResponses.map((r) => r.answer)
      expect(allAnswers).toContain(data.answer)
    })

    it('returns 400 for blank question', async () => {
      const response = await fetch('/api/v1/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: '' }),
      })

      expect(response.status).toBe(400)
      const data = await response.json()
      expect(data.error).toBeDefined()
      expect(data.status).toBe(400)
    })
  })
})
