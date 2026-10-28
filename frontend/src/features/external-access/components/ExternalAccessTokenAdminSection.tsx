import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import TableContainer from '@mui/material/TableContainer'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type {
  AdminExternalAccessTokenResponse,
  ExternalAccessTokenStatus,
} from '@/shared/api/types'
import {
  blockExternalAccessToken,
  blockExternalAccessTokensOfOwner,
  listAllExternalAccessTokens,
} from '../api/externalAccessApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import PageSection from '@/shared/ui/PageSection'
import RowMenu from '@/shared/ui/RowMenu'
import OwnTokensLink from './OwnTokensLink'
import StateDot from '@/shared/ui/StateDot'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { adminTableSx, definitionListSx, listCardSx } from '@/shared/ui/list/adminListStyles'
import {
  ADMIN_PURPOSE_HINT,
  BLOCK_ALL_CONSEQUENCE,
  BLOCK_CONSEQUENCE,
  TOKEN_STATUS_LABEL,
  formatDate,
} from './tokenLabels'

const STATUS_TONE: Record<ExternalAccessTokenStatus, StatusTone> = {
  ACTIVE: 'success',
  EXPIRED: 'neutral',
  REVOKED: 'neutral',
  BLOCKED: 'error',
}

/** The state as a dot before its word; the colour carries no meaning on its own. */
function TokenState({ status }: { status: ExternalAccessTokenStatus }) {
  return <StateDot tone={STATUS_TONE[status]} label={TOKEN_STATUS_LABEL[status]} />
}

function librariesOf(token: AdminExternalAccessTokenResponse): string {
  return token.libraries.map((library) => library.name).join(', ') || '—'
}

/** The draft's expiry filter; it is also the list's only time filter. */
const EXPIRING_WITHIN_DAYS = 30

const STATUS_OPTIONS: Array<{ value: ExternalAccessTokenStatus | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'alle Zustände' },
  { value: 'ACTIVE', label: TOKEN_STATUS_LABEL.ACTIVE },
  { value: 'EXPIRED', label: TOKEN_STATUS_LABEL.EXPIRED },
  { value: 'REVOKED', label: TOKEN_STATUS_LABEL.REVOKED },
  { value: 'BLOCKED', label: TOKEN_STATUS_LABEL.BLOCKED },
]

/** Both entries block for good (confirmation tone `danger`). */
function TokenMenu({
  token,
  onBlock,
  onBlockAllOfOwner,
}: {
  token: AdminExternalAccessTokenResponse
  onBlock: (token: AdminExternalAccessTokenResponse) => void
  onBlockAllOfOwner: (token: AdminExternalAccessTokenResponse) => void
}) {
  return (
    <RowMenu
      name={token.name}
      detail={`von ${token.ownerDisplayName}`}
      items={[
        token.status === 'ACTIVE' && {
          label: 'Token sperren',
          onClick: () => onBlock(token),
          danger: true,
        },
        {
          label: 'Alle Tokens dieser Person sperren',
          onClick: () => onBlockAllOfOwner(token),
          danger: true,
        },
      ]}
    />
  )
}

/**
 * The inventory list of all access tokens of the installation.
 *
 * It carries **no** usage date and offers **no** filter by person: a table filterable by
 * person with a usage date would be the person-related evaluation path whose
 * non-existence security-and-compliance.md promises. A revocation decision does not need it -
 * revocation happens because of an incident or an account life cycle.
 *
 * The revocation per person is therefore an action on a row and not an evaluation: it writes
 * instead of grouping the list by person.
 */
