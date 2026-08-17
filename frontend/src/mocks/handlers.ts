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
  mockLibraryDocuments,
  mockLibraryGrants,
  mockMyGroups,
  resetMockLibraryDocuments,
  resetMockLibraryGrants,
} from './fixtures'
import type {
  AssetGrantRequest,
  AssetRole,
  DocumentSourceType,
  DocumentStatus,
  IndexingStatusResponse,
  IndexingTriggerRequest,
  LibraryOwnerType,
  LibraryVisibility,
  QueryRequest,
} from '../types/api'

// Mirrors SupportedDocumentFormats#EXTENSIONS (backend/src/main/java/io/opaa/indexing) - kept as a
// literal list here rather than importing across the frontend/backend boundary.
const SUPPORTED_DOCUMENT_EXTENSIONS = ['.doc', '.docx', '.md', '.pdf', '.pptx', '.txt']
const MAX_UPLOAD_SIZE_BYTES = 50 * 1024 * 1024
const documentPollCounts = new Map<string, number>()

export function resetDocumentMockState() {
  documentPollCounts.clear()
  resetMockLibraryDocuments()
}

export function resetGrantMockState() {
  resetMockLibraryGrants()
}

const ASSET_ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/**
 * Mirrors AssetGrantService#requireManageable: every grants endpoint requires at least MANAGER on
 * the library, distinct from canManageMockLibrary's EDITOR threshold for documents.
 */
function canManageMockLibraryGrants(libraryId: string): boolean {
  const role = mockLibraryDetails[libraryId]?.myRole
  return role === 'MANAGER' || role === 'OWNER'
}

/** Mirrors AssetGrant#isExpired: null expiresAt means "never expires". */
function isMockGrantActiveOwner(grant: { role: AssetRole; expiresAt?: string | null }): boolean {
  return (
    grant.role === 'OWNER' && (!grant.expiresAt || new Date(grant.expiresAt).getTime() > Date.now())
  )
}

/**
 * Mirrors AssetGrantRepository#countOtherActiveOwnerGrants - how many *other* active OWNER grants
 * a library has besides the one being changed or removed, used by both the #423 code review's
 * nit-4 guards below (409 "last active OWNER" on downgrade and on revoke).
 */
function countOtherActiveMockOwnerGrants(libraryId: string, excludingGrantId: string): number {
  return (mockLibraryGrants[libraryId] ?? []).filter(
    (grant) => grant.id !== excludingGrantId && isMockGrantActiveOwner(grant),
  ).length
}

/**
 * Mirrors LibraryDocumentService#requireEditable: uploading and deleting require at least EDITOR
 * on the library. The mock has no separate system-admin bypass - each fixture's own myRole is the
 * single source of truth here, same as it already is for the frontend's canManageDocuments checks.
 */
