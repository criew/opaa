import { create } from 'zustand'
import type {
  AccountResponse,
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
  ProviderType,
  SystemRole,
  UserInfo,
} from '../types/api'
import { changeUserRole, listAccounts, type AccountSortField } from '../services/accountApi'
import {
  createLocalUser,
  deleteLocalUser,
  generateLocalUserPassword,
  getLocalAuthSettings,
  getLocalUserSummary,
  lockLocalUser,
  requestLocalUserPasswordReset,
  unlockLocalUser,
  updateLocalAuthSettings,
  updateLocalUser,
  LOCAL_USER_PAGE_SIZE,
} from '../services/localUserApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/** The one review filter of ADR-0033 that is not a state: „ohne Ablaufdatum" / „inaktiv". */
export type LocalUserReviewFilter = 'ALL' | 'WITHOUT_EXPIRY' | 'INACTIVE'

/**
 * The filters of the account list (#1601). `providerType` and `providerId` select the origin;
 * `status` and `review` describe local accounts only and narrow the list to them.
 */
export interface AccountFilters {
  query: string
  providerType: ProviderType | null
  providerId: string | null
  status: LocalAccountState | null
  role: SystemRole | null
  review: LocalUserReviewFilter
  sort: AccountSortField
  direction: 'asc' | 'desc'
  page: number
}

export const INITIAL_ACCOUNT_FILTERS: AccountFilters = {
  query: '',
  providerType: null,
  providerId: null,
  status: null,
  role: null,
  review: 'ALL',
  sort: 'displayName',
  direction: 'asc',
  page: 0,
}

interface UserAdminState {
  accounts: AccountResponse[]
  total: number
  size: number
  filters: AccountFilters
  isLoading: boolean
  error: string | null

  summary: LocalUserSummaryResponse | null
  settings: LocalAuthSettingsResponse | null
  isLoadingSettings: boolean
  isSavingSettings: boolean
  settingsError: string | null

  reset: () => void
  /** Loads the page the current {@link AccountFilters} describe. */
  loadAccounts: () => Promise<void>
  /**
   * Merges `patch` into the filters and reloads. Every change but an explicit page jump returns
   * to the first page: a filter that narrows the result would otherwise land on a page that no
   * longer exists and show an empty list.
   */
  setFilters: (patch: Partial<AccountFilters>) => Promise<void>
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
  /** The role of any account, local or of a provider, over the one role endpoint. */
  changeRole: (id: string, role: SystemRole) => Promise<UserInfo>
  /**
   * Adopts a local mutation's own row and refreshes the review counts; internal to the store.
   * `sessionEpoch` is the epoch the caller captured **before** its request (Nachprüfung N2).
   */
  patchRow: (sessionEpoch: number, updated: LocalUserResponse) => Promise<void>
}

const emptyState = {
  accounts: [] as AccountResponse[],
  total: 0,
  size: LOCAL_USER_PAGE_SIZE,
  filters: INITIAL_ACCOUNT_FILTERS,
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

/** A local mutation's row, folded back into the account row it belongs to. */
function withLocal(account: AccountResponse, local: LocalUserResponse): AccountResponse {
  return {
    ...account,
    email: local.email,
    displayName: local.displayName,
    systemRole: local.systemRole,
    local,
  }
}

/**
 * Monotone counter over `loadAccounts` calls: typing in the search field faster than the answers
 * arrive must leave the last query's page in the table, not the one whose response was slowest.
 */
let listRequestSequence = 0

/**
 * The account administration of the Systemverwaltung (#1541, #1601, ADR-0033 Entscheidungen 4
 * and 11): the list of every account with its filters, the review counts of the local accounts
 * and the settings of the local sign-in.
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

  loadAccounts: async () => {
    const sessionEpoch = currentSessionEpoch()
    listRequestSequence += 1
    const requestId = listRequestSequence
    const isStale = () => isStaleSessionEpoch(sessionEpoch) || requestId !== listRequestSequence
    const { query, providerType, providerId, status, role, review, sort, direction, page } =
      get().filters
    set({ isLoading: true, error: null })
    try {
      const result = await listAccounts({
        query: query.trim(),
        providerType,
        providerId,
        status,
        role,
        withoutExpiry: review === 'WITHOUT_EXPIRY',
        inactive: review === 'INACTIVE',
        sort,
        direction,
        page,
      })
      if (isStale()) return
      set({ accounts: result.items, total: result.total, size: result.size, isLoading: false })
    } catch (err) {
      if (isStale()) return
      set({
        error: messageOf(err, 'Die Konten konnten nicht geladen werden'),
        isLoading: false,
      })
    }
  },

  setFilters: async (patch) => {
    set({
      filters: { ...get().filters, ...patch, page: patch.page ?? 0 },
    })
    await get().loadAccounts()
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
    await Promise.all([get().loadAccounts(), get().loadSummary()])
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
    await Promise.all([get().loadAccounts(), get().loadSummary()])
  },

  resetUserPassword: async (id) => requestLocalUserPasswordReset(id),

  generateUserPassword: async (id) => {
    const sessionEpoch = currentSessionEpoch()
    const result = await generateLocalUserPassword(id)
    // The generated password forces a change at the next sign-in, so the row's marker changes.
    if (!isStaleSessionEpoch(sessionEpoch)) await get().loadAccounts()
    return result
  },

  changeRole: async (id, role) => {
    const sessionEpoch = currentSessionEpoch()
    const info = await changeUserRole(id, role)
    if (isStaleSessionEpoch(sessionEpoch)) return info
    const systemRole = info.systemRole as SystemRole
    set({
      accounts: get().accounts.map((account) =>
        account.id === id
          ? {
              ...account,
              systemRole,
              local: account.local ? { ...account.local, systemRole } : account.local,
            }
          : account,
      ),
    })
    return info
  },

  patchRow: async (sessionEpoch, updated) => {
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({
      accounts: get().accounts.map((account) =>
        account.id === updated.id ? withLocal(account, updated) : account,
      ),
    })
    await get().loadSummary()
  },
}))
