import { create } from 'zustand'
import type {
  LocalAccountState,
  LocalAuthSettingsResponse,
  LocalAuthSettingsUpdateRequest,
  LocalUserCreateRequest,
  LocalUserCreatedResponse,
  LocalUserGeneratedPasswordResponse,
  LocalUserPasswordResetResponse,
  LocalUserResponse,
  LocalUserSummaryResponse,
  LocalUserUpdateRequest,
  SystemRole,
} from '../types/api'
import {
  createLocalUser,
  deleteLocalUser,
  generateLocalUserPassword,
  getLocalAuthSettings,
  getLocalUserSummary,
  listLocalUsers,
  lockLocalUser,
  requestLocalUserPasswordReset,
  unlockLocalUser,
  updateLocalAuthSettings,
  updateLocalUser,
  type LocalUserSortField,
  LOCAL_USER_PAGE_SIZE,
} from '../services/localUserApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/** The one review filter of ADR-0033 that is not a state: „ohne Ablaufdatum" / „inaktiv". */
export type LocalUserReviewFilter = 'ALL' | 'WITHOUT_EXPIRY' | 'INACTIVE'

export interface LocalUserFilters {
  query: string
  status: LocalAccountState | null
  role: SystemRole | null
  review: LocalUserReviewFilter
  sort: LocalUserSortField
  direction: 'asc' | 'desc'
  page: number
}

export const INITIAL_LOCAL_USER_FILTERS: LocalUserFilters = {
  query: '',
  status: null,
  role: null,
  review: 'ALL',
  sort: 'displayName',
  direction: 'asc',
  page: 0,
}

interface UserAdminState {
  users: LocalUserResponse[]
  total: number
  size: number
  filters: LocalUserFilters
  isLoading: boolean
  error: string | null

  summary: LocalUserSummaryResponse | null
  settings: LocalAuthSettingsResponse | null
  isLoadingSettings: boolean
  isSavingSettings: boolean
  settingsError: string | null

  reset: () => void
  /** Loads the page the current {@link LocalUserFilters} describe. */
  loadUsers: () => Promise<void>
  /**
   * Merges `patch` into the filters and reloads. Every change but an explicit page jump returns
   * to the first page: a filter that narrows the result would otherwise land on a page that no
   * longer exists and show an empty list.
   */
  setFilters: (patch: Partial<LocalUserFilters>) => Promise<void>
  loadSummary: () => Promise<void>
  loadSettings: () => Promise<void>
  saveSettings: (request: LocalAuthSettingsUpdateRequest) => Promise<LocalAuthSettingsResponse>

  createUser: (request: LocalUserCreateRequest) => Promise<LocalUserCreatedResponse>
  updateUser: (id: string, request: LocalUserUpdateRequest) => Promise<LocalUserResponse>
  lockUser: (id: string, reason?: string | null) => Promise<LocalUserResponse>
  unlockUser: (id: string) => Promise<LocalUserResponse>
  deleteUser: (id: string) => Promise<void>
  resetUserPassword: (id: string) => Promise<LocalUserPasswordResetResponse>
  generateUserPassword: (id: string) => Promise<LocalUserGeneratedPasswordResponse>
  /**
   * Adopts a mutation's own row and refreshes the review counts; internal to the store.
   * `sessionEpoch` is the epoch the caller captured **before** its request (Nachprüfung N2).
   */
  patchRow: (sessionEpoch: number, updated: LocalUserResponse) => Promise<void>
}

