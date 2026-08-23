import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useSpaceStore } from './spaceStore'
import { resetAllStores } from './resettableStores'
import { getSpaces } from '../services/api'

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
    visibility: 'PRIVATE',
    memberCount: 2,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-personal',
    name: 'Meine Dokumente',
    description: 'Private',
    isDefault: true,
    archived: false,
    visibility: 'PRIVATE',
    memberCount: 1,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

let mutableSpaces = initialSpaces.map((space) => ({ ...space }))

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

vi.mock('../services/api', () => ({
  getSpaces: vi.fn(async () => mutableSpaces.map((space) => ({ ...space }))),
  getSpace: vi.fn(async (spaceId: string) => ({
    id: spaceId,
    name: 'Meine Dokumente',
    description: 'Private',
    isDefault: true,
    archived: false,
    visibility: 'PRIVATE',
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
  createSpace: (...args: unknown[]) => mockCreateSpace(...args),
  archiveSpace: (...args: [string]) => mockArchiveSpace(...args),
  getSpaceLibraryAssociations: (spaceId: string) => mockGetSpaceLibraryAssociations(spaceId),
  associateSpaceLibrary: (spaceId: string, libraryId: string) =>
    mockAssociateSpaceLibrary(spaceId, libraryId),
  detachSpaceLibrary: (spaceId: string, libraryId: string) =>
    mockDetachSpaceLibrary(spaceId, libraryId),
}))

const mockGetSpaceLibraryAssociations = vi.fn(async (spaceId: string) => {
  void spaceId
  return {
    hasAssociations: true,
    items: [
      {
        libraryId: 'lib-1',
        libraryName: 'Rechtsquellen',
        readableByCaller: true,
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
  }
})
const mockAssociateSpaceLibrary = vi.fn(async (spaceId: string, libraryId: string) => {
  void spaceId
  void libraryId
  return {}
})
const mockDetachSpaceLibrary = vi.fn(async (spaceId: string, libraryId: string) => {
  void spaceId
  void libraryId
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
    expect(names[0]).toBe('Meine Dokumente')
  })

  it('creates a new space and selects it', async () => {
    mockCreateSpace.mockResolvedValueOnce({
      id: 'space-new',
      name: 'New Space',
      description: 'desc',
      isDefault: false,
      visibility: 'PRIVATE',
      ownerId: 'u1',
      memberCount: 1,
      userRole: 'ADMIN',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    })

    const id = await useSpaceStore.getState().createNewSpace('New Space', 'desc', 'DISCOVERABLE')
    expect(id).toBe('space-new')
    expect(mockCreateSpace).toHaveBeenCalledWith('New Space', 'desc', 'DISCOVERABLE', undefined)
    expect(useSpaceStore.getState().selectedSpaceId).toBe('space-new')
  })

  it('passes libraryIds through to createSpace when provided (#686)', async () => {
    mockCreateSpace.mockResolvedValueOnce({
      id: 'space-new',
      name: 'New Space',
      description: 'desc',
      isDefault: false,
      visibility: 'PRIVATE',
      ownerId: 'u1',
      memberCount: 1,
      userRole: 'ADMIN',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    })

    await useSpaceStore
      .getState()
      .createNewSpace('New Space', 'desc', 'DISCOVERABLE', ['lib-1', 'lib-2'])

    expect(mockCreateSpace).toHaveBeenCalledWith('New Space', 'desc', 'DISCOVERABLE', [
      'lib-1',
      'lib-2',
    ])
  })

  // #203: library associations - loaded on demand, not part of selectSpace, since only pages that
  // actually show them (SpacePage, SpaceManagementPage) need the extra request.
  it('loads library associations for a space', async () => {
    await useSpaceStore.getState().loadLibraryAssociations('space-project')

    expect(mockGetSpaceLibraryAssociations).toHaveBeenCalledWith('space-project')
    expect(useSpaceStore.getState().libraryAssociations).toEqual([
      {
        libraryId: 'lib-1',
        libraryName: 'Rechtsquellen',
        readableByCaller: true,
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
    expect(useSpaceStore.getState().hasLibraryAssociations).toBe(true)
    // #783 review finding 1: callers must be able to tell which space this data actually
    // describes before trusting it.
    expect(useSpaceStore.getState().libraryAssociationsSpaceId).toBe('space-project')
  })

  // #783 review finding 1 (🔴): a response for a space call already superseded by a newer one
  // must not win the race and overwrite the newer call's state - reproduces the bug report's "two
  // quick switches, the stale response arrives last" scenario at the store level.
  it('ignores a stale response for a space no longer being loaded', async () => {
    const first = deferred<{
      hasAssociations: boolean
      items: {
        libraryId: string
        libraryName: string
        readableByCaller: boolean
        createdByUserId: string
        createdAt: string
      }[]
    }>()
    mockGetSpaceLibraryAssociations.mockImplementationOnce(() => first.promise)

    // Started first (space left behind), but resolves last - the real-world case a plain
    // .mockResolvedValueOnce ordering can't reproduce, since here the *second* call's own request
    // settles before the *first* call's deferred response ever arrives.
    const firstCall = useSpaceStore.getState().loadLibraryAssociations('space-a')
    const secondCall = useSpaceStore.getState().loadLibraryAssociations('space-project')
    await secondCall

    expect(useSpaceStore.getState().libraryAssociationsSpaceId).toBe('space-project')

    first.resolve({
      hasAssociations: true,
      items: [
        {
          libraryId: 'lib-a',
          libraryName: 'A',
          readableByCaller: true,
          createdByUserId: 'u1',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
    })
    await firstCall

    // The now-stale space-a response must not have overwritten space-project's already-current
    // state.
    expect(useSpaceStore.getState().libraryAssociationsSpaceId).toBe('space-project')
    expect(useSpaceStore.getState().libraryAssociations).toEqual([
      {
        libraryId: 'lib-1',
        libraryName: 'Rechtsquellen',
        readableByCaller: true,
        createdByUserId: 'u1',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
  })

  // #783 review nit 1: a failed load must leave libraryAssociationsSpaceId null, not silently
  // read as "this space has no associations" (which callers would otherwise render as "every
  // readable library" - the exact false claim #782 fixed).
  it('leaves libraryAssociationsSpaceId null when the load fails', async () => {
    mockGetSpaceLibraryAssociations.mockRejectedValueOnce(new Error('Netzwerkfehler'))

    await useSpaceStore.getState().loadLibraryAssociations('space-project')

    expect(useSpaceStore.getState().libraryAssociationsSpaceId).toBeNull()
    expect(useSpaceStore.getState().hasLibraryAssociations).toBe(false)
  })

  it('associates a library and reloads the association list', async () => {
    await useSpaceStore.getState().associateLibrary('space-project', 'lib-2')

    expect(mockAssociateSpaceLibrary).toHaveBeenCalledWith('space-project', 'lib-2')
    expect(mockGetSpaceLibraryAssociations).toHaveBeenCalledWith('space-project')
  })

  it('detaches a library and reloads the association list', async () => {
    await useSpaceStore.getState().detachLibrary('space-project', 'lib-1')

    expect(mockDetachSpaceLibrary).toHaveBeenCalledWith('space-project', 'lib-1')
    expect(mockGetSpaceLibraryAssociations).toHaveBeenCalledWith('space-project')
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
