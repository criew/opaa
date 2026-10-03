import { create } from 'zustand'
import type {
  SpaceAssetAssociationRequest,
  SpaceAddMemberRequest,
  AssetType,
  PermissionSubjectType,
  SpaceAssetAssociationResponse,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRole,
  SpaceResponse,
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
// The space whose associations were asked for last; a change elsewhere does not refresh them.
let assetAssociationsRequestedSpaceId: string | null = null

/** Shown when a change succeeded but the list could not be refreshed afterwards. */
export const ASSOCIATIONS_NOT_REFRESHED =
  'Die Änderung ist gespeichert, die Liste der Zuordnungen konnte aber nicht aktualisiert werden. Laden Sie die Seite neu.'

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
  /** Resolves to `accessLost` when the removal cost the caller the space itself. */
  removeMember: (spaceId: string, membershipId: string) => Promise<MembershipRefresh>
  transferOwnership: (spaceId: string, userId: string) => Promise<void>
  updateDetails: (
    spaceId: string,
    name: string,
    description: string,
    chatAutoCleanup?: boolean,
  ) => Promise<void>
  deleteSelectedSpace: (spaceId: string) => Promise<void>
  archiveSelectedSpace: (spaceId: string) => Promise<void>
  createNewSpace: (
    name: string,
    description: string,
    assets?: SpaceAssetAssociationRequest[],
    chatAutoCleanup?: boolean,
    initialMembers?: SpaceAddMemberRequest[],
  ) => Promise<string>
  /**
   * `keepCurrent` keeps the shown associations of the same space until the answer replaces them,
   * and keeps them on a failure too, so a refresh after the caller's own change neither flickers
   * nor empties the list.
   */
  loadAssetAssociations: (spaceId: string, options?: { keepCurrent?: boolean }) => Promise<void>
  associateAsset: (spaceId: string, assetType: AssetType, assetId: string) => Promise<void>
  detachAsset: (spaceId: string, assetId: string) => Promise<void>
}

/** Shown when a membership change succeeded but the members could not be re-read afterwards. */
export const MEMBERS_NOT_REFRESHED =
  'Die Änderung ist gespeichert, die Mitgliederliste konnte aber nicht aktualisiert werden. Laden Sie die Seite neu.'

/** How the re-read after a membership change ended. */
export type MembershipRefresh = 'refreshed' | 'accessLost' | 'notRefreshed'

/** Per space, the latest re-read; an older answer arriving later is dropped. */
const membershipRefreshSeq = new Map<string, number>()

function responseStatus(err: unknown): number | null {
  const cause = err instanceof Error ? err.cause : undefined
  const status = (cause as { response?: { status?: unknown } } | undefined)?.response?.status
  return typeof status === 'number' ? status : null
}

/**
 * Re-reads the selected space and its members after the caller's own membership change, in place:
 * no loading state and no emptied list in between, so an open members tab keeps its rows and the
 * focus. Writes only for the latest re-read of a space that is still selected. Never throws - the
 * change itself already succeeded. A 403/404 means the caller lost access: the space is dropped
 * like a failed `selectSpace`; any other failure becomes {@link MEMBERS_NOT_REFRESHED}.
 */
async function refreshMembership(spaceId: string): Promise<MembershipRefresh> {
  const sessionEpoch = currentSessionEpoch()
  const requestId = (membershipRefreshSeq.get(spaceId) ?? 0) + 1
  membershipRefreshSeq.set(spaceId, requestId)
  const isLatest = () =>
    !isStaleSessionEpoch(sessionEpoch) && membershipRefreshSeq.get(spaceId) === requestId
  const isCurrent = () => isLatest() && useSpaceStore.getState().selectedSpaceId === spaceId
  try {
    const [space, members] = await Promise.all([getSpace(spaceId), listSpaceMembers(spaceId)])
    if (isCurrent()) useSpaceStore.setState({ selectedSpace: space, members, error: null })
    return 'refreshed'
  } catch (err) {
    const status = responseStatus(err)
    if (status === 403 || status === 404) {
      // The list reload running alongside may already have moved the selection off this space;
      // the space's details are dropped wherever they are still shown.
      if (isLatest() && useSpaceStore.getState().selectedSpace?.id === spaceId) {
        useSpaceStore.setState({ selectedSpace: null, members: [] })
      }
      return 'accessLost'
    }
    if (isCurrent()) useSpaceStore.setState({ error: MEMBERS_NOT_REFRESHED })
    return 'notRefreshed'
  }
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
    await Promise.all([get().loadSpaces(), refreshMembership(spaceId)])
  },

  updateMemberRole: async (spaceId, membershipId, role) => {
    await updateSpaceMemberRole(spaceId, membershipId, role)
    await refreshMembership(spaceId)
  },

  removeMember: async (spaceId, membershipId) => {
    await removeSpaceMember(spaceId, membershipId)
    const [, refresh] = await Promise.all([get().loadSpaces(), refreshMembership(spaceId)])
    return refresh
  },

  transferOwnership: async (spaceId, userId) => {
    await transferSpaceOwnership(spaceId, userId)
    await refreshMembership(spaceId)
  },

  updateDetails: async (spaceId, name, description, chatAutoCleanup) => {
    await updateSpaceDetails(spaceId, name, description, chatAutoCleanup)
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

  createNewSpace: async (name, description, assets, chatAutoCleanup, initialMembers) => {
    const space = await createSpace(name, description, assets, chatAutoCleanup, initialMembers)
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
    assetAssociationsRequestedSpaceId = spaceId
    const keepCurrent = Boolean(options?.keepCurrent) && get().assetAssociationsSpaceId === spaceId
    if (keepCurrent) {
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
      if (keepCurrent) {
        set({ error: ASSOCIATIONS_NOT_REFRESHED })
        return
      }
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

  // Refreshes only the space whose associations are on display; a change in a space left behind
  // (an in-flight request, an undo) must not overwrite the one now shown.
  associateAsset: async (spaceId, assetType, assetId) => {
    await associateSpaceAsset(spaceId, assetType, assetId)
    if (assetAssociationsRequestedSpaceId === spaceId) {
      await get().loadAssetAssociations(spaceId, { keepCurrent: true })
    }
  },

  detachAsset: async (spaceId, assetId) => {
    await detachSpaceAsset(spaceId, assetId)
    if (assetAssociationsRequestedSpaceId === spaceId) {
      await get().loadAssetAssociations(spaceId, { keepCurrent: true })
    }
  },
}))
