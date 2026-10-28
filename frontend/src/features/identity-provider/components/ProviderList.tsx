import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { OidcProviderResponse } from '@/shared/api/types'
import { fontFamily } from '@/shared/theme/tokens'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import ProviderMonogram from '@/shared/ui/ProviderMonogram'
import MetaBadge from '@/shared/ui/MetaBadge'
import { PROVIDER_CONFLICT_MESSAGES } from './oidcProviderConflicts'
import {
  PER_INSTANCE_NOTE,
  PROVIDER_STATE_LABEL,
  isPerInstance,
  providerState,
} from './oidcProviderState'
import ProviderRowMenu from './ProviderRowMenu'
import ProviderStateDot from './ProviderStateDot'

interface ProviderListProps {
  providers: OidcProviderResponse[]
  onEdit: (provider: OidcProviderResponse) => void
}

/**
 * What the provider takes from the token.
 *
 * The role values are part of the overview, although the cell would be shorter without them:
 * they answer **who becomes system administrator via this provider** — that should be visible
 * without opening the dialog. The claims for email and display name, by contrast, are routine
 * and are shown where they are changed.
 */
function ClaimCell({ provider }: { provider: OidcProviderResponse }) {
  const { rolesClaim, groupsClaim, systemAdminRole, auditorRole } = provider.claimMapping
  const rollenwerte = [
    systemAdminRole ? `SYSTEM_ADMIN = ${systemAdminRole}` : null,
    auditorRole ? `AUDITOR = ${auditorRole}` : null,
  ].filter((wert): wert is string => wert !== null)
  return (
    <>
      <Box component="span" sx={{ display: 'block' }}>
        {rolesClaim ? 'Rollen aus dem Token' : 'Rollen in OPAA verwaltet'}
      </Box>
      {rolesClaim && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', overflowWrap: 'anywhere' }}>
          {rollenwerte.length > 0 ? rollenwerte.join(', ') : 'keine Rollenwerte gesetzt'}
        </Typography>
      )}
      <Typography sx={{ fontSize: 12, color: 'text.secondary', overflowWrap: 'anywhere' }}>
        {groupsClaim ? `Gruppen aus ${groupsClaim}` : 'Keine Gruppen aus dem Token'}
      </Typography>
    </>
  )
}

interface ProviderRowProps {
  provider: OidcProviderResponse
  position: number
  isFirst: boolean
  isLast: boolean
  isLastEnabled: boolean
  canDisable: boolean
  canDelete: boolean
  onMove: (provider: OidcProviderResponse, direction: 'up' | 'down') => void
  onEdit: ProviderListProps['onEdit']
}

/** The order buttons; the same names in both presentations. */
function OrderButtons({
  provider,
  isFirst,
  isLast,
  onMove,
}: Pick<ProviderRowProps, 'provider' | 'isFirst' | 'isLast' | 'onMove'>) {
  return (
    <>
      <IconButton
        size="small"
        aria-label={`„${provider.displayName}“ nach oben verschieben`}
        disabled={isFirst}
        onClick={() => onMove(provider, 'up')}
      >
        <ArrowUpwardIcon fontSize="small" />
      </IconButton>
      <IconButton
        size="small"
        aria-label={`„${provider.displayName}“ nach unten verschieben`}
        disabled={isLast}
        onClick={() => onMove(provider, 'down')}
      >
        <ArrowDownwardIcon fontSize="small" />
      </IconButton>
    </>
  )
}

/** The same details stacked - a narrow window cannot carry a six-column table,
 *  and the page must not scroll horizontally for it (pattern of the account and model list). */
function ProviderCard({
  provider,
  position,
  isFirst,
  isLast,
  isLastEnabled,
  canDisable,
  canDelete,
  onMove,
  onEdit,
}: ProviderRowProps) {
  const state = providerState(provider)
  return (
    <Box sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25 }}>
        <Typography
          sx={{ fontSize: 12, color: 'text.secondary', fontFamily: fontFamily.mono, mt: 0.5 }}
        >
          {position}
        </Typography>
        <ProviderMonogram
          name={provider.displayName}
          size={28}
          tone={provider.enabled ? 'accent' : 'muted'}
        />
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{provider.displayName}</Typography>
          <Typography
            sx={{
              fontSize: 12,
              color: 'text.secondary',
              fontFamily: fontFamily.mono,
              overflowWrap: 'anywhere',
            }}
          >
            {provider.issuerUri}
          </Typography>
        </Box>
        <Box sx={{ flex: 'none', whiteSpace: 'nowrap' }}>
          <OrderButtons provider={provider} isFirst={isFirst} isLast={isLast} onMove={onMove} />
          <ProviderRowMenu
            provider={provider}
            isLastEnabled={isLastEnabled}
            canDisable={canDisable}
            canDelete={canDelete}
            onEdit={onEdit}
          />
        </Box>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 0.75, fontSize: 12.5 }}>
        <ProviderStateDot state={state} />
        <Box component="span">{PROVIDER_STATE_LABEL[state]}</Box>
        {isPerInstance(state) && (
          <Box component="span" sx={{ color: 'text.secondary' }}>
            · {PER_INSTANCE_NOTE}
          </Box>
        )}
        {provider.isDefault && <MetaBadge ariaLabel="Standardanbieter">Standard</MetaBadge>}
      </Box>
      {state === 'unreachable' && provider.registryMessage && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.25 }}>
          {provider.registryMessage}
        </Typography>
      )}
      <Typography
        sx={{
          fontSize: 12,
          color: 'text.secondary',
          fontFamily: fontFamily.mono,
          mt: 0.5,
          overflowWrap: 'anywhere',
        }}
      >
        {provider.clientId}
      </Typography>
      <Box sx={{ fontSize: 12.5, mt: 0.5 }}>
        <ClaimCell provider={provider} />
      </Box>
    </Box>
  )
}

