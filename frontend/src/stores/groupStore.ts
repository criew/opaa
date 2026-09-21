import { create } from 'zustand'
import type { GroupListResponse, GroupResponse } from '../types/api'
import {
  addGroupMember,
  appointGroupSteward,
  createGroup,
  deleteGroup,
  dismissGroupSteward,
  getGroup,
  getGroups,
  getMyStewardedGroups,
  removeGroupMember,
  setGroupProtection,
  setGroupRelease,
  updateGroup,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/**
 * Woher die Liste kommt: alle Gruppen der Organisation (Administration) oder die eigenen
 * Verantwortlichkeiten (#1814). Die Quelle wird beim Laden gesetzt, damit jedes Nachladen nach
 * einer Änderung dieselbe Liste holt wie die Seite, die sie anzeigt.
 */
export type GroupListSource = 'ADMIN' | 'STEWARDED'

interface GroupState {
  groups: GroupListResponse[]
  groupDetails: Record<string, GroupResponse>
  source: GroupListSource
  isLoading: boolean
  error: string | null
  reset: () => void
  loadGroups: (source?: GroupListSource) => Promise<void>
  loadGroupDetails: (groupId: string) => Promise<void>
  createNewGroup: (name: string, description: string) => Promise<void>
  renameGroup: (groupId: string, name: string, description: string) => Promise<void>
  deleteExistingGroup: (groupId: string) => Promise<void>
  addMember: (groupId: string, userId: string) => Promise<void>
  removeMember: (groupId: string, userId: string) => Promise<void>
  appointSteward: (groupId: string, userId: string) => Promise<void>
  dismissSteward: (groupId: string, userId: string) => Promise<void>
  changeRelease: (groupId: string, releasedForUse: boolean) => Promise<void>
  changeProtection: (groupId: string, protectedGroup: boolean) => Promise<void>
}

function sortGroups(list: GroupListResponse[]): GroupListResponse[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name))
}

export const useGroupStore = create<GroupState>((set, get) => ({
  groups: [],
  groupDetails: {},
  source: 'ADMIN',
  isLoading: false,
  error: null,

  reset: () =>
    set({ groups: [], groupDetails: {}, source: 'ADMIN', isLoading: false, error: null }),

  loadGroups: async (source) => {
    const nextSource = source ?? get().source
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's groups into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null, source: nextSource })
    try {
      const loaded = nextSource === 'STEWARDED' ? await getMyStewardedGroups() : await getGroups()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ groups: sortGroups(loaded), isLoading: false })
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

  appointSteward: async (groupId, userId) => {
    await appointGroupSteward(groupId, userId)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  /**
   * Verwirft den Detailstand, statt ihn nachzuladen: Wer sich selbst entlassen hat, darf die Gruppe
   * nicht mehr lesen, und das Nachladen meldete dann einen Fehler nach einer gelungenen Handlung.
   * Die Karte lädt die Details von sich aus nach, solange sie sie noch sehen darf.
   */
  dismissSteward: async (groupId, userId) => {
    const sessionEpoch = currentSessionEpoch()
    await dismissGroupSteward(groupId, userId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const rest = { ...get().groupDetails }
    delete rest[groupId]
    set({ groupDetails: rest })
    await get().loadGroups()
  },

  changeRelease: async (groupId, releasedForUse) => {
    await setGroupRelease(groupId, releasedForUse)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  changeProtection: async (groupId, protectedGroup) => {
    await setGroupProtection(groupId, protectedGroup)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },
}))
