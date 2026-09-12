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
import type { LocalAccountState, LocalUserResponse } from '../../../types/api'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import type { LocalUserSortField } from '../../../services/localUserApi'
import { fontFamily, radius } from '../../../theme/tokens'
import MetaBadge from '../../MetaBadge'
import LocalUserRowMenu from './LocalUserRowMenu'
import type { SetupLinkHandover } from './SetupLinkDialog'
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
function StateCell({ user }: { user: LocalUserResponse }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <Box
          component="span"
          aria-hidden="true"
          sx={{
            width: 8,
            height: 8,
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

interface SortableHeadProps {
  field: LocalUserSortField
  label: string
}

const SORT_DESCRIPTION: Record<LocalUserSortField, string> = {
  displayName: 'Name',
  email: 'E-Mail-Adresse',
  expiresAt: 'Ablaufdatum',
  createdAt: 'Anlagedatum',
}

function SortableHead({ field, label }: SortableHeadProps) {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  const active = filters.sort === field
  return (
    <TableCell sortDirection={active ? filters.direction : false}>
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
    </TableCell>
  )
}

interface RowProps {
  user: LocalUserResponse
  isSelf: boolean
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
}

function activityText(user: LocalUserResponse): string {
  return LOCAL_ACCOUNT_ACTIVITY_LABEL[user.activity]
}

/** One account as a card - the layout below tablet width (guidelines 5.3). */
function LocalUserCard({ user, isSelf, ...handlers }: RowProps) {
  return (
    <Box
      component="article"
      aria-label={user.displayName}
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        p: 1.5,
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
            {user.displayName}
            {user.bootstrap && (
              <Box component="span" sx={{ ml: 0.75 }}>
                <MetaBadge>Notanker</MetaBadge>
              </Box>
            )}
          </Typography>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', overflowWrap: 'anywhere' }}>
            {user.email}
          </Typography>
        </Box>
        <LocalUserRowMenu user={user} isSelf={isSelf} {...handlers} />
      </Stack>
      <Box sx={{ mt: 1 }}>
        <StateCell user={user} />
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.75 }}>
        {SYSTEM_ROLE_LABEL[user.systemRole]} · Ablauf {formatExpiry(user.expiresAt)} · Aktivität:{' '}
        {activityText(user)}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
        {shortenReason(user.createdReason, 120)}
      </Typography>
    </Box>
  )
}

function LocalUserTableRow({ user, isSelf, ...handlers }: RowProps) {
  return (
    <TableRow>
      <TableCell>
        <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>
          {user.displayName}
          {user.bootstrap && (
            <Box component="span" sx={{ ml: 0.75 }}>
              <MetaBadge>Notanker</MetaBadge>
            </Box>
          )}
        </Typography>
      </TableCell>
      <TableCell sx={{ overflowWrap: 'anywhere' }}>{user.email}</TableCell>
      <TableCell>{SYSTEM_ROLE_LABEL[user.systemRole]}</TableCell>
      <TableCell>
        <StateCell user={user} />
      </TableCell>
      <TableCell>{formatExpiry(user.expiresAt)}</TableCell>
      <TableCell sx={{ color: 'text.secondary' }}>{activityText(user)}</TableCell>
      <TableCell sx={{ maxWidth: 240 }}>
        {formatExpiry(user.createdAt)}
        <Tooltip title={user.createdReason}>
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
            {shortenReason(user.createdReason)}
          </Typography>
        </Tooltip>
      </TableCell>
      <TableCell align="right">
        <LocalUserRowMenu user={user} isSelf={isSelf} {...handlers} />
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

interface LocalUserListProps {
  /** The signed-in account, so its own row offers neither locking nor deletion. */
  currentUserId: string | null
  onEdit: (user: LocalUserResponse) => void
  onSetupLink: (handover: SetupLinkHandover) => void
  onGeneratedPassword: (user: LocalUserResponse, password: string) => void
}

/**
 * Die Liste der lokalen Konten (#1541, ADR-0033 Entscheidung 11): am Desktop eine Tabelle, unter
 * Tablet-Breite eine Kartenliste (guidelines 5.3). Sortierbar sind nur die vier erlaubten Felder –
 * nach der Aktivität wird ausdrücklich nicht sortiert, sie ist nur eine Klasse.
 */
export default function LocalUserList({
  currentUserId,
  onEdit,
  onSetupLink,
  onGeneratedPassword,
}: LocalUserListProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const users = useUserAdminStore((s) => s.users)
  const isLoading = useUserAdminStore((s) => s.isLoading)

  if (isLoading && users.length === 0) {
    return (
      <Stack spacing={1} aria-busy="true">
        <span style={visuallyHidden}>Konten werden geladen …</span>
        <Skeleton variant="rounded" height={48} />
        <Skeleton variant="rounded" height={48} />
        <Skeleton variant="rounded" height={48} />
      </Stack>
    )
  }

  if (users.length === 0) {
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
          Kein lokales Konto entspricht den gewählten Filtern.
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
          Diese Liste führt ausschließlich lokale Konten – Konten eines Identitätsanbieters
          erscheinen hier nie.
        </Typography>
      </Box>
    )
  }

  const handlers = { onEdit, onSetupLink, onGeneratedPassword }

  return (
    <>
      {isDesktop ? (
        <Table
          size="small"
          aria-label="Lokale Konten"
          sx={{
            '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
            '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top' },
          }}
        >
          <TableHead>
            <TableRow>
              <SortableHead field="displayName" label="Name" />
              <SortableHead field="email" label="E-Mail" />
              <TableCell>Rolle</TableCell>
              <TableCell>Zustand</TableCell>
              <SortableHead field="expiresAt" label="Ablauf" />
              <TableCell>Aktivität</TableCell>
              <SortableHead field="createdAt" label="Angelegt · Anlagegrund" />
              <TableCell align="right">
                <span style={visuallyHidden}>Aktionen</span>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((user) => (
              <LocalUserTableRow
                key={user.id}
                user={user}
                isSelf={user.id === currentUserId}
                {...handlers}
              />
            ))}
          </TableBody>
        </Table>
      ) : (
        <Stack spacing={1}>
          {users.map((user) => (
            <LocalUserCard
              key={user.id}
              user={user}
              isSelf={user.id === currentUserId}
              {...handlers}
            />
          ))}
        </Stack>
      )}
      <Pager />
    </>
  )
}
