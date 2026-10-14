import { http, HttpResponse } from 'msw'
import type { AccountResponse, SystemRole } from '@/shared/api/types'
import { mockProviderAccounts, setMockProviderAccounts, toLocalAccount } from './accountFixtures'
import {
  countsAsInactive,
  countsAsWithoutExpiry,
  LAST_ADMIN_USER_ID,
  mockLocalUsers,
  setMockLocalUsers,
} from './localUserFixtures'

/**
 * The account list and the role endpoint as MSW handlers - with the invariants the
 * interface relies on: local and provider accounts in one list, the three local filters
 * narrow to local accounts, no sorting by activity, a page size above 50 is 400;
 * the role endpoint rejects the role of a provider-managed account with 409, the last
 * login-capable system administrator with 409 `LAST_LOGIN_CAPABLE_ADMIN`.
 */

const SORT_KEYS = [
  'displayName',
  'email',
  'origin',
  'role',
  'status',
  'expiresAt',
  'createdAt',
] as const
type SortKey = (typeof SORT_KEYS)[number]

/** The same rankings as in the backend (AccountAdminService): role by privilege, state by
 *  urgency, origin first by type and then by provider name. */
const ROLE_RANK: Record<string, number> = { USER: 0, AUDITOR: 1, SYSTEM_ADMIN: 2 }
const STATUS_RANK: Record<string, number> = { LOCKED: 0, EXPIRED: 1, INVITED: 2, ACTIVE: 3 }

function originKey(account: AccountResponse): string {
  if (account.providerType === 'LOCAL') return '0'
  return account.provider ? `1${account.provider.displayName.toLowerCase()}` : '2'
}

function badRequest(error: string) {
  return HttpResponse.json(
    { error, status: 400, timestamp: new Date().toISOString() },
    { status: 400 },
  )
}

function conflict(error: string, code?: string) {
  return HttpResponse.json(
    { error, status: 409, code, timestamp: new Date().toISOString() },
    { status: 409 },
  )
}

function allAccounts(): AccountResponse[] {
  return [...mockLocalUsers.map(toLocalAccount), ...mockProviderAccounts]
}

function sortValue(account: AccountResponse, key: SortKey): string {
  switch (key) {
    case 'displayName':
      return account.displayName ?? ''
    case 'email':
      return account.email ?? ''
    case 'createdAt':
      return account.createdAt
    case 'expiresAt':
      return account.local?.expiresAt ?? ''
    case 'origin':
      return originKey(account)
    case 'role':
      return String(ROLE_RANK[account.systemRole] ?? 9)
    case 'status':
      // A provider account carries no state from OPAA's hand and sorts last.
      return String(account.local ? (STATUS_RANK[account.local.status] ?? 9) : 4)
  }
}

/** Only the sort field - without a tie-breaker, so that `desc` reverses exactly this result. */
function comparePrimary(a: AccountResponse, b: AccountResponse, key: SortKey): number {
  const left = sortValue(a, key)
  const right = sortValue(b, key)
  if (key === 'expiresAt') {
    // An account without an expiry date - every provider account - sorts last, like NULLS LAST.
    if (left === right) return 0
    if (left === '') return 1
    if (right === '') return -1
    return left < right ? -1 : 1
  }
  return left.localeCompare(right, 'de-DE')
}

/**
 * The backend's tie-breaker (AccountAdminService#comparator): first the display name, then
 * the ID. It stays **ascending** even when the primary order was reversed - otherwise
 * the double and the backend would return two different orders for `direction=desc`, and a test
 * would pin one that does not exist against the real backend.
 */
function compareTieBreak(a: AccountResponse, b: AccountResponse): number {
  const byName = (a.displayName ?? '').localeCompare(b.displayName ?? '', 'de-DE')
  return byName !== 0 ? byName : a.id.localeCompare(b.id)
}

