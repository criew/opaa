import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useSpaceStore } from './spaceStore'

const mockCreateSpace = vi.fn()

vi.mock('../services/api', () => ({
  getSpaces: vi.fn(async () => [
    {
      id: 'space-project',
      name: 'Engineering',
      description: 'Eng docs',
      kind: 'PROJECT',
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
      kind: 'PERSONAL',
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
    kind: 'PERSONAL',
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
}))

describe('spaceStore', () => {
  beforeEach(() => {
    useSpaceStore.setState({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      chatFilterSpaceIds: [],
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('sorts personal space first', async () => {
    await useSpaceStore.getState().loadSpaces()
    const names = useSpaceStore.getState().spaces.map((space) => space.name)
    expect(names[0]).toBe('Meine Dokumente')
  })

  it('updates chat filter selection', () => {
    useSpaceStore.getState().setChatFilterSpaceIds(['space-personal', 'space-project'])
    expect(useSpaceStore.getState().chatFilterSpaceIds).toEqual(['space-personal', 'space-project'])
  })

  it('creates a new space and selects it', async () => {
    mockCreateSpace.mockResolvedValueOnce({
      id: 'space-new',
      name: 'New Space',
      description: 'desc',
      kind: 'PROJECT',
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
})
