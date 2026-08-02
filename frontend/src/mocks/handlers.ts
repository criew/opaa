import { http, HttpResponse } from 'msw'
import {
  mockHealthResponse,
  mockIndexingIdle,
  mockIndexingCompleted,
  getRandomMockResponse,
  mockErrorResponse,
  mockAuthConfig,
  mockLoginResponse,
  mockUser,
  mockUsers,
  mockSpaces,
  mockSpaceDetails,
} from './fixtures'
import type { IndexingStatusResponse, QueryRequest } from '../types/api'
import type { LoginRequest } from '../types/auth'

let indexingPollCount = 0
let indexingActive = false

export function resetIndexingState() {
  indexingPollCount = 0
  indexingActive = false
}

const INDEXING_POLL_STEPS = 5
const TOTAL_DOCUMENTS = 42

function recalculateRoleCounts(spaceId: string) {
  const space = mockSpaceDetails[spaceId]
  if (!space) return
  const base = { MEMBER: 0, CURATOR: 0, ADMIN: 0 }
  for (const member of space.members) {
    base[member.role] += 1
  }
  space.roleCounts = base
  space.memberCount = space.members.length
}

function getRunningStatus(step: number): IndexingStatusResponse {
  const progress = Math.min(step / INDEXING_POLL_STEPS, 1)
  return {
    status: 'RUNNING',
    documentCount: Math.round(TOTAL_DOCUMENTS * progress),
    totalDocuments: TOTAL_DOCUMENTS,
    documentsSkipped: 0,
    message: `Indexing in progress... ${Math.round(TOTAL_DOCUMENTS * progress)} documents processed`,
    timestamp: new Date().toISOString(),
  }
}

export const handlers = [
  http.get('/api/health', () => {
    return HttpResponse.json(mockHealthResponse)
  }),

  http.post('/api/v1/indexing/trigger', async ({ request }) => {
    // Accept optional IndexingTriggerRequest body (ignored in mock)
    const contentType = request.headers.get('content-type')
    if (contentType?.includes('application/json')) {
      await request.json().catch(() => null)
    }

    indexingPollCount = 0
    indexingActive = true
    return HttpResponse.json(
      {
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        message: 'Indizierung gestartet',
        timestamp: new Date().toISOString(),
      } satisfies IndexingStatusResponse,
      { status: 202 },
    )
  }),

  http.get('/api/v1/indexing/status', () => {
    if (!indexingActive) {
      return HttpResponse.json(mockIndexingIdle)
    }

    indexingPollCount++

    if (indexingPollCount >= INDEXING_POLL_STEPS) {
      indexingActive = false
      return HttpResponse.json(mockIndexingCompleted)
    }

    return HttpResponse.json(getRunningStatus(indexingPollCount))
  }),

  http.post('/api/v1/query', async ({ request }) => {
    const body = (await request.json()) as QueryRequest
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json(
        { ...mockErrorResponse, timestamp: new Date().toISOString() },
        { status: 400 },
      )
    }
    const mockResponse = getRandomMockResponse()
    const requestedSpaceIds = body.spaceIds ?? []
    const filteredSources =
      requestedSpaceIds.length > 0
        ? mockResponse.sources.filter((source) =>
            requestedSpaceIds.some((spaceId) => {
              const space = mockSpaces.find((item) => item.id === spaceId)
              return space?.name === source.spaceName
            }),
          )
        : mockResponse.sources
    return HttpResponse.json({
      ...mockResponse,
      sources: filteredSources,
      conversationId: body.conversationId ?? crypto.randomUUID(),
    })
  }),

  http.post('/api/v1/spaces', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
      kind?: 'PERSONAL' | 'PROJECT' | 'TEAM'
    }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json({ error: 'Der Name des Space ist erforderlich' }, { status: 400 })
    }
    const id = `space-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const listEntry: (typeof mockSpaces)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      kind: body.kind ?? 'PROJECT',
      visibility: 'PRIVATE',
      memberCount: 1,
      userRole: 'ADMIN',
      createdAt: now,
      updatedAt: now,
    }
    mockSpaces.push(listEntry)
    const detail = {
      ...listEntry,
      ownerId: 'mock-user-id',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'mock-user-id', role: 'ADMIN' as const, createdAt: now }],
    }
    mockSpaceDetails[id] = detail
    return HttpResponse.json(detail, { status: 201 })
  }),

  http.get('/api/v1/spaces', () => {
    return HttpResponse.json(mockSpaces)
  }),

  http.get('/api/v1/spaces/:spaceId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(space)
  }),

  http.post('/api/v1/spaces/:spaceId/members', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }

    const body = (await request.json()) as { userId: string; role?: 'MEMBER' | 'CURATOR' | 'ADMIN' }
    if (!body.userId) {
      return HttpResponse.json({ error: 'userId is required' }, { status: 400 })
    }
    if (space.members.some((member) => member.userId === body.userId)) {
      return HttpResponse.json(
        { error: 'Der Benutzer ist bereits Mitglied dieses Space' },
        { status: 409 },
      )
    }

    const role = body.role ?? 'MEMBER'
    const member = { userId: body.userId, role, createdAt: new Date().toISOString() }
    space.members.push(member)
    recalculateRoleCounts(spaceId)
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/spaces/:spaceId/members/:userId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const userId = String(params.userId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    space.members = space.members.filter((member) => member.userId !== userId)
    recalculateRoleCounts(spaceId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/spaces/:spaceId/members/:userId/role', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const userId = String(params.userId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const target = space.members.find((member) => member.userId === userId)
    if (!target) {
      return HttpResponse.json({ error: 'Mitglied des Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { role: 'MEMBER' | 'CURATOR' | 'ADMIN' }
    target.role = body.role
    recalculateRoleCounts(spaceId)
    return HttpResponse.json(target)
  }),

  http.post('/api/v1/spaces/:spaceId/transfer-ownership', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    const newOwner = space.members.find((member) => member.userId === body.userId)
    if (!newOwner) {
      return HttpResponse.json({ error: 'Mitglied des Space nicht gefunden' }, { status: 404 })
    }
    space.ownerId = body.userId
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/spaces/:spaceId', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const listEntry = mockSpaces.find((item) => item.id === spaceId)
    if (!space || !listEntry) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { name: string; description: string }
    space.name = body.name
    space.description = body.description
    listEntry.name = body.name
    listEntry.description = body.description
    return HttpResponse.json(space)
  }),

  http.delete('/api/v1/spaces/:spaceId', ({ params }) => {
    const spaceId = String(params.spaceId)
    delete mockSpaceDetails[spaceId]
    const idx = mockSpaces.findIndex((item) => item.id === spaceId)
    if (idx >= 0) {
      mockSpaces.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/admin/users', () => {
    return HttpResponse.json(mockUsers)
  }),

  http.get('/api/v1/auth/config', () => {
    return HttpResponse.json(mockAuthConfig)
  }),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as LoginRequest
    if (body.username === 'admin' && body.password === 'admin') {
      return HttpResponse.json(mockLoginResponse)
    }
    return HttpResponse.json({ error: 'Ungültige Anmeldedaten' }, { status: 401 })
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
