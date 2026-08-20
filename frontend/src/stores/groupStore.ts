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
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

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
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's groups into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const groups = sortGroups(await getGroups())
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ groups, isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  loadGroupDetails: async (groupId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const group = await getGroup(groupId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ groupDetails: { ...get().groupDetails, [groupId]: group } })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
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
    const sessionEpoch = currentSessionEpoch()
    await deleteGroup(groupId)
    // #575: loadGroups() below already guards its own write-back - this direct set() needs the
    // same guard, otherwise a logout in between still resurrects a stale groupDetails map into the
    // store reset() just cleared.
    if (isStaleSessionEpoch(sessionEpoch)) return
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
