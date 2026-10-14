import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
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
import MoreVertIcon from '@mui/icons-material/MoreVert'
import type {
  AdminExternalAccessTokenResponse,
  ExternalAccessTokenStatus,
} from '@/shared/api/types'
import {
  blockExternalAccessToken,
  blockExternalAccessTokensOfOwner,
  listAllExternalAccessTokens,
} from '../../../services/externalAccessApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import PageSection from '@/shared/ui/PageSection'
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
} from '../../externalaccess/tokenLabels'

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

/** Der Ablauffilter des Entwurfs; er ist zugleich der einzige Zeitfilter der Liste. */
const EXPIRING_WITHIN_DAYS = 30

const STATUS_OPTIONS: Array<{ value: ExternalAccessTokenStatus | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'alle Zustände' },
  { value: 'ACTIVE', label: TOKEN_STATUS_LABEL.ACTIVE },
  { value: 'EXPIRED', label: TOKEN_STATUS_LABEL.EXPIRED },
  { value: 'REVOKED', label: TOKEN_STATUS_LABEL.REVOKED },
  { value: 'BLOCKED', label: TOKEN_STATUS_LABEL.BLOCKED },
]

function RowMenu({
  token,
  onBlock,
  onBlockAllOfOwner,
}: {
  token: AdminExternalAccessTokenResponse
  onBlock: (token: AdminExternalAccessTokenResponse) => void
  onBlockAllOfOwner: (token: AdminExternalAccessTokenResponse) => void
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)

  // Das Menü schließt, bevor die Rückfrage aufgeht: zwei Ebenen mit eigenem Fokusfang wären
  // weder mit der Tastatur noch mit einem Screenreader zu verlassen.
  function run(action: () => void) {
    setAnchor(null)
    action()
  }

  return (
    <>
      <IconButton
        size="small"
        aria-label={`Aktionen für das Token „${token.name}“ von ${token.ownerDisplayName}`}
        onClick={(event) => setAnchor(event.currentTarget)}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        {token.status === 'ACTIVE' && (
          <MenuItem onClick={() => run(() => onBlock(token))}>Token sperren</MenuItem>
        )}
        <MenuItem onClick={() => run(() => onBlockAllOfOwner(token))}>
          Alle Tokens dieser Person sperren
        </MenuItem>
      </Menu>
    </>
  )
}

/**
 * Die Bestandsliste aller Zugangstokens der Installation (#1719).
 *
 * Sie trägt **kein** Nutzungsdatum und bietet **keinen** Filter nach Person: Eine nach Person
 * filterbare Tabelle mit einem Nutzungsdatum wäre der personenbezogene Auswertungspfad, dessen
 * Nichtexistenz security-and-compliance.md zusagt. Für eine Sperrentscheidung wird er nicht
 * gebraucht - gesperrt wird wegen eines Vorfalls oder eines Kontenlebenszyklus.
 *
 * Die Sperre je Person ist deshalb eine Handlung an einer Zeile und keine Auswertung: Sie schreibt,
 * statt die Liste nach Person zu gruppieren.
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

  // Die Zustandssetzung liegt in den Fortsetzungen der Zusage, nicht im Rumpf des Effekts: ein
  // synchrones setState dort erzeugt eine Kaskade von Renderdurchläufen (react-hooks).
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
                    <RowMenu
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
                <RowMenu
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
