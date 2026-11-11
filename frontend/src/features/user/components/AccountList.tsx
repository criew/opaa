import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import type { AccountResponse, LocalAccountState, LocalUserResponse } from '@/shared/api/types'
import {
  INITIAL_ACCOUNT_FILTERS,
  narrowsAccounts,
  useUserAdminStore,
} from '../stores/userAdminStore'
import type { AccountSortField } from '../api/accountApi'
import AdminList, {
  ListText,
  StateLabel,
  type AdminListColumn,
  type SortBinding,
} from '@/shared/ui/list/AdminList'
import MetaBadge from '@/shared/ui/MetaBadge'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'
import AccountOriginTag from './AccountOriginTag'
import LocalUserRowMenu from './LocalUserRowMenu'
import ProviderAccountRowMenu from './ProviderAccountRowMenu'
import type { SetupLinkHandover } from './SetupLinkDialog'
import { NOT_APPLICABLE, providerStateHint, providerStateText } from './accountLabels'
import {
  LOCAL_ACCOUNT_STATE_LABEL,
  PASSWORD_CHANGE_REASON_LABEL,
  SYSTEM_ROLE_LABEL,
  formatExpiry,
  lockReasonText,
} from './localUserLabels'
import { fontSize, iconSize } from '@/shared/theme/tokens'

const STATE_DOT_COLOR: Record<LocalAccountState, string> = {
  INVITED: 'text.disabled',
  ACTIVE: 'success.main',
  LOCKED: 'error.main',
  EXPIRED: 'warning.main',
}

/** Meaning-only colour: a dot next to the word, never a coloured chip (guidelines 1.2, 5.5). */
function LocalStateCell({ user }: { user: LocalUserResponse }) {
  const reason = lockReasonText(user)
  return (
    <Box sx={{ minWidth: 0 }}>
      <StateLabel
        color={STATE_DOT_COLOR[user.status]}
        label={LOCAL_ACCOUNT_STATE_LABEL[user.status]}
        hint={reason ? { label: 'Sperrgrund', reason } : null}
      />
      {user.passwordChangeRequired && (
        <Box sx={{ mt: 0.5 }}>
          <Tooltip
            title={
              user.passwordChangeReason
                ? `Anlass: ${PASSWORD_CHANGE_REASON_LABEL[user.passwordChangeReason]}`
                : ''
            }
          >
            <Box component="span">
              <MetaBadge>Passwortwechsel ausstehend</MetaBadge>
            </Box>
          </Tooltip>
        </Box>
      )}
    </Box>
  )
}

/**
 * The lifecycle of a provider account lies with its provider: the cell says where it is kept and,
 * when that provider issues no tokens any more, that this way in is closed - with the reason in
 * its tooltip.
 *
 * Der Hinweis trägt ein Symbol in der Signalfarbe, der Text bleibt `text.secondary` - die
 * Signalfarbe steht in Symbol und Kante, nicht im Fließtext (Gestaltungsleitlinien 2.3).
 */
