import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ASSOCIATIONS_NOT_REFRESHED, MEMBERS_NOT_REFRESHED, useSpaceStore } from './spaceStore'
import { resetAllStores } from './resettableStores'
import { getSpace, getSpaces, listSpaceMembers } from '../services/spaceApi'
import type { SpaceMemberResponse } from '../types/api'

const mockCreateSpace = vi.fn()

// #543/#613 review, nit d: getSpaces and archiveSpace share this mutable list, mirroring the
// real backend where archiving is a stateful write and listSpaces re-reads it - a static
// getSpaces() mock (returning the same fixed archived: false payload no matter what
// archiveSelectedSpace does) would make the archive test pass even if archiveSelectedSpace never
// actually refreshed the list, since the assertion would have nothing that could tell the two
// apart. With mockArchiveSpace mutating the same array getSpaces reads from, the test below can
// only pass if archiveSelectedSpace's own loadSpaces() call re-reads it afterwards.
const initialSpaces = [
  {
    id: 'space-project',
    name: 'Engineering',
    description: 'Eng docs',
    isDefault: false,
    archived: false,
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    memberCount: 2,
    memberships: { groupCount: 0, userCount: 2 },
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-personal',
    name: 'Privater Bereich',
    description: null,
    isDefault: true,
    archived: false,
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    memberCount: 1,
    memberships: { groupCount: 0, userCount: 1 },
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

let mutableSpaces = initialSpaces.map((space) => ({ ...space }))

// Membership writes change the list's member figures the way the backend's re-read would.
function changeMemberships(spaceId: string, delta: number) {
  const target = mutableSpaces.find((space) => space.id === spaceId)
  if (!target) return
  target.memberCount += delta
  target.memberships = { ...target.memberships, userCount: target.memberships.userCount + delta }
}

const mockArchiveSpace = vi.fn(async (spaceId: string) => {
  const target = mutableSpaces.find((space) => space.id === spaceId)
  if (target) {
    target.archived = true
  }
  return target
})

/** Resolves once resolve() is called - lets a test hold loadSpaces()'s request open until it
 * explicitly wants the response to arrive, so it can trigger resetAllStores() while the request
 * is still in flight (#575). */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

vi.mock('../services/spaceApi', () => ({
  getSpaces: vi.fn(async () => mutableSpaces.map((space) => ({ ...space }))),
  getSpace: vi.fn(async (spaceId: string) => ({
    id: spaceId,
    name: 'Privater Bereich',
    description: null,
    isDefault: true,
    archived: false,
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    ownerId: 'u1',
    memberCount: 1,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
  // #144: the full member list moved to its own endpoint - loadMembers (called by
  // archiveSelectedSpace among others) needs this mocked or it throws on the now-undefined import.
  listSpaceMembers: vi.fn(async () => [
    { userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
  ]),
  addSpaceMember: vi.fn(async (spaceId: string) => changeMemberships(spaceId, 1)),
  removeSpaceMember: vi.fn(async (spaceId: string) => changeMemberships(spaceId, -1)),
  updateSpaceMemberRole: vi.fn(async () => ({})),
  createSpace: (...args: unknown[]) => mockCreateSpace(...args),
  archiveSpace: (...args: [string]) => mockArchiveSpace(...args),
}))

vi.mock('../services/assetApi', () => ({
  getSpaceAssetAssociations: (spaceId: string) => mockGetSpaceAssetAssociations(spaceId),
  associateSpaceAsset: (spaceId: string, assetType: string, assetId: string) =>
    mockAssociateSpaceAsset(spaceId, assetType, assetId),
  detachSpaceAsset: (spaceId: string, assetId: string) => mockDetachSpaceAsset(spaceId, assetId),
}))

const mockGetSpaceAssetAssociations = vi.fn(async (spaceId: string) => {
  void spaceId
  return {
    hasAssociations: true,
    hasUnreadableAssociations: false,
    hasKnowledge: true,
    hasReadableKnowledge: true,
    items: [
      {
        assetType: 'KNOWLEDGE_LIBRARY',
        assetId: 'lib-1',
        name: 'Rechtsquellen',
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
  }
})
const mockAssociateSpaceAsset = vi.fn(
  async (spaceId: string, assetType: string, assetId: string) => {
    void spaceId
    void assetType
    void assetId
    return {}
  },
)
const mockDetachSpaceAsset = vi.fn(async (spaceId: string, assetId: string) => {
  void spaceId
  void assetId
})

describe('spaceStore', () => {
  beforeEach(() => {
    mutableSpaces = initialSpaces.map((space) => ({ ...space }))
    useSpaceStore.setState({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('sorts the default space first', async () => {
    await useSpaceStore.getState().loadSpaces()
    const names = useSpaceStore.getState().spaces.map((space) => space.name)
    expect(names[0]).toBe('Privater Bereich')
  })

  it('creates a new space and selects it', async () => {
    mockCreateSpace.mockResolvedValueOnce({
      id: 'space-new',
      name: 'New Space',
      description: 'desc',
      isDefault: false,
      reach: { allAccounts: false, groupCount: 0, userCount: 1 },
      ownerId: 'u1',
      memberCount: 1,
      userRole: 'ADMIN',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    })

    const id = await useSpaceStore.getState().createNewSpace('New Space', 'desc')
    expect(id).toBe('space-new')
    expect(mockCreateSpace).toHaveBeenCalledWith(
      'New Space',
      'desc',
      undefined,
      undefined,
      undefined,
    )
    expect(useSpaceStore.getState().selectedSpaceId).toBe('space-new')
  })

  it('passes the chosen assets through to createSpace when provided', async () => {
    mockCreateSpace.mockResolvedValueOnce({
      id: 'space-new',
      name: 'New Space',
      description: 'desc',
      isDefault: false,
      reach: { allAccounts: false, groupCount: 0, userCount: 1 },
      ownerId: 'u1',
      memberCount: 1,
      userRole: 'ADMIN',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    })

    const assets = [{ assetType: 'KNOWLEDGE_LIBRARY' as const, assetId: 'lib-1' }]
    await useSpaceStore.getState().createNewSpace('New Space', 'desc', assets)

    expect(mockCreateSpace).toHaveBeenCalledWith('New Space', 'desc', assets, undefined, undefined)
  })

  // #203: library associations - loaded on demand, not part of selectSpace, since only pages that
  // actually show them (SpacePage, SpaceSettingsPage) need the extra request.
  it('loads library associations for a space', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-project')

    expect(mockGetSpaceAssetAssociations).toHaveBeenCalledWith('space-project')
    expect(useSpaceStore.getState().assetAssociations).toEqual([
      {
        assetType: 'KNOWLEDGE_LIBRARY',
        assetId: 'lib-1',
        name: 'Rechtsquellen',
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
    expect(useSpaceStore.getState().hasAssetAssociations).toBe(true)
    expect(useSpaceStore.getState().hasKnowledge).toBe(true)
    expect(useSpaceStore.getState().hasReadableKnowledge).toBe(true)
    // #783 review finding 1: callers must be able to tell which space this data actually
    // describes before trusting it.
    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe('space-project')
  })

  // #783 review finding 1 (🔴): a response for a space call already superseded by a newer one
  // must not win the race and overwrite the newer call's state - reproduces the bug report's "two
  // quick switches, the stale response arrives last" scenario at the store level.
  it('ignores a stale response for a space no longer being loaded', async () => {
    const first = deferred<{
      hasAssociations: boolean
      hasUnreadableAssociations: boolean
      hasKnowledge: boolean
      hasReadableKnowledge: boolean
      items: {
        assetType: string
        assetId: string
        name: string
        createdByUserId: string
        createdAt: string
      }[]
    }>()
    mockGetSpaceAssetAssociations.mockImplementationOnce(() => first.promise)

    // Started first (space left behind), but resolves last - the real-world case a plain
    // .mockResolvedValueOnce ordering can't reproduce, since here the *second* call's own request
    // settles before the *first* call's deferred response ever arrives.
    const firstCall = useSpaceStore.getState().loadAssetAssociations('space-a')
    const secondCall = useSpaceStore.getState().loadAssetAssociations('space-project')
    await secondCall

    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe('space-project')

    first.resolve({
      hasAssociations: true,
      hasUnreadableAssociations: false,
      hasKnowledge: true,
      hasReadableKnowledge: true,
      items: [
        {
          assetType: 'KNOWLEDGE_LIBRARY',
          assetId: 'lib-a',
          name: 'A',
          createdByUserId: 'u1',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
    })
    await firstCall

    // The now-stale space-a response must not have overwritten space-project's already-current
    // state.
    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe('space-project')
    expect(useSpaceStore.getState().assetAssociations).toEqual([
      {
        assetType: 'KNOWLEDGE_LIBRARY',
        assetId: 'lib-1',
        name: 'Rechtsquellen',
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
  })

  // #783 review nit 1: a failed load must leave assetAssociationsSpaceId null, not silently
  // read as "this space has no associations" (which callers would otherwise render as "every
  // readable library" - the exact false claim #782 fixed).
  it('leaves assetAssociationsSpaceId null when the load fails', async () => {
    mockGetSpaceAssetAssociations.mockRejectedValueOnce(new Error('Netzwerkfehler'))

    await useSpaceStore.getState().loadAssetAssociations('space-project')

    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBeNull()
    expect(useSpaceStore.getState().hasAssetAssociations).toBe(false)
    expect(useSpaceStore.getState().hasKnowledge).toBe(false)
    expect(useSpaceStore.getState().hasReadableKnowledge).toBe(false)
  })

  it('associates a library and reloads the association list', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-project')
    mockGetSpaceAssetAssociations.mockClear()
    await useSpaceStore.getState().associateAsset('space-project', 'KNOWLEDGE_LIBRARY', 'lib-2')

    expect(mockAssociateSpaceAsset).toHaveBeenCalledWith(
      'space-project',
      'KNOWLEDGE_LIBRARY',
      'lib-2',
    )
    expect(mockGetSpaceAssetAssociations).toHaveBeenCalledWith('space-project')
  })

  // The tab "Inhalte" changes one association at a time; its list must not empty in between.
  it('keeps the current associations while refreshing after its own change', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-project')
    const before = useSpaceStore.getState().assetAssociations
    type Answer = Awaited<ReturnType<typeof mockGetSpaceAssetAssociations>>
    let resolveReload: (value: Answer) => void = () => {}
    mockGetSpaceAssetAssociations.mockImplementationOnce(
      () => new Promise((resolve) => (resolveReload = resolve)),
    )

    const detaching = useSpaceStore.getState().detachAsset('space-project', 'lib-1')
    await vi.waitFor(() => expect(mockGetSpaceAssetAssociations).toHaveBeenCalledTimes(2))

    expect(useSpaceStore.getState().assetAssociations).toBe(before)
    expect(useSpaceStore.getState().isLoadingAssetAssociations).toBe(false)
    resolveReload({
      hasAssociations: false,
      hasUnreadableAssociations: false,
      hasKnowledge: false,
      hasReadableKnowledge: false,
      items: [],
    })
    await detaching
    expect(useSpaceStore.getState().assetAssociations).toEqual([])
  })

  // A change in a space left behind (an in-flight request, an undo) must not replace the list now
  // on display with that space's.
  it('leaves the associations of the space on display alone after a change in another', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-shown')
    mockGetSpaceAssetAssociations.mockClear()

    await useSpaceStore.getState().detachAsset('space-left', 'lib-1')
    await useSpaceStore.getState().associateAsset('space-left', 'KNOWLEDGE_LIBRARY', 'lib-1')

    expect(mockGetSpaceAssetAssociations).not.toHaveBeenCalled()
    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe('space-shown')
  })

  it('keeps the list and names the failed refresh after a successful change', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-project')
    const before = useSpaceStore.getState().assetAssociations
    mockGetSpaceAssetAssociations.mockRejectedValueOnce(new Error('Netzwerkfehler'))

    await useSpaceStore.getState().detachAsset('space-project', 'lib-1')

    expect(useSpaceStore.getState().assetAssociations).toBe(before)
    expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe('space-project')
    expect(useSpaceStore.getState().error).toBe(ASSOCIATIONS_NOT_REFRESHED)
  })

  it('detaches a library and reloads the association list', async () => {
    await useSpaceStore.getState().loadAssetAssociations('space-project')
    mockGetSpaceAssetAssociations.mockClear()
    await useSpaceStore.getState().detachAsset('space-project', 'lib-1')

    expect(mockDetachSpaceAsset).toHaveBeenCalledWith('space-project', 'lib-1')
    expect(mockGetSpaceAssetAssociations).toHaveBeenCalledWith('space-project')
  })

  // #543: archiveSelectedSpace is the way out of a space fk_chats_space makes permanently
  // undeletable - it must call the archive endpoint and refresh both the list and the selection,
  // never navigate away (the space stays reachable, only stops accepting new content).
  it('archives the selected space and refreshes the list and selection', async () => {
    await useSpaceStore.getState().archiveSelectedSpace('space-project')

    expect(mockArchiveSpace).toHaveBeenCalledWith('space-project')
    expect(useSpaceStore.getState().selectedSpaceId).toBe('space-project')
    // Only true if the store actually re-read the (now mutated) list after archiving - a store
    // that discarded the archived flag or never refreshed at all would still find the id, since
    // it was in the list from the start, but would not see archived: true.
    const refreshed = useSpaceStore.getState().spaces.find((space) => space.id === 'space-project')
    expect(refreshed?.archived).toBe(true)
  })

  // The list's member label ("nur Sie") must follow a membership change without a page reload.
  it('refreshes the list figures after adding and after removing a member', async () => {
    await useSpaceStore.getState().loadSpaces()

    await useSpaceStore.getState().addMember('space-personal', 'USER', 'u2', 'MEMBER')
    const afterAdd = useSpaceStore.getState().spaces.find((space) => space.id === 'space-personal')
    expect(afterAdd?.memberships).toEqual({ groupCount: 0, userCount: 2 })

    await useSpaceStore.getState().removeMember('space-personal', 'membership-u2')
    const afterRemove = useSpaceStore
      .getState()
      .spaces.find((space) => space.id === 'space-personal')
    expect(afterRemove?.memberships).toEqual({ groupCount: 0, userCount: 1 })
  })

  // #2205: a member change refreshes in place - neither a loading state nor an emptied list in
  // between, so the open tab neither flickers nor rebuilds its rows.
  it('refreshes members and space after a membership change without a loading state', async () => {
    await useSpaceStore.getState().selectSpace('space-personal')
    await useSpaceStore.getState().loadMembers('space-personal')
    const seen: Array<{ loading: boolean; members: number; space: boolean }> = []
    const unsubscribe = useSpaceStore.subscribe((state) =>
      seen.push({
        loading: state.isLoadingMembers || state.isLoadingDetails,
        members: state.members.length,
        space: state.selectedSpace !== null,
      }),
    )

    await useSpaceStore.getState().addMember('space-personal', 'USER', 'u2', 'MEMBER')
    await useSpaceStore.getState().removeMember('space-personal', 'membership-u2')
    unsubscribe()

    expect(seen.length).toBeGreaterThan(0)
    expect(seen.every((state) => !state.loading && state.members > 0 && state.space)).toBe(true)
  })

  describe('re-read after a membership change (#2205)', () => {
    const memberRow = (id: string): SpaceMemberResponse => ({
      id,
      subjectType: 'USER',
      subjectId: id,
      role: 'MEMBER',
      createdAt: '2026-03-01T10:00:00Z',
    })
    const httpError = (status: number) =>
      new Error(`HTTP ${status}`, { cause: { response: { status } } })

    beforeEach(async () => {
      await useSpaceStore.getState().selectSpace('space-project')
      await useSpaceStore.getState().loadMembers('space-project')
    })

    it('keeps the newer state when an older re-read answers last', async () => {
      const older = deferred<SpaceMemberResponse[]>()
      const newer = deferred<SpaceMemberResponse[]>()
      vi.mocked(listSpaceMembers)
        .mockReturnValueOnce(older.promise as never)
        .mockReturnValueOnce(newer.promise as never)

      const calls = vi.mocked(listSpaceMembers).mock.calls.length
      const first = useSpaceStore.getState().updateMemberRole('space-project', 'm-a', 'CURATOR')
      const second = useSpaceStore.getState().updateMemberRole('space-project', 'm-b', 'CURATOR')
      await vi.waitFor(() => expect(listSpaceMembers).toHaveBeenCalledTimes(calls + 2))
      newer.resolve([memberRow('after-second')])
      await second
      older.resolve([memberRow('after-first')])
      await first

      expect(useSpaceStore.getState().members.map((member) => member.id)).toEqual(['after-second'])
    })

    it('clears a previous refresh failure once a re-read succeeds', async () => {
      useSpaceStore.setState({ error: MEMBERS_NOT_REFRESHED })

      await useSpaceStore.getState().updateMemberRole('space-project', 'm-a', 'CURATOR')

      expect(useSpaceStore.getState().error).toBeNull()
    })

    it('reports a failed re-read only in the space it belongs to', async () => {
      let fail: (reason: Error) => void = () => {}
      vi.mocked(getSpace).mockReturnValueOnce(
        new Promise<never>((_, reject) => {
          fail = reject
        }),
      )
      const calls = vi.mocked(getSpace).mock.calls.length

      const change = useSpaceStore.getState().updateMemberRole('space-project', 'm-a', 'CURATOR')
      await vi.waitFor(() => expect(getSpace).toHaveBeenCalledTimes(calls + 1))
      useSpaceStore.setState({ selectedSpaceId: 'space-personal' })
      fail(httpError(500))
      await change

      expect(useSpaceStore.getState().error).toBeNull()
    })

    it('names a failed re-read of the shown space', async () => {
      vi.mocked(getSpace).mockRejectedValueOnce(httpError(500))

      await useSpaceStore.getState().updateMemberRole('space-project', 'm-a', 'CURATOR')

      expect(useSpaceStore.getState().error).toBe(MEMBERS_NOT_REFRESHED)
      expect(useSpaceStore.getState().selectedSpace).not.toBeNull()
    })

    it('drops the space when removing a membership cost the caller access', async () => {
      vi.mocked(getSpace).mockRejectedValueOnce(httpError(403))

      const result = await useSpaceStore.getState().removeMember('space-project', 'm-self')

      expect(result).toBe('accessLost')
      expect(useSpaceStore.getState().selectedSpace).toBeNull()
      expect(useSpaceStore.getState().error).not.toBe(MEMBERS_NOT_REFRESHED)
    })
  })

  // #575: loadSpaces is one of the explicitly named unguarded write paths (Issue #575) - a
  // response arriving after resetAllStores() must not resurrect the previous user's spaces into
  // the now-emptied store.
  it('a loadSpaces response arriving after a session reset does not resurrect spaces', async () => {
    const gate = deferred<(typeof initialSpaces)[number][]>()
    vi.mocked(getSpaces).mockReturnValueOnce(gate.promise as never)

    const loadPromise = useSpaceStore.getState().loadSpaces()
    resetAllStores()
    gate.resolve(mutableSpaces.map((space) => ({ ...space })))
    await loadPromise

    const state = useSpaceStore.getState()
    expect(state.spaces).toEqual([])
    expect(state.selectedSpaceId).toBeNull()
    expect(state.isLoadingList).toBe(false)
  })
})