/**
 * The identity providers as a list — in the order of the login page. From the
 * `md` breakpoint on as a table, below as cards: a narrow window cannot carry six columns,
 * and the page must not scroll horizontally for it.
 *
 * **Not sortable, on purpose.** The order of this list *is* the order in which
 * the providers appear on the login page; sorting by name or state would destroy exactly
 * the statement the list carries. The position is therefore visible in the first
 * column, not only in a hidden text.
 *
 * **The arrows stay in the row**, unlike every other action. Reordering is a
 * repeated action — a menu that closes after every step turns three clicks into
 * nine.
 *
 * The monogram is the same as on the login page: what the system administration configures here,
 * the person logging in recognizes there.
 */
export default function ProviderList({ providers, onEdit }: ProviderListProps) {
  const moveProvider = useOidcProviderStore((s) => s.moveProvider)
  const enabledCount = providers.filter((p) => p.enabled).length
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  async function move(provider: OidcProviderResponse, direction: 'up' | 'down') {
    try {
      await moveProvider(provider.id, direction)
    } catch (err) {
      notify(
        apiErrorMessage(err, PROVIDER_CONFLICT_MESSAGES, 'Verschieben fehlgeschlagen'),
        'error',
      )
    }
  }

  if (!isDesktop) {
    return (
      <Box>
        {providers.map((provider, index) => (
          <ProviderCard
            key={provider.id}
            provider={provider}
            position={index + 1}
            isFirst={index === 0}
            isLast={index === providers.length - 1}
            isLastEnabled={provider.enabled && enabledCount === 1}
            canDisable={!provider.isDefault || enabledCount <= 1}
            canDelete={!provider.isDefault || providers.length === 1}
            onMove={(p, richtung) => void move(p, richtung)}
            onEdit={onEdit}
          />
        ))}
      </Box>
    )
  }

  return (
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Identitätsanbieter, horizontal scrollbar"
    >
      <Table
        size="small"
        aria-label="Identitätsanbieter"
        sx={{
          minWidth: 860,
          tableLayout: 'fixed',
          '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
          '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
        }}
      >
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 52 }}>
              Nr.
              <span style={visuallyHidden}> auf der Anmeldeseite</span>
            </TableCell>
            <TableCell>Anbieter</TableCell>
            <TableCell sx={{ width: '19%' }}>Zustand</TableCell>
            <TableCell sx={{ width: '17%' }}>Client-ID</TableCell>
            <TableCell sx={{ width: '20%' }}>Aus dem Token</TableCell>
            <TableCell align="right" sx={{ width: 128 }}>
              <span style={visuallyHidden}>Reihenfolge und Aktionen</span>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {providers.map((provider, index) => {
            const state = providerState(provider)
            const isLastEnabled = provider.enabled && enabledCount === 1
            return (
              <TableRow key={provider.id}>
                <TableCell sx={{ color: 'text.secondary', fontFamily: fontFamily.mono }}>
                  {index + 1}
                </TableCell>
                <TableCell>
                  <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25 }}>
                    <ProviderMonogram
                      name={provider.displayName}
                      size={28}
                      tone={provider.enabled ? 'accent' : 'muted'}
                    />
                    <Box sx={{ minWidth: 0 }}>
                      <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 600 }}>
                        {provider.displayName}
                      </Typography>
                      <Tooltip title={provider.issuerUri}>
                        <Typography
                          sx={{
                            fontSize: 12,
                            color: 'text.secondary',
                            fontFamily: fontFamily.mono,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                          }}
                        >
                          {provider.issuerUri}
                        </Typography>
                      </Tooltip>
                    </Box>
                  </Box>
                </TableCell>
                <TableCell>
                  <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
                    <ProviderStateDot state={state} />
                    <Box component="span">{PROVIDER_STATE_LABEL[state]}</Box>
                    {isPerInstance(state) && (
                      <Box component="span" sx={{ color: 'text.secondary', fontSize: 12 }}>
                        · {PER_INSTANCE_NOTE}
                      </Box>
                    )}
                  </Box>
                  {/* Why it is unreachable - the provider's answer, verbatim. „Nicht
                       erreichbar" alone does not say whether the address is wrong or the service
                       is down. */}
                  {state === 'unreachable' && provider.registryMessage && (
                    <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.25 }}>
                      {provider.registryMessage}
                    </Typography>
                  )}
                  {provider.isDefault && (
                    <Box sx={{ mt: 0.5 }}>
                      <MetaBadge ariaLabel="Standardanbieter">Standard</MetaBadge>
                    </Box>
                  )}
                </TableCell>
                <TableCell
                  sx={{ fontFamily: fontFamily.mono, fontSize: 12, overflowWrap: 'anywhere' }}
                >
                  {provider.clientId}
                </TableCell>
                <TableCell>
                  <ClaimCell provider={provider} />
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <OrderButtons
                    provider={provider}
                    isFirst={index === 0}
                    isLast={index === providers.length - 1}
                    onMove={(p, richtung) => void move(p, richtung)}
                  />
                  <ProviderRowMenu
                    provider={provider}
                    isLastEnabled={isLastEnabled}
                    canDisable={!provider.isDefault || enabledCount <= 1}
                    canDelete={!provider.isDefault || providers.length === 1}
                    onEdit={onEdit}
                  />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
