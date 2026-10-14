import { create } from 'zustand'
import type { GroupListResponse, GroupMemberResponse, GroupResponse } from '@/shared/api/types'
import {
  addGroupMember,
  appointGroupSteward,
  createGroup,
  deleteGroup,
  dismissGroupSteward,
  getGroup,
  getGroups,
  getMyStewardedGroups,
  listGroupMembers,
  removeGroupMember,
  setGroupDeactivation,
  setGroupProtection,
  setGroupRelease,
  updateGroup,
} from '../api/groupApi'
import type { GroupCreationOptions } from '../api/groupApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { registerSessionStore } from '@/shared/session/resettableStores'

/**
 * Where the list comes from: all groups of the organization, the own responsibilities
 * or none - the latter as long as no page shows this list, e.g. the group administration with
 * its own paged list. The source is set when loading, so that every reload after
 * a change fetches the same list as the page showing it, and with „keine“ nothing at all.
 */
export type GroupListSource = 'ADMIN' | 'STEWARDED' | 'NONE'

interface GroupState {
  groups: GroupListResponse[]
  groupDetails: Record<string, GroupResponse>
  /**
   * Member lists that were explicitly retrieved via {@link loadGroupMembers}. No
   * other path fills or renews them: for the system administration every retrieval is an
   * audit event.
   */
  memberLists: Record<string, GroupMemberResponse[]>
  source: GroupListSource
  isLoading: boolean
  error: string | null
  reset: () => void
  loadGroups: (source?: GroupListSource) => Promise<void>
  loadGroupDetails: (groupId: string) => Promise<void>
  loadGroupMembers: (groupId: string) => Promise<void>
  createNewGroup: (
    name: string,
    description: string,
    options?: GroupCreationOptions,
  ) => Promise<void>
  renameGroup: (groupId: string, name: string, description: string) => Promise<void>
  deleteExistingGroup: (groupId: string) => Promise<void>
  addMember: (groupId: string, userId: string) => Promise<void>
  removeMember: (groupId: string, userId: string) => Promise<void>
  appointSteward: (groupId: string, userId: string) => Promise<void>
  dismissSteward: (groupId: string, userId: string) => Promise<void>
  changeRelease: (groupId: string, releasedForUse: boolean) => Promise<void>
  changeProtection: (groupId: string, protectedGroup: boolean) => Promise<void>
  changeDeactivation: (groupId: string, deactivated: boolean) => Promise<void>
}

function sortGroups(list: GroupListResponse[]): GroupListResponse[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name))
}

export const useGroupStore = create<GroupState>((set, get) => ({
  groups: [],
  groupDetails: {},
  memberLists: {},
  source: 'NONE',
  isLoading: false,
  error: null,

  reset: () =>
    set({
      groups: [],
      groupDetails: {},
      memberLists: {},
      source: 'NONE',
      isLoading: false,
      error: null,
    }),

  loadGroups: async (source) => {
    const nextSource = source ?? get().source
    // Captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's groups into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    if (nextSource === 'NONE') {
      set({ source: nextSource })
      return
    }
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

  loadGroupMembers: async (groupId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const members = await listGroupMembers(groupId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ memberLists: { ...get().memberLists, [groupId]: members } })
    } catch (err) {
      // The caller shows the reason where the list was asked for; a page-wide error would sit on
      // a page that no longer renders it.
      if (isStaleSessionEpoch(sessionEpoch)) return
      throw err
    }
  },

  createNewGroup: async (name, description, options) => {
    await createGroup(name, description, options)
    await get().loadGroups()
  },

  renameGroup: async (groupId, name, description) => {
    await updateGroup(groupId, name, description)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  deleteExistingGroup: async (groupId) => {
    const sessionEpoch = currentSessionEpoch()
    await deleteGroup(groupId)
    // loadGroups() below already guards its own write-back - this direct set() needs the
    // same guard, otherwise a logout in between still resurrects a stale groupDetails map into the
    // store reset() just cleared.
    if (isStaleSessionEpoch(sessionEpoch)) return
    const rest = { ...get().groupDetails }
    delete rest[groupId]
    const lists = { ...get().memberLists }
    delete lists[groupId]
    set({ groupDetails: rest, memberLists: lists })
    await get().loadGroups()
  },

  // A loaded member list is carried forward from the answer rather than fetched again - a new
  // retrieval would be a new audit event nobody asked for.
  addMember: async (groupId, userId) => {
    const sessionEpoch = currentSessionEpoch()
    const member = await addGroupMember(groupId, userId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const loaded = get().memberLists[groupId]
    if (loaded) set({ memberLists: { ...get().memberLists, [groupId]: [...loaded, member] } })
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  removeMember: async (groupId, userId) => {
    const sessionEpoch = currentSessionEpoch()
    await removeGroupMember(groupId, userId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const loaded = get().memberLists[groupId]
    if (loaded) {
      set({
        memberLists: {
          ...get().memberLists,
          [groupId]: loaded.filter((member) => member.userId !== userId),
        },
      })
    }
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  appointSteward: async (groupId, userId) => {
    await appointGroupSteward(groupId, userId)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },

  /**
   * Discards the detail state instead of reloading it: whoever has dismissed themselves may no
   * longer read the group, and the reload would then report an error after a successful action.
   * The card reloads the details by itself as long as it may still see them.
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

  changeDeactivation: async (groupId, deactivated) => {
    await setGroupDeactivation(groupId, deactivated)
    await Promise.all([get().loadGroups(), get().loadGroupDetails(groupId)])
  },
}))

registerSessionStore(useGroupStore)

/**
 * The known members of a group: from the details, where the caller sees them anyway,
 * otherwise from an explicitly retrieved list; `undefined` as long as none is available.
 */
export function selectGroupMembers(groupId: string) {
  return (state: GroupState): GroupMemberResponse[] | undefined =>
    state.groupDetails[groupId]?.members ?? state.memberLists[groupId]
}
