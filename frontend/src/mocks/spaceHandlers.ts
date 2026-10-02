import { http, HttpResponse } from 'msw'
import type { SpaceRequest, SpaceUpdateRequest } from '../types/api'
import {
  defaultChatAutoCleanup,
  mockSpaces,
  mockSpaceDetails,
  mockSpaceMembers,
} from './spaceFixtures'
import { mockGroups } from './groupFixtures'

function recalculateRoleCounts(spaceId: string) {
  const space = mockSpaceDetails[spaceId]
  const members = mockSpaceMembers[spaceId]
  if (!space || !members) return
  const base = { MEMBER: 0, CURATOR: 0, ADMIN: 0 }
  for (const member of members) {
    base[member.role] += 1
  }
  space.roleCounts = base
  space.memberCount = members.length
  const listEntry = mockSpaces.find((entry) => entry.id === spaceId)
  if (listEntry) {
    listEntry.memberCount = members.length
    const groupCount = members.filter((member) => member.subjectType === 'GROUP').length
    listEntry.memberships = { groupCount, userCount: members.length - groupCount }
  }
}

export const spaceHandlers = [
  http.post('/api/v1/spaces', async ({ request }) => {
    const body = (await request.json()) as SpaceRequest
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
      archived: false,
      visibility: 'PRIVATE',
      memberCount: 1,
      memberships: { groupCount: 0, userCount: 1 },
      libraryCount: 0,
      chatCount: 0,
      userRole: 'ADMIN',
      createdAt: now,
      updatedAt: now,
    }
    mockSpaces.push(listEntry)
    const detail = {
      ...listEntry,
      ownerId: 'mock-user-id',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      chatAutoCleanup: { ...defaultChatAutoCleanup, enabled: body.chatAutoCleanup === true },
    }
    mockSpaceDetails[id] = detail
    mockSpaceMembers[id] = [
      {
        id: `membership-${id}-mock-user-id`,
        subjectType: 'USER' as const,
        subjectId: 'mock-user-id',
        role: 'ADMIN' as const,
        createdAt: now,
      },
    ]
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

  // restricted to ADMIN, owner and system admin - the mock's single authenticated user is
  // always the system admin (see mockUser), but its own membership role in this space still gates
  // the list, mirroring SpaceService#listMembers/#requireMemberListViewer.
  http.get('/api/v1/spaces/:spaceId/members', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    if (space.userRole !== 'ADMIN') {
      return HttpResponse.json(
        { error: 'Nur Administratoren oder der Eigentümer können die Mitgliederliste einsehen' },
        { status: 403 },
      )
    }
    return HttpResponse.json(members)
  }),

  http.post('/api/v1/spaces/:spaceId/members', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }

    const body = (await request.json()) as {
      subjectType?: 'USER' | 'GROUP'
      subjectId?: string
      role?: 'MEMBER' | 'CURATOR' | 'ADMIN'
    }
    const subjectType = body.subjectType ?? 'USER'
    if (!body.subjectId) {
      return HttpResponse.json({ error: 'subjectId is required' }, { status: 400 })
    }
    if (
      members.some(
        (member) => member.subjectType === subjectType && member.subjectId === body.subjectId,
      )
    ) {
      return HttpResponse.json(
        {
          error:
            subjectType === 'GROUP'
              ? 'Die Gruppe ist bereits Mitglied dieses Space'
              : 'Der Benutzer ist bereits Mitglied dieses Space',
        },
        { status: 409 },
      )
    }

    const role = body.role ?? 'MEMBER'
    const group = mockGroups.find((candidate) => candidate.id === body.subjectId)
    const member = {
      id: `membership-${spaceId}-${body.subjectId}`,
      subjectType,
      subjectId: body.subjectId,
      displayName: subjectType === 'GROUP' ? (group?.name ?? null) : null,
      role,
      ...(subjectType === 'GROUP'
        ? { memberCountAtGrant: null, memberCountNow: null, smallGroup: true, emptyGroup: false }
        : {}),
      createdAt: new Date().toISOString(),
    }
    members.push(member)
    recalculateRoleCounts(spaceId)
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/spaces/:spaceId/members/:membershipId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const membershipId = String(params.membershipId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    mockSpaceMembers[spaceId] = members.filter((member) => member.id !== membershipId)
    recalculateRoleCounts(spaceId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/spaces/:spaceId/members/:membershipId/role', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const membershipId = String(params.membershipId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const target = members.find((member) => member.id === membershipId)
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
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    const newOwner = members.find(
      (member) => member.subjectType === 'USER' && member.subjectId === body.userId,
    )
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
    const body = (await request.json()) as SpaceUpdateRequest
    space.name = body.name
    space.description = body.description
    if (body.chatAutoCleanup != null) {
      space.chatAutoCleanup = { ...space.chatAutoCleanup, enabled: body.chatAutoCleanup }
    }
    listEntry.name = body.name
    listEntry.description = body.description
    return HttpResponse.json(space)
  }),

  http.delete('/api/v1/spaces/:spaceId', ({ params }) => {
    const spaceId = String(params.spaceId)
    delete mockSpaceDetails[spaceId]
    delete mockSpaceMembers[spaceId]
    const idx = mockSpaces.findIndex((item) => item.id === spaceId)
    if (idx >= 0) {
      mockSpaces.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // archives a space instead of deleting it - idempotent, same as SpaceService#archiveSpace.
  http.post('/api/v1/spaces/:spaceId/archive', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const listEntry = mockSpaces.find((item) => item.id === spaceId)
    if (!space || !listEntry) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    space.archived = true
    listEntry.archived = true
    return HttpResponse.json(space)
  }),

  http.get('/api/v1/spaces/:spaceId/access-derivation', ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const userId = new URL(request.url).searchParams.get('userId')
    // Die Auskunft über eine andere Person nennt keine geschützte Gruppe, sondern nur die
    // wirksame Rolle (ADR-0036, Entscheidung 9).
    if (userId && userId !== 'mock-user-id') {
      return HttpResponse.json({
        spaceId,
        userId,
        effectiveRole: 'MEMBER',
        pathsWithheld: true,
        paths: [],
      })
    }
    return HttpResponse.json({
      spaceId,
      userId: userId ?? 'mock-user-id',
      effectiveRole: space.userRole ?? 'MEMBER',
      pathsWithheld: false,
      paths: [
        {
          basis: 'DIRECT_MEMBERSHIP',
          assetRole: null,
          spaceRole: space.userRole ?? 'MEMBER',
          since: '2026-03-01T10:00:00Z',
          group: null,
        },
      ],
    })
  }),
]