const emptyState = {
  users: [] as LocalUserResponse[],
  total: 0,
  size: LOCAL_USER_PAGE_SIZE,
  filters: INITIAL_LOCAL_USER_FILTERS,
  isLoading: false,
  error: null as string | null,
  summary: null as LocalUserSummaryResponse | null,
  settings: null as LocalAuthSettingsResponse | null,
  isLoadingSettings: false,
  isSavingSettings: false,
  settingsError: null as string | null,
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

/**
 * Monotone counter over `loadUsers` calls: typing in the search field faster than the answers
 * arrive must leave the last query's page in the table, not the one whose response was slowest.
 */
let listRequestSequence = 0

/**
 * The local account management of the Systemverwaltung (#1541, ADR-0033 Entscheidungen 4 and 11):
 * the account list with its filters, the review counts and the settings of the local sign-in.
 *
 * Unlike {@link useOidcProviderStore} the list is **not** patched from a mutation's own response
 * alone: every act can move a row out of the current filter (a locked account under „Zustand:
 * Aktiv", a deleted one) and changes the review counts, so a mutation adopts the returned row
 * *and* reloads counts; creating and deleting reload the page itself.
 */
export const useUserAdminStore = create<UserAdminState>((set, get) => ({
  ...emptyState,

  reset: () => {
    listRequestSequence += 1
    set({ ...emptyState })
  },

  loadUsers: async () => {
    const sessionEpoch = currentSessionEpoch()
    listRequestSequence += 1
    const requestId = listRequestSequence
    const isStale = () => isStaleSessionEpoch(sessionEpoch) || requestId !== listRequestSequence
    const { query, status, role, review, sort, direction, page } = get().filters
    set({ isLoading: true, error: null })
    try {
      const result = await listLocalUsers({
        query: query.trim(),
        status,
        role,
        withoutExpiry: review === 'WITHOUT_EXPIRY',
        inactive: review === 'INACTIVE',
        sort,
        direction,
        page,
      })
      if (isStale()) return
      set({ users: result.items, total: result.total, size: result.size, isLoading: false })
    } catch (err) {
      if (isStale()) return
      set({
        error: messageOf(err, 'Die lokalen Konten konnten nicht geladen werden'),
        isLoading: false,
      })
    }
  },

  setFilters: async (patch) => {
    set({
      filters: { ...get().filters, ...patch, page: patch.page ?? 0 },
    })
    await get().loadUsers()
  },

  loadSummary: async () => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const summary = await getLocalUserSummary()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ summary })
    } catch {
      // The notice is an addition to the list, not a precondition of it: a failed count must not
      // replace the table with an error the list itself does not have.
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ summary: null })
    }
  },

  loadSettings: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoadingSettings: true, settingsError: null })
    try {
      const settings = await getLocalAuthSettings()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ settings, isLoadingSettings: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        settingsError: messageOf(err, 'Die Einstellungen konnten nicht geladen werden'),
        isLoadingSettings: false,
      })
    }
  },

  /*
   * Auch die Mutationen prüfen die Sitzungs-Epoche, bevor sie zurückschreiben (Review-Runde 1,
   * LOW 9): Eine Anfrage, die erst nach einer Abmeldung antwortet, würde sonst Daten des
   * vorherigen Kontos in einen gerade geleerten Store schreiben. Die Epoche wird dazu **vor** dem
   * `await` gefasst - nach dem Request gefasst wäre sie immer die neue und der Vergleich
   * wirkungslos (Nachprüfung N2). Der Fehler wird weiter geworfen, damit der Aufrufer seine
   * Meldung zeigen kann.
   */
  saveSettings: async (request) => {
    const sessionEpoch = currentSessionEpoch()
    set({ isSavingSettings: true, settingsError: null })
    try {
      const settings = await updateLocalAuthSettings(request)
      if (isStaleSessionEpoch(sessionEpoch)) return settings
      set({ settings, isSavingSettings: false })
      return settings
    } catch (err) {
      if (!isStaleSessionEpoch(sessionEpoch)) set({ isSavingSettings: false })
      throw err
    }
  },

  createUser: async (request) => {
    const sessionEpoch = currentSessionEpoch()
    const created = await createLocalUser(request)
    if (isStaleSessionEpoch(sessionEpoch)) return created
    await Promise.all([get().loadUsers(), get().loadSummary()])
    return created
  },

  updateUser: async (id, request) => {
    const sessionEpoch = currentSessionEpoch()
    const updated = await updateLocalUser(id, request)
    await get().patchRow(sessionEpoch, updated)
    return updated
  },

  lockUser: async (id, reason) => {
    const sessionEpoch = currentSessionEpoch()
    const updated = await lockLocalUser(id, reason)
    await get().patchRow(sessionEpoch, updated)
    return updated
  },

  unlockUser: async (id) => {
    const sessionEpoch = currentSessionEpoch()
    const updated = await unlockLocalUser(id)
    await get().patchRow(sessionEpoch, updated)
    return updated
  },

  deleteUser: async (id) => {
    const sessionEpoch = currentSessionEpoch()
    await deleteLocalUser(id)
    if (isStaleSessionEpoch(sessionEpoch)) return
    await Promise.all([get().loadUsers(), get().loadSummary()])
  },

  resetUserPassword: async (id) => requestLocalUserPasswordReset(id),

  generateUserPassword: async (id) => {
    const sessionEpoch = currentSessionEpoch()
    const result = await generateLocalUserPassword(id)
    // The generated password forces a change at the next sign-in, so the row's marker changes.
    if (!isStaleSessionEpoch(sessionEpoch)) await get().loadUsers()
    return result
  },

  patchRow: async (sessionEpoch, updated) => {
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ users: get().users.map((u) => (u.id === updated.id ? updated : u)) })
    await get().loadSummary()
  },
}))
