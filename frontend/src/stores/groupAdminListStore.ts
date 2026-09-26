import { create } from 'zustand'
import type {
  GroupEffectsResponse,
  GroupKind,
  GroupListResponse,
  GroupOrigin,
  GroupState,
} from '../types/api'
import { GROUP_PAGE_SIZE, listGroupPage, type GroupSortField } from '../services/groupAdminApi'
import { getGroupEffects } from '../services/permissionTransferApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/** The filters of the group list (#1978); `providerId` implies origin PROVIDER. */
export interface GroupFilters {
  query: string
  origin: GroupOrigin | null
  providerId: string | null
  kind: GroupKind | null
  state: GroupState | null
  sort: GroupSortField
  direction: 'asc' | 'desc'
  page: number
}

export const INITIAL_GROUP_FILTERS: GroupFilters = {
  query: '',
  origin: null,
  providerId: null,
  kind: null,
  state: null,
  sort: 'name',
  direction: 'asc',
  page: 0,
}

interface GroupAdminListState {
  groups: GroupListResponse[]
  total: number
  size: number
  filters: GroupFilters
  /** „Wo wirkt diese Gruppe" for the rows of the current page only. */
  effects: Record<string, GroupEffectsResponse>
  isLoading: boolean
  error: string | null

  reset: () => void
  /** Loads the page the current {@link GroupFilters} describe, then its effects. */
  loadGroups: () => Promise<void>
  /**
   * Merges `patch` into the filters and reloads. Every change but an explicit page jump returns
   * to the first page, so a narrowing filter never lands on a page that no longer exists.
   */
  setFilters: (patch: Partial<GroupFilters>) => Promise<void>
}

const emptyState = {
  groups: [] as GroupListResponse[],
  total: 0,
  size: GROUP_PAGE_SIZE,
  filters: INITIAL_GROUP_FILTERS,
  effects: {} as Record<string, GroupEffectsResponse>,
  isLoading: false,
  error: null as string | null,
}

/**
 * Monotone counter over `loadGroups` calls: typing in the search field faster than the answers
 * arrive must leave the last query's page in the table, not the one whose response was slowest.
 */
let requestSequence = 0

function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

/**
 * The group list of the administration (#1978), searched, filtered, sorted and paged on the
 * server like the account list. Acts on a single group stay in `useGroupStore`; after one of them
 * the page reloads here, because an act can move a group out of the current filter.
 */
export const useGroupAdminListStore = create<GroupAdminListState>((set, get) => ({
  ...emptyState,

  reset: () => {
    requestSequence += 1
    set({ ...emptyState })
  },

  loadGroups: async () => {
    const sessionEpoch = currentSessionEpoch()
    requestSequence += 1
    const requestId = requestSequence
    const isStale = () => isStaleSessionEpoch(sessionEpoch) || requestId !== requestSequence
    const { query, ...rest } = get().filters
    set({ isLoading: true, error: null })
    let groupIds: string[]
    try {
      const result = await listGroupPage({ query: query.trim(), ...rest })
      if (isStale()) return
      set({ groups: result.items, total: result.total, size: result.size, isLoading: false })
      groupIds = result.items.map((group) => group.id)
    } catch (err) {
      if (isStale()) return
      set({ error: messageOf(err, 'Die Gruppen konnten nicht geladen werden'), isLoading: false })
      return
    }
    // The effects are an addition to the rows, not a precondition of them: a failed read leaves
    // the column empty instead of the list.
    try {
      const effects = groupIds.length === 0 ? [] : await getGroupEffects({ groupIds })
      if (isStale()) return
      set({ effects: Object.fromEntries(effects.map((entry) => [entry.groupId, entry])) })
    } catch {
      if (!isStale()) set({ effects: {} })
    }
  },

  setFilters: async (patch) => {
    set({ filters: { ...get().filters, ...patch, page: patch.page ?? 0 } })
    await get().loadGroups()
  },
}))