export default function ExternalAccessTokenAdminSection({
  onChanged,
}: {
  /** Called after a block, so figures elsewhere on the page follow. */
  onChanged?: () => void
} = {}) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const [tokens, setTokens] = useState<AdminExternalAccessTokenResponse[] | null>(null)
  const [status, setStatus] = useState<ExternalAccessTokenStatus | 'ALL'>('ALL')
  const [expiringSoon, setExpiringSoon] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The state updates live in the promise's continuations, not in the effect body: a
  // synchronous setState there causes a cascade of renders (react-hooks).
  const load = useCallback(
    () =>
      listAllExternalAccessTokens({
        status: status === 'ALL' ? undefined : status,
        expiringWithinDays: expiringSoon ? EXPIRING_WITHIN_DAYS : undefined,
      })
        .then((loaded) => {
          setTokens(loaded)
          setError(null)
        })
        .catch((err: unknown) => {
          setTokens([])
          setError(
            err instanceof Error ? err.message : 'Die Tokenliste konnte nicht geladen werden.',
          )
        }),
    [status, expiringSoon],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function handleBlock(token: AdminExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `Token „${token.name}“ von ${token.ownerDisplayName} sperren?`,
      consequence: BLOCK_CONSEQUENCE,
      confirmLabel: 'Sperren',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      await blockExternalAccessToken(token.id)
      notify(`Das Token „${token.name}“ wurde gesperrt.`, 'success')
      await load()
      onChanged?.()
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Das Token konnte nicht gesperrt werden.',
        'error',
      )
    }
  }

  async function handleBlockAllOfOwner(token: AdminExternalAccessTokenResponse) {
    const confirmed = await confirmAction({
      question: `Alle Zugangstokens von ${token.ownerDisplayName} sperren?`,
      consequence: BLOCK_ALL_CONSEQUENCE,
      confirmLabel: 'Alle sperren',
      tone: 'danger',
    })
    if (!confirmed) return
    try {
      const blocked = await blockExternalAccessTokensOfOwner(token.ownerUserId)
      notify(
        blocked === 1
          ? `Ein Token von ${token.ownerDisplayName} wurde gesperrt.`
          : `${blocked} Tokens von ${token.ownerDisplayName} wurden gesperrt.`,
        'success',
      )
      await load()
      onChanged?.()
    } catch (err: unknown) {
      notify(
        err instanceof Error ? err.message : 'Die Tokens konnten nicht gesperrt werden.',
        'error',
      )
    }
  }

  return (
    <PageSection
      title="Zugangstokens"
      description={
        <>
          Zugangstokens erzeugt jede Person selbst unter <OwnTokensLink />. Hier sehen Sie den
          Bestand und können Tokens sperren. {ADMIN_PURPOSE_HINT}
        </>
      }
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 2, flexWrap: 'wrap' }}>
        <TextField
          select
          size="small"
          label="Zustand"
          value={status}
          onChange={(e) => setStatus(e.target.value as ExternalAccessTokenStatus | 'ALL')}
          sx={{ minWidth: 200 }}
        >
          {STATUS_OPTIONS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel
          control={
            <Switch checked={expiringSoon} onChange={(e) => setExpiringSoon(e.target.checked)} />
          }
          label={`Läuft in ${EXPIRING_WITHIN_DAYS} Tagen ab`}
        />
      </Stack>

      {tokens === null ? (
        <Skeleton variant="rounded" height={160} />
      ) : tokens.length === 0 ? (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          Zu dieser Auswahl gibt es kein Zugangstoken.
        </Typography>
      ) : isDesktop ? (
        <TableContainer>
          <Table size="small" aria-label="Zugangstokens" sx={{ ...adminTableSx, minWidth: 760 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: '17%' }}>Besitzer</TableCell>
                <TableCell sx={{ width: '21%' }}>Name</TableCell>
                <TableCell>Bibliotheken</TableCell>
                <TableCell sx={{ width: 96 }}>Erstellt</TableCell>
                <TableCell sx={{ width: 96 }}>Läuft ab</TableCell>
                <TableCell sx={{ width: 112 }}>Zustand</TableCell>
                <TableCell align="right" sx={{ width: 56 }}>
                  <span style={visuallyHidden}>Aktionen</span>
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {tokens.map((token) => (
                <TableRow key={token.id}>
                  <TableCell>{token.ownerDisplayName}</TableCell>
                  <TableCell sx={{ fontWeight: 600, overflowWrap: 'break-word' }}>
                    {token.name}
                  </TableCell>
                  <TableCell sx={{ overflowWrap: 'break-word' }}>{librariesOf(token)}</TableCell>
                  <TableCell>{formatDate(token.createdAt)}</TableCell>
                  <TableCell>{formatDate(token.expiresAt)}</TableCell>
                  <TableCell>
                    <TokenState status={token.status} />
                  </TableCell>
                  <TableCell align="right" sx={{ py: 0.5 }}>
                    <TokenMenu
                      token={token}
                      onBlock={(row) => void handleBlock(row)}
                      onBlockAllOfOwner={(row) => void handleBlockAllOfOwner(row)}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ) : (
        <Box
          component="ul"
          aria-label="Zugangstokens"
          sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
        >
          {tokens.map((token) => (
            <Box component="li" key={token.id} sx={listCardSx}>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
                    {token.name}
                  </Typography>
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {token.ownerDisplayName}
                  </Typography>
                </Box>
                <TokenMenu
                  token={token}
                  onBlock={(row) => void handleBlock(row)}
                  onBlockAllOfOwner={(row) => void handleBlockAllOfOwner(row)}
                />
              </Box>
              <Box component="dl" sx={definitionListSx}>
                <dt>Bibliotheken</dt>
                <dd>{librariesOf(token)}</dd>
                <dt>Erstellt</dt>
                <dd>{formatDate(token.createdAt)}</dd>
                <dt>Läuft ab</dt>
                <dd>{formatDate(token.expiresAt)}</dd>
                <dt>Zustand</dt>
                <dd>
                  <TokenState status={token.status} />
                </dd>
              </Box>
            </Box>
          ))}
        </Box>
      )}
    </PageSection>
  )
}
