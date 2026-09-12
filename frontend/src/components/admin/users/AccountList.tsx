import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TableSortLabel from '@mui/material/TableSortLabel'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import type { AccountResponse, LocalAccountState, LocalUserResponse } from '../../../types/api'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import type { AccountSortField } from '../../../services/accountApi'
import { fontFamily, radius } from '../../../theme/tokens'
import MetaBadge from '../../MetaBadge'
import AccountOriginTag from './AccountOriginTag'
import LocalUserRowMenu from './LocalUserRowMenu'
import ProviderAccountRowMenu from './ProviderAccountRowMenu'
import type { SetupLinkHandover } from './SetupLinkDialog'
import { NOT_APPLICABLE, providerStateHint, providerStateText } from './accountLabels'
import {
  LOCAL_ACCOUNT_ACTIVITY_LABEL,
  PASSWORD_CHANGE_REASON_LABEL,
  SYSTEM_ROLE_LABEL,
  formatExpiry,
  localAccountStateText,
  shortenReason,
} from './localUserLabels'

const STATE_DOT_COLOR: Record<LocalAccountState, string> = {
  INVITED: 'text.disabled',
  ACTIVE: 'success.main',
  LOCKED: 'error.main',
  EXPIRED: 'warning.main',
}

/** Meaning-only colour: a dot next to the word, never a coloured chip (guidelines 1.2, 5.5). */
function LocalStateCell({ user }: { user: LocalUserResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'flex-start' }}>
        <Box
          component="span"
          aria-hidden="true"
          sx={{
            width: 8,
            height: 8,
            mt: '5px',
            borderRadius: '50%',
            flex: 'none',
            bgcolor: STATE_DOT_COLOR[user.status],
          }}
        />
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 500 }}>
          {localAccountStateText(user)}
        </Typography>
      </Stack>
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
 */
function ProviderStateCell({ account }: { account: AccountResponse }) {
  const hint = providerStateHint(account)
  const cell = (
    <Typography
      component="span"
      sx={{
        fontSize: 13,
        color: hint ? 'warning.main' : 'text.secondary',
        fontWeight: hint ? 500 : 400,
      }}
    >
      {providerStateText(account)}
    </Typography>
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

interface SortableLabelProps {
  field: AccountSortField
  label: string
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

/** One sort control; several of them share a header cell where one column carries two values. */
function SortableLabel({ field, label }: SortableLabelProps) {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  const active = filters.sort === field
  return (
    <TableSortLabel
      active={active}
      direction={active ? filters.direction : 'asc'}
      onClick={() =>
        void setFilters({
          sort: field,
          direction: active && filters.direction === 'asc' ? 'desc' : 'asc',
        })
      }
    >
      {label}
      {active && (
        <span style={visuallyHidden}>
          {filters.direction === 'asc'
            ? `aufsteigend nach ${SORT_DESCRIPTION[field]} sortiert`
            : `absteigend nach ${SORT_DESCRIPTION[field]} sortiert`}
        </span>
      )}
    </TableSortLabel>
  )
}

function SortableHead({ fields, width }: { fields: AccountSortField[]; width?: string }) {
  const filters = useUserAdminStore((s) => s.filters)
  const active = fields.includes(filters.sort)
  return (
    <TableCell
      sortDirection={active ? filters.direction : false}
      sx={width ? { width } : undefined}
    >
      <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
        {fields.map((field, index) => (
          <Box key={field} component="span" sx={{ display: 'inline-flex', alignItems: 'center' }}>
            {index > 0 && (
              <Box component="span" aria-hidden="true" sx={{ mr: 0.5, color: 'text.disabled' }}>
                ·
              </Box>
            )}
            <SortableLabel field={field} label={SORT_LABEL[field]} />
          </Box>
        ))}
      </Stack>
    </TableCell>
  )
}

interface RowHandlers {
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
  onChangeRole: (account: AccountResponse) => void
}

interface RowProps extends RowHandlers {
  account: AccountResponse
  isSelf: boolean
}

function activityText(account: AccountResponse): string {
  return account.local ? LOCAL_ACCOUNT_ACTIVITY_LABEL[account.local.activity] : NOT_APPLICABLE
}

function expiryText(account: AccountResponse): string {
  return account.local ? formatExpiry(account.local.expiresAt) : NOT_APPLICABLE
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

/** Expiry above, the activity class below it - one column for the two dates of an account's use. */
function LifetimeCell({ account }: { account: AccountResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography sx={{ fontSize: 13 }}>{expiryText(account)}</Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
        {account.local ? activityText(account) : ''}
      </Typography>
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
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        p: 1.5,
      }}
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
        {expiryText(account)} · Aktivität: {activityText(account)}
      </Typography>
      {account.local && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
          {shortenReason(account.local.createdReason, 120)}
        </Typography>
      )}
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
        <LifetimeCell account={account} />
      </TableCell>
      <TableCell>
        <Typography sx={{ fontSize: 13 }}>{formatExpiry(account.createdAt)}</Typography>
        {account.local && (
          <Tooltip title={account.local.createdReason}>
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {shortenReason(account.local.createdReason)}
            </Typography>
          </Tooltip>
        )}
      </TableCell>
      <TableCell align="right">
        <RowMenu {...props} />
      </TableCell>
    </TableRow>
  )
}

/** Page switch with German labels; the page size stays at the list's own default (≤ 50). */
function Pager() {
  const total = useUserAdminStore((s) => s.total)
  const size = useUserAdminStore((s) => s.size)
  const page = useUserAdminStore((s) => s.filters.page)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  const pageCount = Math.max(1, Math.ceil(total / Math.max(size, 1)))
  if (total === 0) return null

  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: 'center', justifyContent: 'flex-end', mt: 1.5, flexWrap: 'wrap' }}
    >
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        {total === 1 ? '1 Konto' : `${total} Konten`} · Seite {page + 1} von {pageCount}
      </Typography>
      <Tooltip title="Vorherige Seite">
        <span>
          <IconButton
            size="small"
            aria-label="Vorherige Seite"
            disabled={page === 0}
            onClick={() => void setFilters({ page: page - 1 })}
          >
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Tooltip title="Nächste Seite">
        <span>
          <IconButton
            size="small"
            aria-label="Nächste Seite"
            disabled={page + 1 >= pageCount}
            onClick={() => void setFilters({ page: page + 1 })}
          >
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
    </Stack>
  )
}

