import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useWorkspaceStore } from './workspaceStore'

vi.mock('../services/api', () => ({
  getWorkspaces: vi.fn(async () => [
    {
      id: 'ws-shared',
      name: 'Engineering',
      description: 'Eng docs',
      type: 'SHARED',
      memberCount: 2,
      userRole: 'ADMIN',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'ws-personal',
      name: 'My Documents',
      description: 'Private',
      type: 'PERSONAL',
      memberCount: 1,
      userRole: 'OWNER',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ]),
  getWorkspace: vi.fn(async (workspaceId: string) => ({
    id: workspaceId,
    name: 'My Documents',
    description: 'Private',
    type: 'PERSONAL',
    ownerId: 'u1',
    memberCount: 1,
    userRole: 'OWNER',
    roleCounts: { VIEWER: 0, EDITOR: 0, ADMIN: 0, OWNER: 1 },
    members: [{ userId: 'u1', role: 'OWNER', createdAt: '2026-03-01T10:00:00Z' }],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  })),
  getWorkspaceDocuments: vi.fn(async () => []),
}))

describe('workspaceStore', () => {
  beforeEach(() => {
    useWorkspaceStore.setState({
      workspaces: [],
      selectedWorkspaceId: null,
      selectedWorkspace: null,
      selectedWorkspaceDocuments: [],
      chatFilterWorkspaceId: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    })
  })

  it('sorts personal workspace first', async () => {
    await useWorkspaceStore.getState().loadWorkspaces()
    const names = useWorkspaceStore.getState().workspaces.map((workspace) => workspace.name)
    expect(names[0]).toBe('My Documents')
  })

  it('updates chat filter selection', () => {
    useWorkspaceStore.getState().setChatFilterWorkspaceId('ws-personal')
    expect(useWorkspaceStore.getState().chatFilterWorkspaceId).toBe('ws-personal')
  })
})
