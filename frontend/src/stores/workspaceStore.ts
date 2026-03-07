import { create } from 'zustand'
import type {
  WorkspaceDocumentResponse,
  WorkspaceListResponse,
  WorkspaceResponse,
} from '../types/api'
import { getWorkspace, getWorkspaceDocuments, getWorkspaces } from '../services/api'

interface WorkspaceState {
  workspaces: WorkspaceListResponse[]
  selectedWorkspaceId: string | null
  selectedWorkspace: WorkspaceResponse | null
  selectedWorkspaceDocuments: WorkspaceDocumentResponse[]
  chatFilterWorkspaceId: string | null
  isLoadingList: boolean
  isLoadingDetails: boolean
  error: string | null
  loadWorkspaces: () => Promise<void>
  selectWorkspace: (workspaceId: string) => Promise<void>
  setChatFilterWorkspaceId: (workspaceId: string | null) => void
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
  chatFilterWorkspaceId: null,
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

  setChatFilterWorkspaceId: (workspaceId: string | null) =>
    set({ chatFilterWorkspaceId: workspaceId }),
}))
