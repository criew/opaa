import { create } from 'zustand'
import type { GroupListResponse, GroupResponse } from '../types/api'
import {
  addGroupMember,
  createGroup,
  deleteGroup,
  getGroup,
  getGroups,
  removeGroupMember,
  updateGroup,
} from '../services/api'

interface GroupState {
  groups: GroupListResponse[]
  groupDetails: Record<string, GroupResponse>
  isLoading: boolean
  error: string | null
  reset: () => void
  loadGroups: () => Promise<void>
  loadGroupDetails: (groupId: string) => Promise<void>
  createNewGroup: (name: string, description: string) => Promise<void>
  renameGroup: (groupId: string, name: string, description: string) => Promise<void>
  deleteExistingGroup: (groupId: string) => Promise<void>
  addMember: (groupId: string, userId: string) => Promise<void>
  removeMember: (groupId: string, userId: string) => Promise<void>
}

function sortGroups(list: GroupListResponse[]): GroupListResponse[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name))
}

export const useGroupStore = create<GroupState>((set, get) => ({
  groups: [],
  groupDetails: {},
  isLoading: false,
  error: null,

  reset: () => set({ groups: [], groupDetails: {}, isLoading: false, error: null }),

  loadGroups: async () => {
    set({ isLoading: true, error: null })
    try {
      const groups = sortGroups(await getGroups())
      set({ groups, isLoading: false })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  loadGroupDetails: async (groupId: string) => {
    try {
      const group = await getGroup(groupId)
      set({ groupDetails: { ...get().groupDetails, [groupId]: group } })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Gruppendetails konnten nicht geladen werden'
      set({ error: message })
    }
  },

  createNewGroup: async (name, description) => {
    await createGroup(name, description)
    await get().loadGroups()
  },

  renameGroup: async (groupId, name, description) => {
    await updateGroup(groupId, name, description)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  deleteExistingGroup: async (groupId) => {
    await deleteGroup(groupId)
    const rest = { ...get().groupDetails }
    delete rest[groupId]
    set({ groupDetails: rest })
    await get().loadGroups()
  },

  addMember: async (groupId, userId) => {
    await addGroupMember(groupId, userId)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  removeMember: async (groupId, userId) => {
    await removeGroupMember(groupId, userId)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },
}))
