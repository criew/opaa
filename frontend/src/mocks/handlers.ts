import { http, HttpResponse } from 'msw'
import {
  mockHealthResponse,
  mockIndexingIdle,
  mockIndexingCompleted,
  getRandomMockResponse,
  mockErrorResponse,
  mockAuthConfig,
  mockUser,
  mockUsers,
  mockSpaces,
  mockSpaceDetails,
  mockGroups,
  mockGroupDetails,
  mockLibraries,
  mockLibraryDetails,
  mockMyGroups,
} from './fixtures'
import type {
  IndexingStatusResponse,
  LibraryOwnerType,
  LibraryVisibility,
  QueryRequest,
} from '../types/api'

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
      isDefault: false,
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

  http.get('/api/v1/admin/groups', () => {
    return HttpResponse.json(mockGroups)
  }),

  http.post('/api/v1/admin/groups', async ({ request }) => {
    const body = (await request.json()) as { name: string; description?: string }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json({ error: 'Der Name der Gruppe ist erforderlich' }, { status: 400 })
    }
    const id = `group-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const listEntry: (typeof mockGroups)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      kind: 'AD_HOC',
      externalId: null,
      parentGroupId: null,
      memberCount: 0,
      createdAt: now,
      updatedAt: now,
    }
    mockGroups.push(listEntry)
    mockGroupDetails[id] = { ...listEntry, members: [] }
    return HttpResponse.json(mockGroupDetails[id], { status: 201 })
  }),

  http.get('/api/v1/admin/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(group)
  }),

  http.put('/api/v1/admin/groups/:groupId', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { name: string; description?: string }
    group.name = body.name
    group.description = body.description ?? null
    listEntry.name = body.name
    listEntry.description = body.description ?? null
    return HttpResponse.json(group)
  }),

  http.delete('/api/v1/admin/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    delete mockGroupDetails[groupId]
    const idx = mockGroups.findIndex((item) => item.id === groupId)
    if (idx >= 0) {
      mockGroups.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/groups/:groupId/members', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    if (!body.userId) {
      return HttpResponse.json({ error: 'userId is required' }, { status: 400 })
    }
    if (group.members.some((member) => member.userId === body.userId)) {
      return HttpResponse.json(
        { error: 'Der Benutzer ist bereits Mitglied dieser Gruppe' },
        { status: 409 },
      )
    }
    const member = { userId: body.userId, createdAt: new Date().toISOString() }
    group.members.push(member)
    group.memberCount = group.members.length
    listEntry.memberCount = group.members.length
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/admin/groups/:groupId/members/:userId', ({ params }) => {
    const groupId = String(params.groupId)
    const userId = String(params.userId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    group.members = group.members.filter((member) => member.userId !== userId)
    group.memberCount = group.members.length
    listEntry.memberCount = group.members.length
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries', () => {
    return HttpResponse.json(mockLibraries)
  }),

  http.post('/api/v1/libraries', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
      ownerType?: LibraryOwnerType
      ownerId?: string
      visibility?: LibraryVisibility
      listed?: boolean
    }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json(
        { error: 'Der Name der Bibliothek ist erforderlich' },
        { status: 400 },
      )
    }
    // Mirrors KnowledgeLibraryService#createLibrary: only members of a group can own a library
    // in its name.
    if (body.ownerType === 'GROUP' && !mockMyGroups.some((group) => group.id === body.ownerId)) {
      return HttpResponse.json(
        { error: 'Nur Mitglieder der Gruppe koennen eine Bibliothek in ihrem Namen anlegen' },
        { status: 403 },
      )
    }
    const id = `library-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const ownerType = body.ownerType ?? 'USER'
    const listEntry: (typeof mockLibraries)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      ownerType,
      visibility: body.visibility ?? 'PRIVATE',
      listed: body.listed ?? false,
      personal: false,
      myRole: 'OWNER',
      createdAt: now,
      updatedAt: now,
    }
    mockLibraries.push(listEntry)
    const detail: (typeof mockLibraryDetails)[string] = {
      ...listEntry,
      ownerId: ownerType === 'GROUP' ? (body.ownerId ?? null) : 'mock-user-id',
      documentCount: 0,
    }
    mockLibraryDetails[id] = detail
    return HttpResponse.json(detail, { status: 201 })
  }),

  http.get('/api/v1/libraries/:libraryId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(library)
  }),

  http.put('/api/v1/libraries/:libraryId', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (!library || !listEntry) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as {
      name: string
      description?: string
      visibility?: LibraryVisibility
      listed?: boolean
    }
    // Mirrors KnowledgeLibraryService#updateLibrary: the personal library's visibility can never
    // be ORGANIZATION, since that would expose its owner's private documents to everyone.
    if (library.personal && body.visibility === 'ORGANIZATION') {
      return HttpResponse.json(
        {
          error:
            'Die Sichtbarkeit der persoenlichen Bibliothek kann nicht auf ORGANIZATION gesetzt werden',
        },
        { status: 400 },
      )
    }
    library.name = body.name
    library.description = body.description ?? null
    library.visibility = body.visibility ?? library.visibility
    library.listed = body.listed ?? library.listed
    listEntry.name = library.name
    listEntry.description = library.description
    listEntry.visibility = library.visibility
    listEntry.listed = library.listed
    return HttpResponse.json(library)
  }),

  http.delete('/api/v1/libraries/:libraryId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    // Mirrors KnowledgeLibraryService#deleteLibrary: neither the personal nor the SYSTEM library
    // can ever be deleted, regardless of caller.
    if (library.personal) {
      return HttpResponse.json(
        { error: 'Die persoenliche Bibliothek kann nicht geloescht werden' },
        { status: 400 },
      )
    }
    if (library.ownerType === 'SYSTEM') {
      return HttpResponse.json(
        { error: 'Die System-Bibliothek kann nicht geloescht werden' },
        { status: 400 },
      )
    }
    delete mockLibraryDetails[libraryId]
    const idx = mockLibraries.findIndex((item) => item.id === libraryId)
    if (idx >= 0) {
      mockLibraries.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/me/groups', () => {
    return HttpResponse.json(mockMyGroups)
  }),

  http.get('/api/v1/auth/config', () => {
    return HttpResponse.json(mockAuthConfig)
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
