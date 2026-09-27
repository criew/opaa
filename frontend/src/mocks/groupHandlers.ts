import { http, HttpResponse } from 'msw'
import { mockUsers } from './userFixtures'
import { mockGroups, mockGroupDetails, mockMyGroups, mockSelectableGroups } from './groupFixtures'

export const groupHandlers = [
  http.get('/api/v1/admin/groups', () => {
    return HttpResponse.json(mockGroups)
  }),

  http.post('/api/v1/groups', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
      releasedForUse?: boolean
      protectedGroup?: boolean
      stewardIds?: string[]
    }
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
      origin: 'INTERNAL',
      state: body.releasedForUse ? 'ACTIVE' : 'NOT_RELEASED',
      provider: null,
      sourcePath: null,
      parentGroupId: null,
      memberCount: 0,
      // As in the service: the named stewards, or the creating account where none are named.
      dissolved: false,
      releasedForUse: body.releasedForUse ?? false,
      protectedGroup: body.protectedGroup ?? false,
      stewards: (body.stewardIds?.length ? body.stewardIds : ['mock-user-id']).map((userId) => ({
        userId,
        displayName: userId === 'mock-user-id' ? 'Admin' : null,
        appointedAt: now,
      })),
      createdAt: now,
      updatedAt: now,
    }
    mockGroups.push(listEntry)
    mockGroupDetails[id] = { ...listEntry, members: [] }
    return HttpResponse.json(mockGroupDetails[id], { status: 201 })
  }),

  // Vor '/api/v1/groups/:groupId': MSW waehlt den ersten passenden Handler, und der
  // Einzelgruppen-Pfad faengt sonst 'selectable' als Gruppen-ID ab (im Backend entscheidet
  // Springs Pfad-Spezifitaet, hier die Reihenfolge).
  http.get('/api/v1/groups/selectable', ({ request }) => {
    const query = (new URL(request.url).searchParams.get('query') ?? '').trim().toLowerCase()
    if (query.length < 2) {
      return HttpResponse.json([])
    }
    const matches = mockSelectableGroups.filter((group) =>
      group.protectedGroup
        ? (group.name ?? '').toLowerCase() === query
        : (group.name ?? '').toLowerCase().includes(query) ||
          (group.sourcePath ?? '').toLowerCase().includes(query),
    )
    return HttpResponse.json(matches.slice(0, 20))
  }),

  // #1820: Der Kennungsweg löst die Gruppe unter derselben Sichtbarkeitsregel auf; eine
  // geschützte Gruppe kommt dabei ohne ihren Namen zurück.
  http.get('/api/v1/groups/selectable/:groupId', ({ params }) => {
    const group = mockSelectableGroups.find((candidate) => candidate.id === String(params.groupId))
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(group.protectedGroup ? { ...group, name: null } : group)
  }),

  // As in the backend, the mock user reads as the administration: where they steward nothing, the
  // detail withholds the list and only the recorded '/members' endpoint hands it out.
  http.get('/api/v1/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const isSteward = group.stewards.some((steward) => steward.userId === 'mock-user-id')
    return HttpResponse.json(isSteward ? group : { ...group, members: null })
  }),

  http.get('/api/v1/groups/:groupId/members', ({ params }) => {
    const group = mockGroupDetails[String(params.groupId)]
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(group.members ?? [])
  }),

  http.put('/api/v1/groups/:groupId', async ({ params, request }) => {
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

  http.delete('/api/v1/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    delete mockGroupDetails[groupId]
    const idx = mockGroups.findIndex((item) => item.id === groupId)
    if (idx >= 0) {
      mockGroups.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/groups/:groupId/members', async ({ params, request }) => {
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

  http.delete('/api/v1/groups/:groupId/members/:userId', ({ params }) => {
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

  http.get('/api/v1/me/stewarded-groups', () => {
    return HttpResponse.json(
      mockGroups.filter((group) =>
        group.stewards.some((steward) => steward.userId === 'mock-user-id'),
      ),
    )
  }),

  http.get('/api/v1/groups/:groupId/stewards', ({ params }) => {
    const group = mockGroupDetails[String(params.groupId)]
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(group.stewards)
  }),

  http.post('/api/v1/groups/:groupId/stewards', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    if (group.stewards.some((steward) => steward.userId === body.userId)) {
      return HttpResponse.json(
        { error: 'Die Person ist bereits verantwortlich für diese Gruppe' },
        { status: 409 },
      )
    }
    const displayName = mockUsers.find((user) => user.id === body.userId)?.displayName ?? null
    const steward = { userId: body.userId, displayName, appointedAt: new Date().toISOString() }
    group.stewards = [...group.stewards, steward]
    listEntry.stewards = group.stewards
    return HttpResponse.json(steward, { status: 201 })
  }),

  http.delete('/api/v1/groups/:groupId/stewards/:userId', ({ params }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    if (group.stewards.length <= 1) {
      return HttpResponse.json(
        {
          error:
            'Dies ist die letzte verantwortliche Person dieser Gruppe. Benennen Sie zuerst eine' +
            ' Nachfolge und geben Sie die Verantwortung dann ab.',
        },
        { status: 409 },
      )
    }
    group.stewards = group.stewards.filter((steward) => steward.userId !== String(params.userId))
    listEntry.stewards = group.stewards
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/groups/:groupId/release', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { releasedForUse: boolean }
    group.releasedForUse = body.releasedForUse
    listEntry.releasedForUse = body.releasedForUse
    // the state follows the release, as the server derives it (GroupStates)
    if (listEntry.kind === 'AD_HOC' && !listEntry.dissolved) {
      listEntry.state = body.releasedForUse ? 'ACTIVE' : 'NOT_RELEASED'
    }
    return HttpResponse.json(group)
  }),

  http.put('/api/v1/groups/:groupId/protection', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { protectedGroup: boolean }
    group.protectedGroup = body.protectedGroup
    listEntry.protectedGroup = body.protectedGroup
    return HttpResponse.json(group)
  }),

  http.get('/api/v1/me/groups', () => {
    return HttpResponse.json(mockMyGroups)
  }),
]
