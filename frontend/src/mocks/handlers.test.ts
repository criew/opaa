import { describe, expect, it } from 'vitest'
import { mockQueryResponses } from './fixtures'

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
    it('returns RUNNING status', async () => {
      const response = await fetch('/api/v1/indexing/trigger', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ libraryId: 'library-1' }),
      })
      const data = await response.json()

      expect(response.status).toBe(202)
      expect(data.status).toBe('RUNNING')
      expect(data.documentCount).toBe(0)
    })

    it('returns 400 without a libraryId', async () => {
      // #419 acceptance criteria: no libraryId -> 400.
      const response = await fetch('/api/v1/indexing/trigger', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      })
      const data = await response.json()

      expect(response.status).toBe(400)
      expect(data.error).toBe('libraryId ist erforderlich')
    })
  })

  describe('GET /api/v1/indexing/status', () => {
    it('returns IDLE when no indexing has been triggered', async () => {
      const response = await fetch('/api/v1/indexing/status')
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(data.status).toBe('IDLE')
    })

    it('progresses to COMPLETED after trigger and multiple polls', async () => {
      await fetch('/api/v1/indexing/trigger', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ libraryId: 'library-1' }),
      })

      let data
      for (let i = 0; i < 5; i++) {
        const response = await fetch('/api/v1/indexing/status')
        data = await response.json()
      }

      expect(data.status).toBe('COMPLETED')
      expect(data.documentCount).toBe(37)
      expect(data.documentsSkipped).toBe(5)
    })
  })

  describe('GET /api/v1/libraries', () => {
    it('returns the list of libraries', async () => {
      const response = await fetch('/api/v1/libraries')
      const data = await response.json()

      expect(response.status).toBe(200)
      expect(Array.isArray(data)).toBe(true)
      expect(data.length).toBeGreaterThan(0)
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

  // Upload/format/size/dedup handling of the POST handler is exercised end-to-end through
  // DocumentsPage.test.tsx and documentStore.test.ts instead of a raw multipart request here:
  // driving request.formData() through a jsdom-environment fetch()/axios body never resolves in
  // this handler (a known jsdom/undici stream interaction, not specific to this handler), so a
  // multipart POST cannot be exercised directly against MSW from this test file.
  describe('/api/v1/libraries/:libraryId/documents', () => {
    const libraryId = 'library-dienstanweisungen'

    it('lists the documents of a library', async () => {
      const response = await fetch(`/api/v1/libraries/${libraryId}/documents`)
      expect(response.status).toBe(200)
      expect(await response.json()).toEqual([])
    })

    it('returns 404 for an unknown library', async () => {
      const response = await fetch('/api/v1/libraries/does-not-exist/documents')
      expect(response.status).toBe(404)
    })

    it('returns 404 when deleting a document from an unknown library', async () => {
      const response = await fetch('/api/v1/libraries/does-not-exist/documents/some-document', {
        method: 'DELETE',
      })
      expect(response.status).toBe(404)
    })

    it('returns 404 when deleting an unknown document', async () => {
      const response = await fetch(`/api/v1/libraries/${libraryId}/documents/does-not-exist`, {
        method: 'DELETE',
      })
      expect(response.status).toBe(404)
    })
  })
})