function canManageMockLibrary(libraryId: string): boolean {
  const role = mockLibraryDetails[libraryId]?.myRole
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

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
    // #419: libraryId is required - a missing one mirrors the backend's 400.
    const body = (await request.json().catch(() => null)) as IndexingTriggerRequest | null
    if (!body?.libraryId) {
      return HttpResponse.json(
        { error: 'libraryId ist erforderlich', status: 400, timestamp: new Date().toISOString() },
        { status: 400 },
      )
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

  http.get('/api/v1/libraries/:libraryId/documents', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const documents = mockLibraryDocuments[libraryId] ?? []
    // Simulates the indexing pipeline resolving a freshly uploaded document after a couple of
    // polls, mirroring the INDEXING_POLL_STEPS pattern above - lets tests exercise the "PENDING
    // until the list refresh settles" acceptance criterion without staying PENDING forever.
    documents.forEach((doc) => {
      if (doc.status !== 'PENDING') return
      const pollCount = (documentPollCounts.get(doc.id) ?? 0) + 1
      documentPollCounts.set(doc.id, pollCount)
      if (pollCount >= 2) {
        doc.status = 'INDEXED'
        doc.chunkCount = 12
        doc.indexedAt = new Date().toISOString()
      }
    })
    return HttpResponse.json(documents)
  }),

  http.post('/api/v1/libraries/:libraryId/documents', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const formData = await request.formData()
    const file = formData.get('file')
    if (!(file instanceof File) || file.size === 0) {
      return HttpResponse.json({ error: 'Datei ist erforderlich' }, { status: 400 })
    }
    if (file.size > MAX_UPLOAD_SIZE_BYTES) {
      return HttpResponse.json(
        {
          error: `Die Datei ist zu gross. Erlaubt sind hoechstens ${MAX_UPLOAD_SIZE_BYTES / (1024 * 1024)} MB`,
        },
        { status: 413 },
      )
    }
    const lowerCasedName = file.name.toLowerCase()
    if (!SUPPORTED_DOCUMENT_EXTENSIONS.some((ext) => lowerCasedName.endsWith(ext))) {
      return HttpResponse.json(
        {
          error: `Das Dateiformat wird nicht unterstuetzt. Erlaubt sind: ${SUPPORTED_DOCUMENT_EXTENSIONS.join(', ')}`,
        },
        { status: 400 },
      )
    }
    // Mirrors LibraryDocumentService#uploadDocument catching EmptyDocumentContentException: a file
    // whose text content is blank (e.g. a scanned image with no extractable text) is rejected after
    // the format check passes, distinct from the "no file at all" 400 above.
    const textContent = await file.text()
    if (textContent.trim() === '') {
      return HttpResponse.json(
        { error: 'Aus der Datei konnte kein Text extrahiert werden' },
        { status: 422 },
      )
    }
    const existing = mockLibraryDocuments[libraryId] ?? []
    // Mirrors LibraryDocumentService#uploadDocument: dedup is scoped per library and keyed on
    // content, approximated here by file name since MSW fixtures do not carry a real checksum.
    if (existing.some((doc) => doc.fileName === file.name)) {
      return HttpResponse.json(
        { error: 'Diese Datei ist bereits in dieser Bibliothek vorhanden' },
        { status: 409 },
      )
    }
    const document: (typeof existing)[number] = {
      id: `document-${crypto.randomUUID().slice(0, 8)}`,
      fileName: file.name,
      contentType: file.type || null,
      fileSize: file.size,
      status: 'PENDING' as DocumentStatus,
      sourceType: 'UPLOAD' as DocumentSourceType,
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    }
    mockLibraryDocuments[libraryId] = [document, ...existing]
    const detail = mockLibraryDetails[libraryId]
    if (detail) {
      detail.documentCount = (detail.documentCount ?? 0) + 1
    }
    return HttpResponse.json(document, { status: 201 })
  }),

  http.delete('/api/v1/libraries/:libraryId/documents/:documentId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const documentId = String(params.documentId)
    const existing = mockLibraryDocuments[libraryId]
    if (!mockLibraryDetails[libraryId] || !existing) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const idx = existing.findIndex((doc) => doc.id === documentId)
    if (idx < 0) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    existing.splice(idx, 1)
    const detail = mockLibraryDetails[libraryId]
    if (detail && (detail.documentCount ?? 0) > 0) {
      detail.documentCount = (detail.documentCount ?? 0) - 1
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/grants', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    return HttpResponse.json(mockLibraryGrants[libraryId] ?? [])
  }),

  http.post('/api/v1/libraries/:libraryId/grants', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    // Mirrors KnowledgeLibraryService/AssetGrantService#upsertGrant: no grants on the personal
    // library, which is meant to reach only its owner.
    if (library.personal) {
      return HttpResponse.json(
        { error: 'Auf die persoenliche Bibliothek koennen keine Berechtigungen vergeben werden' },
        { status: 400 },
      )
    }
    const body = (await request.json()) as AssetGrantRequest
    if (!body.subjectType || !body.subjectId || !body.role) {
      return HttpResponse.json(
        { error: 'subjectType, subjectId und role sind erforderlich' },
        { status: 400 },
      )
    }
    // Mirrors AssetGrantService's escalation guard: the caller may never grant a role higher than
    // their own.
    const callerRoleIndex = ASSET_ROLE_ORDER.indexOf(library.myRole)
    const requestedRoleIndex = ASSET_ROLE_ORDER.indexOf(body.role)
    if (requestedRoleIndex > callerRoleIndex) {
      return HttpResponse.json(
        { error: `Die eigene Rolle reicht nicht aus, um die Rolle ${body.role} zu vergeben` },
        { status: 403 },
      )
    }
    const now = new Date().toISOString()
    const existing = mockLibraryGrants[libraryId] ?? []
    const existingIndex = existing.findIndex(
      (grant) => grant.subjectType === body.subjectType && grant.subjectId === body.subjectId,
    )
    if (existingIndex >= 0) {
      const existingGrant = existing[existingIndex]
      // Mirrors AssetGrantService#requireCallerCanTouchExistingGrant (escalation guard, half 2):
      // the caller may never touch a grant that already carries a role higher than their own,
      // independent of whether they could have granted that role in the first place (#423 code
      // review, nit 4 - previously only the *requested* role above was capped).
      const existingRoleIndex = ASSET_ROLE_ORDER.indexOf(existingGrant.role)
      if (existingRoleIndex > callerRoleIndex) {
        return HttpResponse.json(
          {
            error: `Die eigene Rolle reicht nicht aus, um eine bestehende ${existingGrant.role}-Berechtigung zu aendern`,
          },
          { status: 403 },
        )
      }
      // Mirrors AssetGrantService#requireNotDowngradingTheLastActiveOwnerGrant: downgrading the
      // library's last active OWNER grant is exactly as dangerous as revoking it outright - both
      // leave nobody able to manage the library at all, not even to grant a new OWNER.
      const newExpiresAt = body.expiresAt ?? null
      const staysActiveOwner =
        body.role === 'OWNER' && (!newExpiresAt || new Date(newExpiresAt).getTime() > Date.now())
      if (
        isMockGrantActiveOwner(existingGrant) &&
        !staysActiveOwner &&
        countOtherActiveMockOwnerGrants(libraryId, existingGrant.id) === 0
      ) {
        return HttpResponse.json(
          {
            error: 'Die letzte OWNER-Berechtigung einer Bibliothek kann nicht herabgestuft werden',
          },
          { status: 409 },
        )
      }
      const updated = {
        ...existingGrant,
        role: body.role,
        expiresAt: newExpiresAt,
        updatedAt: now,
      }
      existing[existingIndex] = updated
      mockLibraryGrants[libraryId] = existing
      return HttpResponse.json(updated)
    }
    const created = {
      id: `grant-${crypto.randomUUID().slice(0, 8)}`,
      subjectType: body.subjectType,
      subjectId: body.subjectId,
      role: body.role,
      expiresAt: body.expiresAt ?? null,
      grantedByUserId: mockUser.id,
      createdAt: now,
      updatedAt: now,
    }
    mockLibraryGrants[libraryId] = [...existing, created]
    return HttpResponse.json(created)
  }),

  http.delete('/api/v1/libraries/:libraryId/grants/:grantId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const grantId = String(params.grantId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const existing = mockLibraryGrants[libraryId] ?? []
    const idx = existing.findIndex((grant) => grant.id === grantId)
    if (idx < 0) {
      return HttpResponse.json({ error: 'Berechtigung nicht gefunden' }, { status: 404 })
    }
    const grant = existing[idx]
    // Mirrors AssetGrantService#requireCallerCanTouchExistingGrant, the same escalation guard
    // half 2 as the POST update path above (#423 code review, nit 4).
    const callerRoleIndex = ASSET_ROLE_ORDER.indexOf(library.myRole)
    const grantRoleIndex = ASSET_ROLE_ORDER.indexOf(grant.role)
    if (grantRoleIndex > callerRoleIndex) {
      return HttpResponse.json(
        {
          error: `Die eigene Rolle reicht nicht aus, um eine bestehende ${grant.role}-Berechtigung zu entfernen`,
        },
        { status: 403 },
      )
    }
    // Mirrors AssetGrantService#revokeGrant's last-active-OWNER guard: removing the library's
    // last active OWNER grant would leave nobody able to manage it at all.
    if (
      isMockGrantActiveOwner(grant) &&
      countOtherActiveMockOwnerGrants(libraryId, grant.id) === 0
    ) {
      return HttpResponse.json(
        { error: 'Die letzte OWNER-Berechtigung einer Bibliothek kann nicht entfernt werden' },
        { status: 409 },
      )
    }
    existing.splice(idx, 1)
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