function ProviderStateCell({ account }: { account: AccountResponse }) {
  const hint = providerStateHint(account)
  const cell = (
    <Stack
      direction="row"
      spacing={0.5}
      component="span"
      sx={{ alignItems: 'center', display: 'inline-flex', minWidth: 0, maxWidth: '100%' }}
    >
      {hint && (
        <WarningAmberIcon
          aria-hidden="true"
          sx={{ fontSize: iconSize.xs, flex: 'none', color: 'warning.main' }}
        />
      )}
      <Typography
        component="span"
        sx={{
          fontSize: fontSize.text,
          color: 'text.secondary',
          fontWeight: hint ? 500 : 400,
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {providerStateText(account)}
      </Typography>
    </Stack>
  )
  return hint ? <Tooltip title={hint}>{cell}</Tooltip> : cell
}

function StateCell({ account }: { account: AccountResponse }) {
  return account.local ? (
    <LocalStateCell user={account.local} />
  ) : (
    <ProviderStateCell account={account} />
  )
}

/** What the screen reader announces after a click - the field, and for the three ranked ones
 *  the order itself, because „aufsteigend" says nothing about a category. */
const SORT_DESCRIPTION: Record<AccountSortField, string> = {
  displayName: 'Name',
  email: 'E-Mail-Adresse',
  origin: 'Herkunft, lokale Konten zuerst',
  role: 'Rolle: Nutzer, Revision, Systemverwaltung',
  status: 'Zustand: gesperrt, abgelaufen, eingeladen, aktiv',
  expiresAt: 'Ablaufdatum',
  createdAt: 'Anlagedatum',
}

/** The column head's own word, where the announcement above is a sentence. */
const SORT_LABEL: Record<AccountSortField, string> = {
  displayName: 'Name',
  email: 'E-Mail',
  origin: 'Herkunft',
  role: 'Rolle',
  status: 'Zustand',
  expiresAt: 'Ablauf',
  createdAt: 'Angelegt',
}

/** The account list's sort, bound to its store. */
function useAccountSort(): SortBinding<AccountSortField> {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  return {
    sort: filters.sort,
    direction: filters.direction,
    onSort: (sort, direction) => void setFilters({ sort, direction }),
    labels: SORT_LABEL,
    descriptions: SORT_DESCRIPTION,
  }
}

interface RowHandlers {
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
  onHandover: (user: LocalUserResponse) => void
  onChangeRole: (account: AccountResponse) => void
}

interface RowProps extends RowHandlers {
  account: AccountResponse
  isSelf: boolean
}

/** A local account without a date is „unbefristet"; a provider account has no expiry here. */
function expiryText(account: AccountResponse): string {
  if (!account.local) return NOT_APPLICABLE
  return account.local.expiresAt ? formatExpiry(account.local.expiresAt) : 'unbefristet'
}

/** The menu of the row: the local one with its acts, the provider one with the role only. */
function RowMenu({ account, isSelf, onChangeRole, ...local }: RowProps) {
  if (account.local) {
    return <LocalUserRowMenu user={account.local} isSelf={isSelf} {...local} />
  }
  return <ProviderAccountRowMenu account={account} onChangeRole={onChangeRole} />
}

/**
 * Name and address in one cell: the name carries the row, the address identifies it. Two columns
 * for two strings of one identity cost the width that the origin and the reason need.
 */
function AccountCell({ account }: { account: AccountResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
        <ListText strong>{account.displayName}</ListText>
        {account.local?.bootstrap && (
          <Box component="span" sx={{ flex: 'none' }}>
            <MetaBadge>Notanker</MetaBadge>
          </Box>
        )}
      </Stack>
      <ListText secondary>{account.email}</ListText>
    </Box>
  )
}

/** The role on one line, cut off with its tooltip; what is pending or provider-led below it. */
function RoleCell({ account }: { account: AccountResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <ListText>{SYSTEM_ROLE_LABEL[account.systemRole]}</ListText>
      {(account.roleManagedByProvider || account.pendingSystemRole) && (
        <Stack direction="row" spacing={0.5} sx={{ mt: 0.5, minWidth: 0, overflow: 'hidden' }}>
          {account.roleManagedByProvider && <MetaBadge>Vom Anbieter geführt</MetaBadge>}
          {account.pendingSystemRole && (
            <MetaBadge>{`${SYSTEM_ROLE_LABEL[account.pendingSystemRole]} beantragt`}</MetaBadge>
          )}
        </Stack>
      )}
    </Box>
  )
}

function AccountEmptyState({ createAction }: { createAction: ReactNode }) {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  if (!narrowsAccounts(filters)) {
    return <EmptyState framed message="Noch keine Konten." action={createAction} />
  }
  const { query, providerType, providerId, status, role, review } = INITIAL_ACCOUNT_FILTERS
  const contradiction =
    filters.providerType === 'OIDC' && (filters.status !== null || filters.review !== 'ALL')
  return (
    <EmptyState
      framed
      kind="noMatch"
      hint={
        contradiction
          ? 'Zustand und Auflage gelten nur für lokale Konten – bei Anbieterkonten führt der Anbieter den Lebenszyklus.'
          : undefined
      }
      onReset={() => void setFilters({ query, providerType, providerId, status, role, review })}
    />
  )
}

interface AccountListProps extends RowHandlers {
  /** The signed-in account, so its own row offers neither locking nor deletion. */
  currentUserId: string | null
  /** The create button of the page header, repeated by the first-time empty state. */
  createAction: ReactNode
}

/**
 * The list of all accounts (ADR-0033 Entscheidung 11): local accounts with state and
 * expiry, provider accounts with their provider - on desktop a table, below tablet width a
 * card list (guidelines 5.3). Name and address share a cell. The creation reason is in the
 * edit dialog, not in the list. The list does not show an account's activity;
 * it acts only as the filter „länger nicht genutzt".
 */
export default function AccountList({
  currentUserId,
  createAction,
  ...handlers
}: AccountListProps) {
  const accounts = useUserAdminStore((s) => s.accounts)
  const isLoading = useUserAdminStore((s) => s.isLoading)
  const error = useUserAdminStore((s) => s.error)
  const loadAccounts = useUserAdminStore((s) => s.loadAccounts)
  const total = useUserAdminStore((s) => s.total)
  const size = useUserAdminStore((s) => s.size)
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  const sort = useAccountSort()

  if (error) {
    return (
      <LoadError
        message="Konten konnten nicht geladen werden."
        detail={error}
        onRetry={() => void loadAccounts()}
      />
    )
  }

  if (isLoading && accounts.length === 0) {
    return <LoadingState />
  }

  if (accounts.length === 0) {
    return <AccountEmptyState createAction={createAction} />
  }

  const columns: AdminListColumn<AccountResponse, AccountSortField>[] = [
    {
      id: 'account',
      head: 'Name',
      sortFields: ['displayName', 'email'],
      cell: (account) => <AccountCell account={account} />,
    },
    {
      id: 'origin',
      head: 'Herkunft',
      sortFields: ['origin'],
      width: '15%',
      card: 'line',
      cell: (account) => <AccountOriginTag account={account} />,
    },
    {
      id: 'role',
      head: 'Rolle',
      sortFields: ['role'],
      width: '15%',
      cell: (account) => <RoleCell account={account} />,
    },
    {
      id: 'status',
      head: 'Zustand',
      sortFields: ['status'],
      width: '16%',
      cell: (account) => <StateCell account={account} />,
    },
    {
      id: 'expiresAt',
      head: 'Ablauf',
      sortFields: ['expiresAt'],
      width: '11%',
      cell: (account) => expiryText(account),
    },
    {
      id: 'createdAt',
      head: 'Angelegt',
      sortFields: ['createdAt'],
      width: '11%',
      card: 'hidden',
      cell: (account) => formatExpiry(account.createdAt),
    },
  ]

  return (
    <AdminList
      label="Konten"
      rows={accounts}
      rowKey={(account) => account.id}
      rowLabel={(account) => account.displayName ?? account.email ?? account.id}
      columns={columns}
      sort={sort}
      rowActions={(account) => (
        <RowMenu account={account} isSelf={account.id === currentUserId} {...handlers} />
      )}
      pager={{ total, size, page: filters.page, onPage: (page) => void setFilters({ page }) }}
    />
  )
}