function EmptyState() {
  const filters = useUserAdminStore((s) => s.filters)
  const contradiction =
    filters.providerType === 'OIDC' && (filters.status !== null || filters.review !== 'ALL')
  return (
    <Box
      sx={{
        border: 1,
        borderStyle: 'dashed',
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        p: 3,
        textAlign: 'center',
      }}
    >
      <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
        Kein Konto entspricht den gewählten Filtern.
      </Typography>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
        {contradiction
          ? 'Zustand und Auflage gelten nur für lokale Konten – bei Anbieterkonten führt der Anbieter den Lebenszyklus.'
          : 'Lokale Konten und Konten der Identitätsanbieter stehen hier gemeinsam; die Herkunft steht an jeder Zeile.'}
      </Typography>
    </Box>
  )
}

interface AccountListProps extends RowHandlers {
  /** The signed-in account, so its own row offers neither locking nor deletion. */
  currentUserId: string | null
}

/**
 * Die Liste aller Konten (#1541, #1601, ADR-0033 Entscheidung 11): lokale Konten mit Zustand,
 * Ablauf, Anlagegrund und Aktivitätsklasse, Anbieterkonten mit ihrem Anbieter - am Desktop eine
 * Tabelle, unter Tablet-Breite eine Kartenliste (guidelines 5.3). Sieben Spalten, nicht neun:
 * Name und Adresse teilen sich eine Zelle, Ablauf und Aktivität ebenso - sonst bleibt für Herkunft
 * und Anlagegrund keine lesbare Breite. Sortierbar sind nur die vier erlaubten Felder; nach der
 * Aktivität wird ausdrücklich nicht sortiert, und für ein Anbieterkonto gibt es sie nicht.
 */
export default function AccountList({ currentUserId, ...handlers }: AccountListProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const accounts = useUserAdminStore((s) => s.accounts)
  const isLoading = useUserAdminStore((s) => s.isLoading)

  if (isLoading && accounts.length === 0) {
    return (
      <Stack spacing={1} aria-busy="true">
        <span style={visuallyHidden}>Konten werden geladen …</span>
        <Skeleton variant="rounded" height={48} />
        <Skeleton variant="rounded" height={48} />
        <Skeleton variant="rounded" height={48} />
      </Stack>
    )
  }

  if (accounts.length === 0) {
    return <EmptyState />
  }

  return (
    <>
      {isDesktop ? (
        <Table
          size="small"
          aria-label="Konten"
          sx={{
            // Feste Breiten, weil der Inhalt es nicht ist: E-Mail-Adressen, Anbieternamen und
            // Anlagegründe sind beliebig lang. Ohne overflow liefe jede dieser Zellen in ihre
            // Nachbarin - mit ihr schneidet sie ab, und der Tooltip nennt den vollen Wert.
            tableLayout: 'fixed',
            '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
            '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
          }}
        >
          <TableHead>
            <TableRow>
              <SortableHead fields={['displayName', 'email']} />
              <SortableHead fields={['origin']} width="15%" />
              <SortableHead fields={['role']} width="13%" />
              <SortableHead fields={['status']} width="16%" />
              <TableCell sx={{ width: '11%' }}>
                <SortableLabel field="expiresAt" label="Ablauf" />
                <Box component="span" sx={{ display: 'block', color: 'text.disabled' }}>
                  Aktivität
                </Box>
              </TableCell>
              <TableCell sx={{ width: '19%' }}>
                <SortableLabel field="createdAt" label="Angelegt" />
                <Box component="span" sx={{ display: 'block', color: 'text.disabled' }}>
                  Anlagegrund
                </Box>
              </TableCell>
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
        <Stack spacing={1}>
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
