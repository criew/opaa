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
  mockWorkspaces,
  mockWorkspaceDetails,
  mockWorkspaceDocuments,
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

function recalculateRoleCounts(workspaceId: string) {
  const workspace = mockWorkspaceDetails[workspaceId]
  if (!workspace) return
  const base = { VIEWER: 0, EDITOR: 0, ADMIN: 0, OWNER: 0 }
  for (const member of workspace.members) {
    base[member.role] += 1
  }
  workspace.roleCounts = base
  workspace.memberCount = workspace.members.length
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
        message: 'Indexing started',
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
    const requestedWorkspaceIds = body.workspaceIds ?? []
    const filteredSources =
      requestedWorkspaceIds.length > 0
        ? mockResponse.sources.filter((source) =>
            requestedWorkspaceIds.some((workspaceId) => {
              const workspace = mockWorkspaces.find((item) => item.id === workspaceId)
              return workspace?.name === source.workspaceName
            }),
          )
        : mockResponse.sources
    return HttpResponse.json({
      ...mockResponse,
      sources: filteredSources,
      conversationId: body.conversationId ?? crypto.randomUUID(),
    })
  }),

  http.post('/api/v1/workspaces', async ({ request }) => {
    const body = (await request.json()) as { name: string; description?: string }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json({ error: 'Workspace name is required' }, { status: 400 })
    }
    if (mockWorkspaces.some((ws) => ws.name.toLowerCase() === body.name.trim().toLowerCase())) {
      return HttpResponse.json({ error: 'Workspace name already exists' }, { status: 409 })
    }
    const id = `ws-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const listEntry: (typeof mockWorkspaces)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      type: 'SHARED',
      memberCount: 1,
      userRole: 'OWNER',
      createdAt: now,
      updatedAt: now,
    }
    mockWorkspaces.push(listEntry)
    const detail = {
      ...listEntry,
      ownerId: 'mock-user-id',
      roleCounts: { VIEWER: 0, EDITOR: 0, ADMIN: 0, OWNER: 1 },
      members: [{ userId: 'mock-user-id', role: 'OWNER' as const, createdAt: now }],
    }
    mockWorkspaceDetails[id] = detail
    mockWorkspaceDocuments[id] = []
    return HttpResponse.json(detail, { status: 201 })
  }),

  http.get('/api/v1/workspaces', () => {
    return HttpResponse.json(mockWorkspaces)
  }),

  http.get('/api/v1/workspaces/:workspaceId', ({ params }) => {
    const workspaceId = String(params.workspaceId)
    const workspace = mockWorkspaceDetails[workspaceId]
    if (!workspace) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }
    return HttpResponse.json(workspace)
  }),

  http.get('/api/v1/workspaces/:workspaceId/documents', ({ params }) => {
    const workspaceId = String(params.workspaceId)
    return HttpResponse.json(mockWorkspaceDocuments[workspaceId] ?? [])
  }),

  http.post('/api/v1/workspaces/:workspaceId/members', async ({ params, request }) => {
    const workspaceId = String(params.workspaceId)
    const workspace = mockWorkspaceDetails[workspaceId]
    if (!workspace) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }

    const body = (await request.json()) as { userId: string; role?: 'VIEWER' | 'EDITOR' | 'ADMIN' }
    if (!body.userId) {
      return HttpResponse.json({ error: 'userId is required' }, { status: 400 })
    }
    if (workspace.members.some((member) => member.userId === body.userId)) {
      return HttpResponse.json({ error: 'Member already exists' }, { status: 409 })
    }

    const role = body.role ?? 'VIEWER'
    const member = { userId: body.userId, role, createdAt: new Date().toISOString() }
    workspace.members.push(member)
    recalculateRoleCounts(workspaceId)
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/workspaces/:workspaceId/members/:userId', ({ params }) => {
    const workspaceId = String(params.workspaceId)
    const userId = String(params.userId)
    const workspace = mockWorkspaceDetails[workspaceId]
    if (!workspace) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }
    workspace.members = workspace.members.filter((member) => member.userId !== userId)
    recalculateRoleCounts(workspaceId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/workspaces/:workspaceId/members/:userId/role', async ({ params, request }) => {
    const workspaceId = String(params.workspaceId)
    const userId = String(params.userId)
    const workspace = mockWorkspaceDetails[workspaceId]
    if (!workspace) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }
    const target = workspace.members.find((member) => member.userId === userId)
    if (!target) {
      return HttpResponse.json({ error: 'Member not found' }, { status: 404 })
    }
    const body = (await request.json()) as { role: 'VIEWER' | 'EDITOR' | 'ADMIN' }
    target.role = body.role
    recalculateRoleCounts(workspaceId)
    return HttpResponse.json(target)
  }),

  http.post('/api/v1/workspaces/:workspaceId/transfer-ownership', async ({ params, request }) => {
    const workspaceId = String(params.workspaceId)
    const workspace = mockWorkspaceDetails[workspaceId]
    if (!workspace) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    const currentOwner = workspace.members.find((member) => member.role === 'OWNER')
    const newOwner = workspace.members.find((member) => member.userId === body.userId)
    if (!currentOwner || !newOwner) {
      return HttpResponse.json({ error: 'Member not found' }, { status: 404 })
    }
    currentOwner.role = 'ADMIN'
    newOwner.role = 'OWNER'
    recalculateRoleCounts(workspaceId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/workspaces/:workspaceId', async ({ params, request }) => {
    const workspaceId = String(params.workspaceId)
    const workspace = mockWorkspaceDetails[workspaceId]
    const listEntry = mockWorkspaces.find((item) => item.id === workspaceId)
    if (!workspace || !listEntry) {
      return HttpResponse.json({ error: 'Workspace not found' }, { status: 404 })
    }
    const body = (await request.json()) as { name: string; description: string }
    workspace.name = body.name
    workspace.description = body.description
    listEntry.name = body.name
    listEntry.description = body.description
    return HttpResponse.json(workspace)
  }),

  http.delete('/api/v1/workspaces/:workspaceId', ({ params }) => {
    const workspaceId = String(params.workspaceId)
    delete mockWorkspaceDetails[workspaceId]
    delete mockWorkspaceDocuments[workspaceId]
    const idx = mockWorkspaces.findIndex((item) => item.id === workspaceId)
    if (idx >= 0) {
      mockWorkspaces.splice(idx, 1)
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
    return HttpResponse.json({ error: 'Invalid credentials' }, { status: 401 })
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
