import type {
  AccountPageResponse,
  LocalAccountState,
  ProviderType,
  SystemRole,
  UserInfo,
} from '../types/api'
import { apiClient, normalizeError } from './api'
import { LOCAL_USER_PAGE_SIZE } from './localUserApi'

/**
 * The sort fields of the account list (#1601): the four of the local list plus the three columns
 * it gained with the provider accounts. The activity class is not among them and never will be
 * (ADR-0033, Entscheidung 11) - a list sortable by „last used" is the evaluation path that
 * decision rules out. Origin, role and state say where an account comes from and whether it can
 * sign in, not when someone worked.
 */
export type AccountSortField =
  'displayName' | 'email' | 'origin' | 'role' | 'status' | 'expiresAt' | 'createdAt'

/**
 * The filter of the account list (#1601). `status`, `withoutExpiry` and `inactive` describe local
 * accounts only and narrow the list to them on the server.
 */
export interface AccountQuery {
  query?: string
  providerType?: ProviderType | null
  providerId?: string | null
  role?: SystemRole | null
  status?: LocalAccountState | null
  withoutExpiry?: boolean
  inactive?: boolean
  sort?: AccountSortField
  direction?: 'asc' | 'desc'
  page?: number
  size?: number
}

function queryString(query: AccountQuery): string {
  const params = new URLSearchParams()
  if (query.query) params.set('query', query.query)
  if (query.providerType) params.set('providerType', query.providerType)
  if (query.providerId) params.set('providerId', query.providerId)
  if (query.role) params.set('role', query.role)
  if (query.status) params.set('status', query.status)
  if (query.withoutExpiry) params.set('withoutExpiry', 'true')
  if (query.inactive) params.set('inactive', 'true')
  params.set('sort', query.sort ?? 'displayName')
  params.set('direction', query.direction ?? 'asc')
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? LOCAL_USER_PAGE_SIZE))
  return params.toString()
}

/**
 * Every account of the organization - local accounts with their state and activity class,
 * identity-provider accounts with their provider (#1601). No activity timestamp, no export, a
 * page of at most 50 rows: the guardrails of ADR-0033, Entscheidung 11, hold for this list too.
 */
export async function listAccounts(query: AccountQuery): Promise<AccountPageResponse> {
  try {
    const { data } = await apiClient.get<AccountPageResponse>(
      `/v1/admin/accounts?${queryString(query)}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The one role endpoint for local and provider accounts alike (ADR-0033, Entscheidung 11). A
 * provider that manages roles through a claim answers 409; taking the last login-capable system
 * administrator's role away answers 409 `LAST_LOGIN_CAPABLE_ADMIN`.
 */
export async function changeUserRole(id: string, role: SystemRole): Promise<UserInfo> {
  try {
    const { data } = await apiClient.post<UserInfo>(`/v1/admin/users/${id}/role`, { role })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
