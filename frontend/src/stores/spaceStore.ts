import { create } from 'zustand'
import type { SpaceListResponse, SpaceRole, SpaceResponse } from '../types/api'
import {
  addSpaceMember,
  createSpace,
  deleteSpace,
  getSpace,
  getSpaces,
  removeSpaceMember,
  transferSpaceOwnership,
  updateSpaceDetails,
  updateSpaceMemberRole,
} from '../services/api'

interface SpaceState {
  spaces: SpaceListResponse[]
  selectedSpaceId: string | null
  selectedSpace: SpaceResponse | null
  isLoadingList: boolean
  isLoadingDetails: boolean
  error: string | null
  reset: () => void
  loadSpaces: () => Promise<void>
  selectSpace: (spaceId: string) => Promise<void>
  addMember: (spaceId: string, userId: string, role?: SpaceRole) => Promise<void>
  updateMemberRole: (spaceId: string, userId: string, role: SpaceRole) => Promise<void>
  removeMember: (spaceId: string, userId: string) => Promise<void>
  transferOwnership: (spaceId: string, userId: string) => Promise<void>
  updateDetails: (spaceId: string, name: string, description: string) => Promise<void>
  deleteSelectedSpace: (spaceId: string) => Promise<void>
  createNewSpace: (name: string, description: string) => Promise<string>
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

  reset: () =>
    set({
      spaces: [],
      selectedSpaceId: null,
      selectedSpace: null,
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
    }),

  loadSpaces: async () => {
    set({ isLoadingList: true, error: null })
    try {
      const spaces = sortSpaces(await getSpaces())
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
      const message = err instanceof Error ? err.message : 'Spaces konnten nicht geladen werden'
      set({ error: message, isLoadingList: false })
    }
  },

  selectSpace: async (spaceId: string) => {
    set({ selectedSpaceId: spaceId, isLoadingDetails: true, error: null })
    try {
      const space = await getSpace(spaceId)
      set({
        selectedSpace: space,
        isLoadingDetails: false,
      })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Space-Details konnten nicht geladen werden'
      set({
        error: message,
        selectedSpace: null,
        isLoadingDetails: false,
      })
    }
  },

  addMember: async (spaceId, userId, role) => {
    await addSpaceMember(spaceId, userId, role)
    await get().selectSpace(spaceId)
  },

  updateMemberRole: async (spaceId, userId, role) => {
    await updateSpaceMemberRole(spaceId, userId, role)
    await get().selectSpace(spaceId)
  },

  removeMember: async (spaceId, userId) => {
    await removeSpaceMember(spaceId, userId)
    await get().selectSpace(spaceId)
  },

  transferOwnership: async (spaceId, userId) => {
    await transferSpaceOwnership(spaceId, userId)
    await get().selectSpace(spaceId)
  },

  updateDetails: async (spaceId, name, description) => {
    await updateSpaceDetails(spaceId, name, description)
    await Promise.all([get().loadSpaces(), get().selectSpace(spaceId)])
  },

  deleteSelectedSpace: async (spaceId) => {
    await deleteSpace(spaceId)
    await get().loadSpaces()
    const fallbackSpaceId = get().spaces[0]?.id
    if (fallbackSpaceId) {
      await get().selectSpace(fallbackSpaceId)
    } else {
      set({ selectedSpace: null, selectedSpaceId: null })
    }
  },

  createNewSpace: async (name, description) => {
    const space = await createSpace(name, description)
    await get().loadSpaces()
    await get().selectSpace(space.id)
    return space.id
  },
}))
