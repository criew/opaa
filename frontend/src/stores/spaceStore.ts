import { create } from 'zustand'
import type {
  SpaceAssetAssociationRequest,
  SpaceMemberRequest,
  AssetType,
  PermissionSubjectType,
  SpaceAssetAssociationResponse,
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
} from '../services/spaceApi'
import {
  associateSpaceAsset,
  detachSpaceAsset,
  getSpaceAssetAssociations,
} from '../services/assetApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

// #783 review: module-level, mirroring chatStore's chatLoadSequence - guards loadAssetAssociations
// against a *newer* call for a different space, not just against a session reset. Without it, a
// quick space switch (e.g. ChatInput reacting to the chat's spaceId) could let an in-flight
// response for the space just left overwrite state a later call already started clearing, so the
// wrong space's association count would render (#783 review finding 1).
let assetAssociationsRequestSeq = 0

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
  // The space's associated assets of every type that the caller may read, in every role - two
  // members of the same space can legitimately see different lists here. hasAssociations is a
  // count-free state field independent of the filtered items list.
  assetAssociations: SpaceAssetAssociationResponse[]
  hasAssetAssociations: boolean
  // The two count-free signals of an empty search scope, computed by the server because the
  // filtered list does not show what the caller cannot read: is any knowledge library associated
  // at all, and is any of it readable by the caller. Without knowledge, a chat searches nothing.
  hasKnowledge: boolean
  hasReadableKnowledge: boolean
  // Count-free: some association is left out of assetAssociations because the caller cannot read it.
  hasUnreadableAssociations: boolean
  isLoadingAssetAssociations: boolean
  // #783 review: the space id that assetAssociations/hasAssetAssociations actually describe -
  // null while nothing has successfully loaded yet, or after a failed load. A caller reading
  // assetAssociations/hasAssetAssociations must compare this against the space it cares about
  // before trusting the count for anything - otherwise a stale value from a previously loaded
  // space (or a failed load silently read as "no associations") renders as if it were current.
  assetAssociationsSpaceId: string | null
  reset: () => void
  loadSpaces: () => Promise<void>
  selectSpace: (spaceId: string) => Promise<void>
  loadMembers: (spaceId: string) => Promise<void>
  // #1815: a member is a person or a group; every membership is addressed by its own id.
  addMember: (
    spaceId: string,
    subjectType: PermissionSubjectType,
    subjectId: string,
    role?: SpaceRole,
  ) => Promise<void>
  updateMemberRole: (spaceId: string, membershipId: string, role: SpaceRole) => Promise<void>
  removeMember: (spaceId: string, membershipId: string) => Promise<void>
  transferOwnership: (spaceId: string, userId: string) => Promise<void>
  updateDetails: (
    spaceId: string,
    name: string,
    description: string,
    visibility?: SpaceVisibility,
    chatAutoCleanup?: boolean,
  ) => Promise<void>
  deleteSelectedSpace: (spaceId: string) => Promise<void>
  archiveSelectedSpace: (spaceId: string) => Promise<void>
  createNewSpace: (
    name: string,
    description: string,
    visibility?: SpaceVisibility,
    assets?: SpaceAssetAssociationRequest[],
    chatAutoCleanup?: boolean,
    initialMembers?: SpaceMemberRequest[],
  ) => Promise<string>
  /**
   * `keepCurrent` keeps the shown associations of the same space until the answer replaces them,
   * so a refresh after the caller's own change neither flickers nor empties the list meanwhile.
   */
  loadAssetAssociations: (spaceId: string, options?: { keepCurrent?: boolean }) => Promise<void>
  associateAsset: (spaceId: string, assetType: AssetType, assetId: string) => Promise<void>
  detachAsset: (spaceId: string, assetId: string) => Promise<void>
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
  assetAssociations: [],
  hasAssetAssociations: false,
  hasKnowledge: false,
  hasReadableKnowledge: false,
  hasUnreadableAssociations: false,
  isLoadingAssetAssociations: false,
  assetAssociationsSpaceId: null,

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
      assetAssociations: [],
      hasAssetAssociations: false,
      hasKnowledge: false,
      hasReadableKnowledge: false,
      hasUnreadableAssociations: false,
      isLoadingAssetAssociations: false,
      assetAssociationsSpaceId: null,
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
    // #144/#203: members and assetAssociations belong to whichever space was selected before -
    // clearing them here prevents either from briefly appearing to belong to the newly selected
    // space while the new lists (or a 403 for a non-admin) are still in flight.
    set({
      selectedSpaceId: spaceId,
      isLoadingDetails: true,
      error: null,
      members: [],
      assetAssociations: [],
      hasAssetAssociations: false,
      hasKnowledge: false,
      hasReadableKnowledge: false,
      hasUnreadableAssociations: false,
      assetAssociationsSpaceId: null,
    })
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

  addMember: async (spaceId, subjectType, subjectId, role) => {
    await addSpaceMember(spaceId, subjectType, subjectId, role)
    // The list carries the member figures too - reloading it keeps "nur Sie" current.
    await Promise.all([get().loadSpaces(), get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  updateMemberRole: async (spaceId, membershipId, role) => {
    await updateSpaceMemberRole(spaceId, membershipId, role)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  removeMember: async (spaceId, membershipId) => {
    await removeSpaceMember(spaceId, membershipId)
    await Promise.all([get().loadSpaces(), get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  transferOwnership: async (spaceId, userId) => {
    await transferSpaceOwnership(spaceId, userId)
    await Promise.all([get().selectSpace(spaceId), get().loadMembers(spaceId)])
  },

  updateDetails: async (spaceId, name, description, visibility, chatAutoCleanup) => {
    await updateSpaceDetails(spaceId, name, description, visibility, chatAutoCleanup)
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

  createNewSpace: async (
    name,
    description,
    visibility,
    assets,
    chatAutoCleanup,
    initialMembers,
  ) => {
    const space = await createSpace(
      name,
      description,
      visibility,
      assets,
      chatAutoCleanup,
      initialMembers,
    )
    await get().loadSpaces()
    await get().selectSpace(space.id)
    return space.id
  },

  // #783 review finding 1: clears the previous space's data at the start (not just on success), and
  // only writes a response back if this is still the most recently requested call - otherwise a
  // quick space switch (e.g. ChatInput reacting to the chat's spaceId) could let an in-flight
  // response for the space just left land after a later call already started for the next space,
  // making the wrong space's association count render. On failure, assetAssociationsSpaceId stays
  // null rather than becoming "this space has no associations" - #783 review nit 1: an unresolved
  // load must render as unknown, not silently as a claim about what the space searches.
  loadAssetAssociations: async (spaceId, options) => {
    const sessionEpoch = currentSessionEpoch()
    const requestId = ++assetAssociationsRequestSeq
    if (options?.keepCurrent && get().assetAssociationsSpaceId === spaceId) {
      set({ error: null })
    } else {
      set({
        isLoadingAssetAssociations: true,
        error: null,
        assetAssociations: [],
        hasAssetAssociations: false,
        hasKnowledge: false,
        hasReadableKnowledge: false,
        hasUnreadableAssociations: false,
        assetAssociationsSpaceId: null,
      })
    }
    try {
      const response = await getSpaceAssetAssociations(spaceId)
      if (isStaleSessionEpoch(sessionEpoch) || requestId !== assetAssociationsRequestSeq) return
      set({
        assetAssociations: response.items,
        hasAssetAssociations: response.hasAssociations,
        hasKnowledge: response.hasKnowledge,
        hasReadableKnowledge: response.hasReadableKnowledge,
        hasUnreadableAssociations: response.hasUnreadableAssociations,
        assetAssociationsSpaceId: spaceId,
        isLoadingAssetAssociations: false,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch) || requestId !== assetAssociationsRequestSeq) return
      const message =
        err instanceof Error ? err.message : 'Zugeordnete Bibliotheken konnten nicht geladen werden'
      set({
        error: message,
        assetAssociations: [],
        hasAssetAssociations: false,
        hasKnowledge: false,
        hasReadableKnowledge: false,
        hasUnreadableAssociations: false,
        assetAssociationsSpaceId: null,
        isLoadingAssetAssociations: false,
      })
    }
  },

  associateAsset: async (spaceId, assetType, assetId) => {
    await associateSpaceAsset(spaceId, assetType, assetId)
    await get().loadAssetAssociations(spaceId, { keepCurrent: true })
  },

  detachAsset: async (spaceId, assetId) => {
    await detachSpaceAsset(spaceId, assetId)
    await get().loadAssetAssociations(spaceId, { keepCurrent: true })
  },
}))
