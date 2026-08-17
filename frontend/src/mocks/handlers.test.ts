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
    // VIEWER on this fixture (fixtures.ts) - used for the role-check tests below.
    const viewerLibraryId = 'library-dienstanweisungen'
    // MANAGER on this fixture, i.e. permitted to upload/delete - used where a 404 (unknown
    // document, unknown library) rather than a 403 is under test.
    const editableLibraryId = 'library-referat-50'

    it('lists the documents of a library', async () => {
      const response = await fetch(`/api/v1/libraries/${viewerLibraryId}/documents`)
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

    it('returns 404 when deleting an unknown document from an editable library', async () => {
      const response = await fetch(
        `/api/v1/libraries/${editableLibraryId}/documents/does-not-exist`,
        { method: 'DELETE' },
      )
      expect(response.status).toBe(404)
    })

    it('returns 403 when deleting from a library where the caller only has VIEWER', async () => {
      const response = await fetch(
        `/api/v1/libraries/${viewerLibraryId}/documents/does-not-exist`,
        { method: 'DELETE' },
      )
      expect(response.status).toBe(403)
      const data = await response.json()
      expect(data.error).toMatch(/kein zugriff/i)
    })

    // Does not call request.formData() before the role check runs, so - unlike a successful
    // upload - this does not hit the jsdom/undici hang described in the block comment above and
    // can be exercised directly against the handler.
    it('returns 403 when uploading into a library where the caller only has VIEWER', async () => {
      const formData = new FormData()
      formData.append('file', new File(['Inhalt'], 'sollte-abgelehnt-werden.md'))

      const response = await fetch(`/api/v1/libraries/${viewerLibraryId}/documents`, {
        method: 'POST',
        body: formData,
      })
      expect(response.status).toBe(403)
      const data = await response.json()
      expect(data.error).toMatch(/kein zugriff/i)
    })
  })

  describe('/api/v1/libraries/:libraryId/grants', () => {
    // MANAGER on this fixture (fixtures.ts) - the minimum role the grants endpoints require.
    const managerLibraryId = 'library-referat-50'
    // VIEWER on this fixture - below the MANAGER threshold the grants endpoints require.
    const viewerLibraryId = 'library-dienstanweisungen'
    // OWNER but personal=true - AssetGrantService#upsertGrant rejects new/updated grants on the
    // personal library unconditionally.
    const personalLibraryId = 'library-personal'

    it('lists the grants of a library the caller manages', async () => {
      const response = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`)
      expect(response.status).toBe(200)
      const data = await response.json()
      expect(Array.isArray(data)).toBe(true)
      expect(data.length).toBeGreaterThan(0)
    })

    it('returns 403 when listing grants with only VIEWER on the library', async () => {
      const response = await fetch(`/api/v1/libraries/${viewerLibraryId}/grants`)
      expect(response.status).toBe(403)
      const data = await response.json()
      expect(data.error).toMatch(/kein zugriff/i)
    })

    it('returns 404 for an unknown library', async () => {
      const response = await fetch('/api/v1/libraries/does-not-exist/grants')
      expect(response.status).toBe(404)
    })

    it('creates a grant and is idempotent per subject, replacing role and expiry', async () => {
      const requestBody = {
        subjectType: 'USER',
        subjectId: 'owner-2',
        role: 'VIEWER',
        expiresAt: null,
      }
      const createResponse = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
      })
      expect(createResponse.status).toBe(200)
      const created = await createResponse.json()
      expect(created.role).toBe('VIEWER')

      const updateResponse = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...requestBody, role: 'EDITOR' }),
      })
      expect(updateResponse.status).toBe(200)
      const updated = await updateResponse.json()
      expect(updated.id).toBe(created.id)
      expect(updated.role).toBe('EDITOR')

      const listResponse = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`)
      const list = (await listResponse.json()) as { id: string; subjectId: string }[]
      expect(list.filter((grant) => grant.subjectId === 'owner-2')).toHaveLength(1)
    })

    it('rejects a grant on the personal library', async () => {
      const response = await fetch(`/api/v1/libraries/${personalLibraryId}/grants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subjectType: 'USER', subjectId: 'owner-1', role: 'VIEWER' }),
      })
      expect(response.status).toBe(400)
      const data = await response.json()
      expect(data.error).toMatch(/persoenliche bibliothek/i)
    })

    it('rejects granting a role higher than the caller holds', async () => {
      // The caller only has MANAGER on this fixture - requesting OWNER must be capped, mirroring
      // AssetGrantService's escalation guard.
      const response = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subjectType: 'USER', subjectId: 'owner-1', role: 'OWNER' }),
      })
      expect(response.status).toBe(403)
    })

    it('revokes a grant', async () => {
      const listResponse = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`)
      const [firstGrant] = (await listResponse.json()) as { id: string }[]

      const deleteResponse = await fetch(
        `/api/v1/libraries/${managerLibraryId}/grants/${firstGrant.id}`,
        { method: 'DELETE' },
      )
      expect(deleteResponse.status).toBe(204)

      const afterResponse = await fetch(`/api/v1/libraries/${managerLibraryId}/grants`)
      const after = (await afterResponse.json()) as { id: string }[]
      expect(after.some((grant) => grant.id === firstGrant.id)).toBe(false)
    })

    it('returns 404 when revoking an unknown grant', async () => {
      const response = await fetch(`/api/v1/libraries/${managerLibraryId}/grants/does-not-exist`, {
        method: 'DELETE',
      })
      expect(response.status).toBe(404)
    })
  })
})
