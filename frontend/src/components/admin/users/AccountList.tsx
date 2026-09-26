import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import type { AccountResponse, LocalAccountState, LocalUserResponse } from '../../../types/api'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import type { AccountSortField } from '../../../services/accountApi'
import {
  ListEmptyState,
  ListLoading,
  ListPager,
  SortableHeadCell,
  StateLabel,
  type SortBinding,
} from '../list/AdminList'
import { adminTableSx, listCardSx } from '../list/adminListStyles'
import MetaBadge from '../../MetaBadge'
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
 * Der Hinweis trägt ein Symbol in der Signalfarbe, der Text bleibt `text.secondary`:
 * `warning.main` als reiner Text misst auf heller Fläche rund 1,8:1 und unterschreitet 4,5:1 -
 * dieselbe Stelle wurde in SourceEvidenceDrawer und ChatInput bereits zweimal so beseitigt (#697).
 * Ein Symbol braucht nur 3:1 und darf die Farbe behalten.
 */
function ProviderStateCell({ account }: { account: AccountResponse }) {
  const hint = providerStateHint(account)
  const cell = (
    <Stack
      direction="row"
      spacing={0.5}
      component="span"
      sx={{ alignItems: 'center', display: 'inline-flex', minWidth: 0 }}
    >
      {hint && (
        <WarningAmberIcon
          aria-hidden="true"
          sx={{ fontSize: 14, flex: 'none', color: 'warning.main' }}
        />
      )}
      <Typography
        component="span"
        sx={{ fontSize: 13, color: 'text.secondary', fontWeight: hint ? 500 : 400 }}
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
      <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
        {account.displayName}
        {account.local?.bootstrap && (
          <Box component="span" sx={{ ml: 0.75 }}>
            <MetaBadge>Notanker</MetaBadge>
          </Box>
        )}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', overflowWrap: 'anywhere' }}>
        {account.email}
      </Typography>
    </Box>
  )
}

function RoleCell({ account }: { account: AccountResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      {SYSTEM_ROLE_LABEL[account.systemRole]}
      {account.roleManagedByProvider && (
        <Box sx={{ mt: 0.5 }}>
          <MetaBadge>Vom Anbieter geführt</MetaBadge>
        </Box>
      )}
    </Box>
  )
}

/** One account as a card - the layout below tablet width (guidelines 5.3). */
function AccountCard(props: RowProps) {
  const { account } = props
  return (
    <Box
      component="article"
      aria-label={account.displayName ?? account.email ?? account.id}
      // Auch schmal ein Listeneintrag, keine Karte (#1608) - dieselbe Sprache wie die Tabelle
      // daneben, nur einspaltig.
      sx={listCardSx}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <AccountCell account={account} />
          <Box sx={{ mt: 0.5 }}>
            <AccountOriginTag account={account} />
          </Box>
        </Box>
        <RowMenu {...props} />
      </Stack>
      <Box sx={{ mt: 1 }}>
        <StateCell account={account} />
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.75 }}>
        {SYSTEM_ROLE_LABEL[account.systemRole]}
        {account.roleManagedByProvider ? ' (vom Anbieter geführt)' : ''} · Ablauf{' '}
        {expiryText(account)}
      </Typography>
    </Box>
  )
}

function AccountTableRow(props: RowProps) {
  const { account } = props
  return (
    <TableRow>
      <TableCell>
        <AccountCell account={account} />
      </TableCell>
      <TableCell>
        <AccountOriginTag account={account} />
      </TableCell>
      <TableCell>
        <RoleCell account={account} />
      </TableCell>
      <TableCell>
        <StateCell account={account} />
      </TableCell>
      <TableCell>
        <Typography sx={{ fontSize: 13 }}>{expiryText(account)}</Typography>
      </TableCell>
      <TableCell>
        <Typography sx={{ fontSize: 13 }}>{formatExpiry(account.createdAt)}</Typography>
      </TableCell>
      <TableCell align="right">
        <RowMenu {...props} />
      </TableCell>
    </TableRow>
  )
}

/** Page switch; the page size stays at the list's own default (≤ 50). */
function Pager() {
  const total = useUserAdminStore((s) => s.total)
  const size = useUserAdminStore((s) => s.size)
  const page = useUserAdminStore((s) => s.filters.page)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  return (
    <ListPager
      total={total}
      size={size}
      page={page}
      onPage={(next) => void setFilters({ page: next })}
      countLabel={total === 1 ? '1 Konto' : `${total} Konten`}
    />
  )
}

function EmptyState() {
  const filters = useUserAdminStore((s) => s.filters)
  const contradiction =
    filters.providerType === 'OIDC' && (filters.status !== null || filters.review !== 'ALL')
  return (
    <ListEmptyState
      title="Kein Konto entspricht den gewählten Filtern."
      hint={
        contradiction
          ? 'Zustand und Auflage gelten nur für lokale Konten – bei Anbieterkonten führt der Anbieter den Lebenszyklus.'
          : 'Lokale Konten und Konten der Identitätsanbieter stehen hier gemeinsam; die Herkunft steht an jeder Zeile.'
      }
    />
  )
}

interface AccountListProps extends RowHandlers {
  /** The signed-in account, so its own row offers neither locking nor deletion. */
  currentUserId: string | null
}

/**
 * Die Liste aller Konten (#1541, #1601, ADR-0033 Entscheidung 11): lokale Konten mit Zustand und
 * Ablauf, Anbieterkonten mit ihrem Anbieter - am Desktop eine Tabelle, unter Tablet-Breite eine
 * Kartenliste (guidelines 5.3). Name und Adresse teilen sich eine Zelle. Der Anlagegrund steht im
 * Bearbeiten-Dialog, nicht in der Liste. Die Aktivität eines Kontos zeigt die
 * Liste nicht; sie wirkt nur als Filter „länger nicht genutzt".
 */
export default function AccountList({ currentUserId, ...handlers }: AccountListProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const accounts = useUserAdminStore((s) => s.accounts)
  const isLoading = useUserAdminStore((s) => s.isLoading)
  const sort = useAccountSort()

  if (isLoading && accounts.length === 0) {
    return <ListLoading label="Konten werden geladen …" />
  }

  if (accounts.length === 0) {
    return <EmptyState />
  }

  return (
    <>
      {isDesktop ? (
        <Table size="small" aria-label="Konten" sx={adminTableSx}>
          <TableHead>
            <TableRow>
              <SortableHeadCell fields={['displayName', 'email']} binding={sort} />
              <SortableHeadCell fields={['origin']} binding={sort} width="15%" />
              <SortableHeadCell fields={['role']} binding={sort} width="13%" />
              <SortableHeadCell fields={['status']} binding={sort} width="16%" />
              <SortableHeadCell fields={['expiresAt']} binding={sort} width="11%" />
              <SortableHeadCell fields={['createdAt']} binding={sort} width="11%" />
              <TableCell align="right" sx={{ width: 56 }}>
                <span style={visuallyHidden}>Aktionen</span>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {accounts.map((account) => (
              <AccountTableRow
                key={account.id}
                account={account}
                isSelf={account.id === currentUserId}
                {...handlers}
              />
            ))}
          </TableBody>
        </Table>
      ) : (
        <Stack spacing={0}>
          {accounts.map((account) => (
            <AccountCard
              key={account.id}
              account={account}
              isSelf={account.id === currentUserId}
              {...handlers}
            />
          ))}
        </Stack>
      )}
      <Pager />
    </>
  )
}
