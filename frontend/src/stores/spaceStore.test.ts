import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useSpaceStore } from './spaceStore'

const mockCreateSpace = vi.fn()
const mockArchiveSpace = vi.fn()

vi.mock('../services/api', () => ({
  getSpaces: vi.fn(async () => [
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
  ]),
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
    members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
  createSpace: (...args: unknown[]) => mockCreateSpace(...args),
  archiveSpace: (...args: unknown[]) => mockArchiveSpace(...args),
}))

describe('spaceStore', () => {
  beforeEach(() => {
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

    const id = await useSpaceStore.getState().createNewSpace('New Space', 'desc')
    expect(id).toBe('space-new')
    expect(mockCreateSpace).toHaveBeenCalledWith('New Space', 'desc')
    expect(useSpaceStore.getState().selectedSpaceId).toBe('space-new')
  })

  // #543: archiveSelectedSpace is the way out of a space fk_chats_space makes permanently
  // undeletable - it must call the archive endpoint and refresh both the list and the selection,
  // never navigate away (the space stays reachable, only stops accepting new content).
  it('archives the selected space and refreshes the list and selection', async () => {
    mockArchiveSpace.mockResolvedValueOnce({
      id: 'space-project',
      name: 'Engineering',
      description: 'Eng docs',
      isDefault: false,
      archived: true,
      visibility: 'PRIVATE',
      ownerId: 'u1',
      memberCount: 2,
      userRole: 'ADMIN',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
      members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    })

    await useSpaceStore.getState().archiveSelectedSpace('space-project')

    expect(mockArchiveSpace).toHaveBeenCalledWith('space-project')
    expect(useSpaceStore.getState().selectedSpaceId).toBe('space-project')
    expect(useSpaceStore.getState().spaces.map((space) => space.id)).toContain('space-project')
  })
})
