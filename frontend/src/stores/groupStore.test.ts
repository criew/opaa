import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useGroupStore } from './groupStore'

const mockCreateGroup = vi.fn()
const mockAddGroupMember = vi.fn()
const mockRemoveGroupMember = vi.fn()
const mockDeleteGroup = vi.fn()
const mockUpdateGroup = vi.fn()

vi.mock('../services/api', () => ({
  getGroups: vi.fn(async () => [
    {
      id: 'group-b',
      name: 'Team B',
      description: null,
      kind: 'AD_HOC',
      externalId: null,
      parentGroupId: null,
      memberCount: 1,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'group-a',
      name: 'Team A',
      description: null,
      kind: 'AD_HOC',
      externalId: null,
      parentGroupId: null,
      memberCount: 2,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ]),
  getGroup: vi.fn(async (groupId: string) => ({
    id: groupId,
    name: 'Team A',
    description: null,
    kind: 'AD_HOC',
    externalId: null,
    parentGroupId: null,
    memberCount: 1,
    members: [{ userId: 'u1', displayName: 'User 1', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
  createGroup: (...args: unknown[]) => mockCreateGroup(...args),
  updateGroup: (...args: unknown[]) => mockUpdateGroup(...args),
  deleteGroup: (...args: unknown[]) => mockDeleteGroup(...args),
  addGroupMember: (...args: unknown[]) => mockAddGroupMember(...args),
  removeGroupMember: (...args: unknown[]) => mockRemoveGroupMember(...args),
}))

describe('groupStore', () => {
  beforeEach(() => {
    useGroupStore.setState({ groups: [], groupDetails: {}, isLoading: false, error: null })
    mockCreateGroup.mockReset()
    mockAddGroupMember.mockReset()
    mockRemoveGroupMember.mockReset()
    mockDeleteGroup.mockReset()
    mockUpdateGroup.mockReset()
  })

  it('sorts groups alphabetically', async () => {
    await useGroupStore.getState().loadGroups()
    const names = useGroupStore.getState().groups.map((g) => g.name)
    expect(names).toEqual(['Team A', 'Team B'])
  })

  it('creates a new group and reloads the list', async () => {
    mockCreateGroup.mockResolvedValueOnce({})
    await useGroupStore.getState().createNewGroup('Team C', 'desc')
    expect(mockCreateGroup).toHaveBeenCalledWith('Team C', 'desc')
    expect(useGroupStore.getState().groups.length).toBeGreaterThan(0)
  })

  it('loads group details on demand', async () => {
    await useGroupStore.getState().loadGroupDetails('group-a')
    expect(useGroupStore.getState().groupDetails['group-a'].members).toHaveLength(1)
  })

  it('adds a member and refreshes list and details', async () => {
    mockAddGroupMember.mockResolvedValueOnce({})
    await useGroupStore.getState().addMember('group-a', 'u2')
    expect(mockAddGroupMember).toHaveBeenCalledWith('group-a', 'u2')
    expect(useGroupStore.getState().groupDetails['group-a']).toBeDefined()
  })

  it('removes a member and refreshes list and details', async () => {
    mockRemoveGroupMember.mockResolvedValueOnce(undefined)
    await useGroupStore.getState().removeMember('group-a', 'u1')
    expect(mockRemoveGroupMember).toHaveBeenCalledWith('group-a', 'u1')
  })

  it('deletes a group and clears its cached details', async () => {
    useGroupStore.setState({
      groupDetails: {
        'group-a': {
          id: 'group-a',
          name: 'Team A',
          kind: 'AD_HOC',
          memberCount: 0,
          members: [],
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      },
    })
    mockDeleteGroup.mockResolvedValueOnce(undefined)

    await useGroupStore.getState().deleteExistingGroup('group-a')

    expect(mockDeleteGroup).toHaveBeenCalledWith('group-a')
    expect(useGroupStore.getState().groupDetails['group-a']).toBeUndefined()
  })

  it('renames a group and refreshes list and details', async () => {
    mockUpdateGroup.mockResolvedValueOnce({})
    await useGroupStore.getState().renameGroup('group-a', 'Renamed', 'desc')
    expect(mockUpdateGroup).toHaveBeenCalledWith('group-a', 'Renamed', 'desc')
  })
})
