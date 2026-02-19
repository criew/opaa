import { describe, expect, it } from 'vitest'
import { mockIndexingStatus, mockQueryResponse } from './fixtures'

describe('MSW Handlers', () => {
  describe('POST /api/v1/indexing/trigger', () => {
    it('returns indexing status', async () => {
      const response = await fetch('/api/v1/indexing/trigger', { method: 'POST' })
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.status).toBe(mockIndexingStatus.status)
      expect(data.documentCount).toBe(mockIndexingStatus.documentCount)
      expect(data.chunkCount).toBe(mockIndexingStatus.chunkCount)
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
    it('returns query response for valid question', async () => {
      const response = await fetch('/api/v1/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: 'What is the architecture?' }),
      })
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.answer).toBe(mockQueryResponse.answer)
      expect(data.sources).toHaveLength(3)
      expect(data.sources[0].fileName).toBe('architecture-overview.md')
      expect(data.metadata.model).toBe('gpt-4o')
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