export const accountHandlers = [
  http.get('/api/v1/admin/accounts', ({ request }) => {
    const url = new URL(request.url)
    const size = Number(url.searchParams.get('size') ?? '25')
    const page = Number(url.searchParams.get('page') ?? '0')
    const sort = url.searchParams.get('sort') ?? 'displayName'
    const direction = url.searchParams.get('direction') ?? 'asc'
    if (!Number.isInteger(size) || size < 1 || size > 50) {
      return badRequest('Die Seitengröße muss zwischen 1 und 50 liegen.')
    }
    if (!SORT_KEYS.includes(sort as SortKey)) {
      return badRequest(
        'Sortierung nur nach displayName, email, origin, role, status, expiresAt oder createdAt.',
      )
    }
    if (direction !== 'asc' && direction !== 'desc') {
      return badRequest('Sortierrichtung nur asc oder desc.')
    }
    const query = (url.searchParams.get('query') ?? '').trim().toLowerCase()
    const providerType = url.searchParams.get('providerType')
    const providerId = url.searchParams.get('providerId')
    const role = url.searchParams.get('role')
    const status = url.searchParams.get('status')
    const withoutExpiry = url.searchParams.get('withoutExpiry') === 'true'
    const inactive = url.searchParams.get('inactive') === 'true'
    const localOnly = status !== null || withoutExpiry || inactive

    const matching = allAccounts()
      .filter((account) => !providerType || account.providerType === providerType)
      .filter((account) => !providerId || account.provider?.id === providerId)
      .filter((account) => !role || account.systemRole === role)
      .filter((account) => {
        if (!localOnly) return true
        const local = account.local
        if (!local) return false
        if (status && local.status !== status) return false
        if (withoutExpiry && !countsAsWithoutExpiry(local)) return false
        if (inactive && !countsAsInactive(local)) return false
        return true
      })
      .filter(
        (account) =>
          !query ||
          (account.email ?? '').toLowerCase().includes(query) ||
          (account.displayName ?? '').toLowerCase().includes(query),
      )
      .sort((a, b) => {
        const primary = comparePrimary(a, b, sort as SortKey)
        if (primary !== 0) return direction === 'desc' ? -primary : primary
        return compareTieBreak(a, b)
      })
    const from = page * size
    return HttpResponse.json({
      items: matching.slice(from, from + size),
      total: matching.length,
      page,
      size,
    })
  }),

  http.post('/api/v1/admin/users/:id/role', async ({ params, request }) => {
    const id = String(params.id)
    const { role } = (await request.json()) as { role: SystemRole }
    const provider = mockProviderAccounts.find((account) => account.id === id)
    if (provider?.roleManagedByProvider) {
      return conflict(
        `Die Rolle wird vom Identitätsanbieter „${provider.provider?.displayName ?? ''}“ verwaltet und kann hier nicht geändert werden.`,
      )
    }
    if (id === LAST_ADMIN_USER_ID && role !== 'SYSTEM_ADMIN') {
      return conflict(
        'Danach wäre kein anmeldefähiger Systemverwalter mehr übrig.',
        'LAST_LOGIN_CAPABLE_ADMIN',
      )
    }
    if (provider) {
      setMockProviderAccounts(
        mockProviderAccounts.map((account) =>
          account.id === id ? { ...account, systemRole: role } : account,
        ),
      )
      return HttpResponse.json({
        id,
        email: provider.email,
        displayName: provider.displayName,
        systemRole: role,
      })
    }
    const local = mockLocalUsers.find((account) => account.id === id)
    if (!local) {
      return HttpResponse.json(
        { error: `Benutzer nicht gefunden: ${id}`, status: 404 },
        { status: 404 },
      )
    }
    setMockLocalUsers(
      mockLocalUsers.map((account) =>
        account.id === id ? { ...account, systemRole: role } : account,
      ),
    )
    return HttpResponse.json({
      id,
      email: local.email,
      displayName: local.displayName,
      systemRole: role,
      createdReason: local.createdReason,
    })
  }),
]
