import { create } from 'zustand'
import type {
  WorkspaceDocumentResponse,
  WorkspaceListResponse,
  WorkspaceRole,
  WorkspaceResponse,
} from '../types/api'
import {
  addWorkspaceMember,
  createWorkspace,
  deleteWorkspace,
  getWorkspace,
  getWorkspaceDocuments,
  getWorkspaces,
  removeWorkspaceMember,
  transferWorkspaceOwnership,
  updateWorkspaceDetails,
  updateWorkspaceMemberRole,
} from '../services/api'

interface WorkspaceState {
  workspaces: WorkspaceListResponse[]
  selectedWorkspaceId: string | null
  selectedWorkspace: WorkspaceResponse | null
  selectedWorkspaceDocuments: WorkspaceDocumentResponse[]
  chatFilterWorkspaceIds: string[]
  isLoadingList: boolean
  isLoadingDetails: boolean
  error: string | null
  loadWorkspaces: () => Promise<void>
  selectWorkspace: (workspaceId: string) => Promise<void>
  setChatFilterWorkspaceIds: (workspaceIds: string[]) => void
  addMember: (workspaceId: string, userId: string, role?: WorkspaceRole) => Promise<void>
  updateMemberRole: (workspaceId: string, userId: string, role: WorkspaceRole) => Promise<void>
  removeMember: (workspaceId: string, userId: string) => Promise<void>
  transferOwnership: (workspaceId: string, userId: string) => Promise<void>
  updateDetails: (workspaceId: string, name: string, description: string) => Promise<void>
  deleteSelectedWorkspace: (workspaceId: string) => Promise<void>
  createNewWorkspace: (name: string, description: string) => Promise<string>
}

function sortWorkspaces(list: WorkspaceListResponse[]): WorkspaceListResponse[] {
  return [...list].sort((a, b) => {
    if (a.type === 'PERSONAL' && b.type !== 'PERSONAL') return -1
    if (a.type !== 'PERSONAL' && b.type === 'PERSONAL') return 1
    return a.name.localeCompare(b.name)
  })
}

export const useWorkspaceStore = create<WorkspaceState>((set, get) => ({
  workspaces: [],
  selectedWorkspaceId: null,
  selectedWorkspace: null,
  selectedWorkspaceDocuments: [],
  chatFilterWorkspaceIds: [],
  isLoadingList: false,
  isLoadingDetails: false,
  error: null,

  loadWorkspaces: async () => {
    set({ isLoadingList: true, error: null })
    try {
      const workspaces = sortWorkspaces(await getWorkspaces())
      const currentSelected = get().selectedWorkspaceId
      const nextSelected =
        currentSelected && workspaces.some((workspace) => workspace.id === currentSelected)
          ? currentSelected
          : (workspaces[0]?.id ?? null)
      set({
        workspaces,
        selectedWorkspaceId: nextSelected,
        isLoadingList: false,
      })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load workspaces'
      set({ error: message, isLoadingList: false })
    }
  },

  selectWorkspace: async (workspaceId: string) => {
    set({ selectedWorkspaceId: workspaceId, isLoadingDetails: true, error: null })
    try {
      const [workspace, documents] = await Promise.all([
        getWorkspace(workspaceId),
        getWorkspaceDocuments(workspaceId).catch(() => []),
      ])
      set({
        selectedWorkspace: workspace,
        selectedWorkspaceDocuments: documents,
        isLoadingDetails: false,
      })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load workspace details'
      set({
        error: message,
        selectedWorkspace: null,
        selectedWorkspaceDocuments: [],
        isLoadingDetails: false,
      })
    }
  },

  setChatFilterWorkspaceIds: (workspaceIds: string[]) =>
    set({ chatFilterWorkspaceIds: workspaceIds }),

  addMember: async (workspaceId, userId, role) => {
    await addWorkspaceMember(workspaceId, userId, role)
    await get().selectWorkspace(workspaceId)
  },

  updateMemberRole: async (workspaceId, userId, role) => {
    await updateWorkspaceMemberRole(workspaceId, userId, role)
    await get().selectWorkspace(workspaceId)
  },

  removeMember: async (workspaceId, userId) => {
    await removeWorkspaceMember(workspaceId, userId)
    await get().selectWorkspace(workspaceId)
  },

  transferOwnership: async (workspaceId, userId) => {
    await transferWorkspaceOwnership(workspaceId, userId)
    await get().selectWorkspace(workspaceId)
  },

  updateDetails: async (workspaceId, name, description) => {
    await updateWorkspaceDetails(workspaceId, name, description)
    await Promise.all([get().loadWorkspaces(), get().selectWorkspace(workspaceId)])
  },

  deleteSelectedWorkspace: async (workspaceId) => {
    await deleteWorkspace(workspaceId)
    await get().loadWorkspaces()
    const fallbackWorkspaceId = get().workspaces[0]?.id
    if (fallbackWorkspaceId) {
      await get().selectWorkspace(fallbackWorkspaceId)
    } else {
      set({ selectedWorkspace: null, selectedWorkspaceDocuments: [], selectedWorkspaceId: null })
    }
  },

  createNewWorkspace: async (name, description) => {
    const workspace = await createWorkspace(name, description)
    await get().loadWorkspaces()
    await get().selectWorkspace(workspace.id)
    return workspace.id
  },
}))
