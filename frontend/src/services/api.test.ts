import { describe, expect, it } from 'vitest'
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
})
