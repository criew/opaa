import { create } from 'zustand'
import type {
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRole,
  SpaceResponse,
  SpaceVisibility,
} from '../types/api'
import {
  addSpaceMember,
  archiveSpace,
  createSpace,
  deleteSpace,
  getSpace,
  getSpaces,
  listSpaceMembers,
  removeSpaceMember,
  transferSpaceOwnership,
  updateSpaceDetails,
  updateSpaceMemberRole,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

interface SpaceState {
  spaces: SpaceListResponse[]
  selectedSpaceId: string | null
  selectedSpace: SpaceResponse | null
  isLoadingList: boolean
  isLoadingDetails: boolean
  error: string | null
  // #144: SpaceResponse no longer carries the full member list - it is loaded separately, and only
  // reachable for ADMIN, owner and system admins (a 403 for anyone else leaves members empty).
  members: SpaceMemberResponse[]
  isLoadingMembers: boolean
  reset: () => void
  loadSpaces: () => Promise<void>
  selectSpace: (spaceId: string) => Promise<void>
  loadMembers: (spaceId: string) => Promise<void>
  addMember: (spaceId: string, userId: string, role?: SpaceRole) => Promise<void>
  updateMemberRole: (spaceId: string, userId: string, role: SpaceRole) => Promise<void>
  removeMember: (spaceId: string, userId: string) => Promise<void>
  transferOwnership: (spaceId: string, userId: string) => Promise<void>
  updateDetails: (
    spaceId: string,
    name: string,
    description: string,
    visibility?: SpaceVisibility,
  ) => Promise<void>
  deleteSelectedSpace: (spaceId: string) => Promise<void>
  archiveSelectedSpace: (spaceId: string) => Promise<void>
  createNewSpace: (
    name: string,
    description: string,
    visibility?: SpaceVisibility,
  ) => Promise<string>
}

function sortSpaces(list: SpaceListResponse[]): SpaceListResponse[] {
  return [...list].sort((a, b) => {
    if (a.isDefault && !b.isDefault) return -1
    if (!a.isDefault && b.isDefault) return 1
    return a.name.localeCompare(b.name)
  })
}

export const useSpaceStore = create<SpaceState>((set, get) => ({
  spaces: [],
  selectedSpaceId: null,
  selectedSpace: null,
  isLoadingList: false,
  isLoadingDetails: false,
  error: null,
  members: [],
  isLoadingMembers: false,

  reset: () =>
    set({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
      members: [],
      isLoadingMembers: false,
    }),

  loadSpaces: async () => {
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's spaces into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoadingList: true, error: null })
    try {
      const spaces = sortSpaces(await getSpaces())
      if (isStaleSessionEpoch(sessionEpoch)) return
      const currentSelected = get().selectedSpaceId
      const nextSelected =
        currentSelected && spaces.some((space) => space.id === currentSelected)
          ? currentSelected
          : (spaces[0]?.id ?? null)
      set({
        spaces,
        selectedSpaceId: nextSelected,
        isLoadingList: false,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Spaces konnten nicht geladen werden'
      set({ error: message, isLoadingList: false })
    }
  },

  selectSpace: async (spaceId: string) => {
    const sessionEpoch = currentSessionEpoch()
    // #144: members belongs to whichever space was selected before - clearing it here prevents it
    // from briefly appearing to belong to the newly selected space while the new list (or a 403
    // for a non-admin) is still in flight.
    set({ selectedSpaceId: spaceId, isLoadingDetails: true, error: null, members: [] })
    try {
      const space = await getSpace(spaceId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        selectedSpace: space,
        isLoadingDetails: false,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Space-Details konnten nicht geladen werden'
      set({
        error: message,
        selectedSpace: null,
        isLoadingDetails: false,
      })
    }
  },

  // #144: only ADMIN, the owner and system admins may call this - listSpaceMembers already turns
  // a 403 for anyone else into a silent empty list (the caller already knows they lack the role),
  // so any error still reaching this catch is a real failure (network, 404, 500, ...) and gets the
  // same error-state treatment as every other loader here (#674 review, nit a: it must not be
  // folded into "no members to show" alongside the expected 403 case).
  loadMembers: async (spaceId: string) => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoadingMembers: true, error: null })
    try {
      const members = await listSpaceMembers(spaceId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ members, isLoadingMembers: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Mitgliederliste konnte nicht geladen werden'
      set({ error: message, members: [], isLoadingMembers: false })
    }
  },

  addMember: async (spaceId, userId, role) => {
    await addSpaceMember(spaceId, userId, role)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  updateMemberRole: async (spaceId, userId, role) => {
    await updateSpaceMemberRole(spaceId, userId, role)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  removeMember: async (spaceId, userId) => {
    await removeSpaceMember(spaceId, userId)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  transferOwnership: async (spaceId, userId) => {
    await transferSpaceOwnership(spaceId, userId)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  updateDetails: async (spaceId, name, description, visibility) => {
    await updateSpaceDetails(spaceId, name, description, visibility)
    await Promise.all([get().loadSpaces(), get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  deleteSelectedSpace: async (spaceId) => {
    const sessionEpoch = currentSessionEpoch()
    await deleteSpace(spaceId)
    await get().loadSpaces()
    const fallbackSpaceId = get().spaces[0]?.id
    if (fallbackSpaceId) {
      await get().selectSpace(fallbackSpaceId)
    } else {
      // #575: loadSpaces()/selectSpace() above already guard their own write-backs - this direct
      // set() needs the same guard, otherwise a logout in between still resurrects an (empty but
      // non-null) selection state into the store reset() just cleared.
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ selectedSpace: null, selectedSpaceId: null })
    }
  },

  archiveSelectedSpace: async (spaceId) => {
    await archiveSpace(spaceId)
    // The space itself stays selectable - archiving stops new content, it does not remove the
    // space or navigate away from it (#543).
    await Promise.all([get().loadSpaces(), get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  createNewSpace: async (name, description, visibility) => {
    const space = await createSpace(name, description, visibility)
    await get().loadSpaces()
    await get().selectSpace(space.id)
    return space.id
  },
}))
