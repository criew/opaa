import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/server'
import { createSpace, updateSpaceDetails } from './spaceApi'

describe('spaceApi', () => {
  describe('updateSpaceDetails', () => {
    it('sends only name and description - no visibility, kind, ownerId or initialMembers', async () => {
      let capturedBody: unknown = null
      server.use(
        http.put('/api/v1/spaces/:spaceId', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-1',
            name: 'Renamed',
            isDefault: false,
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      await updateSpaceDetails('space-1', 'Renamed', 'A new description')

      expect(capturedBody).toStrictEqual({ name: 'Renamed', description: 'A new description' })
    })

    it('sends the instruction to the model only when one is given', async () => {
      let capturedBody: unknown = null
      server.use(
        http.put('/api/v1/spaces/:spaceId', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({ id: 'space-1' })
        }),
      )

      await updateSpaceDetails('space-1', 'Renamed', '', undefined, 'Fasse dich kurz.')

      expect(capturedBody).toStrictEqual({
        name: 'Renamed',
        description: '',
        modelInstruction: 'Fasse dich kurz.',
      })
    })
  })

  describe('createSpace', () => {
    it('sends persons and groups as initial members and no visibility', async () => {
      let capturedBody: Record<string, unknown> | null = null
      server.use(
        http.post('/api/v1/spaces', async ({ request }) => {
          capturedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: 'space-new',
            name: 'New Space',
            isDefault: false,
            ownerId: 'u1',
            memberCount: 3,
            roleCounts: { MEMBER: 1, CURATOR: 1, ADMIN: 1 },
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      const initialMembers = [
        { subjectType: 'USER' as const, subjectId: 'user-1', role: 'CURATOR' as const },
        { subjectType: 'GROUP' as const, subjectId: 'group-1', role: 'MEMBER' as const },
      ]
      await createSpace('New Space', 'A description', undefined, undefined, initialMembers)

      expect(capturedBody).toMatchObject({
        name: 'New Space',
        description: 'A description',
        initialMembers,
      })
      expect(capturedBody).not.toHaveProperty('visibility')
    })

    it('sends the chosen assets of every type when provided', async () => {
      let capturedBody: unknown = null
      server.use(
        http.post('/api/v1/spaces', async ({ request }) => {
          capturedBody = await request.json()
          return HttpResponse.json({
            id: 'space-new',
            name: 'New Space',
            isDefault: false,
            reach: { allAccounts: false, groupCount: 0, userCount: 1 },
            ownerId: 'u1',
            memberCount: 1,
            roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
            members: [],
            createdAt: '2026-03-01T10:00:00Z',
            updatedAt: '2026-03-01T10:00:00Z',
          })
        }),
      )

      const assets = [
        { assetType: 'KNOWLEDGE_LIBRARY' as const, assetId: 'lib-1' },
        { assetType: 'PROMPT_LIBRARY' as const, assetId: 'prompts-1' },
      ]
      await createSpace('New Space', 'A description', assets)

      expect(capturedBody).toMatchObject({ assets })
    })
  })
})
