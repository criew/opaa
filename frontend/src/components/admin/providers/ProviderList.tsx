import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
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
import StarRoundedIcon from '@mui/icons-material/StarRounded'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { OidcProviderResponse } from '../../../types/api'
import { fontFamily } from '../../../theme/tokens'
import { apiErrorMessage } from '../../../services/apiErrorDetails'
import { notify } from '../../../stores/notificationStore'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import ProviderMonogram from '../../ProviderMonogram'
import { PROVIDER_CONFLICT_MESSAGES } from '../oidcProviderConflicts'
import { PROVIDER_STATE_LABEL, providerState } from '../oidcProviderState'
import ProviderRowMenu from './ProviderRowMenu'
import ProviderStateDot from './ProviderStateDot'

interface ProviderListProps {
  providers: OidcProviderResponse[]
  onEdit: (provider: OidcProviderResponse) => void
}

/**
 * Was der Anbieter aus dem Token holt.
 *
 * Die Rollenwerte stehen mit in der Übersicht, obwohl es die knappere Zelle ohne sie gäbe: Sie
 * beantworten, **wer über diesen Anbieter Systemverwalter wird** — das soll man sehen, ohne den
 * Dialog zu öffnen. Die Claims für E-Mail und Anzeigename sind dagegen Routine und stehen dort,
 * wo man sie ändert.
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

/** Die Reihenfolge-Schaltflächen; in beiden Darstellungen dieselben Namen. */
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

/** Dieselben Angaben untereinander - eine sechsspaltige Tabelle trägt ein schmales Fenster nicht,
 *  und die Seite dürfte dafür nicht waagerecht rollen (Muster der Konten- und Modellliste). */
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
        {provider.isDefault && (
          <Chip
            label="Standard"
            color="primary"
            size="small"
            icon={<StarRoundedIcon />}
            aria-label="Standardanbieter"
          />
        )}
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
 * Die Identitätsanbieter als Liste (#1625) — in der Reihenfolge der Anmeldeseite. Ab dem
 * `md`-Breakpoint als Tabelle, darunter als Karten: Sechs Spalten trägt ein schmales Fenster
 * nicht, und die Seite dürfte dafür nicht waagerecht rollen.
 *
 * **Nicht sortierbar, mit Absicht.** Die Reihenfolge dieser Liste *ist* die Reihenfolge, in der
 * die Anbieter auf der Anmeldeseite stehen; eine Sortierung nach Name oder Zustand würde genau
 * die Aussage zerstören, die die Liste trägt. Die Position steht deshalb sichtbar in der ersten
 * Spalte, nicht nur in einem versteckten Text.
 *
 * **Die Pfeile bleiben in der Zeile**, anders als jede andere Handlung. Umsortieren ist eine
 * wiederholte Handlung — ein Menü, das sich nach jedem Schritt schließt, macht aus drei Klicks
 * neun.
 *
 * Das Monogramm ist dasselbe wie auf der Anmeldeseite: Was die Systemverwaltung hier konfiguriert,
 * erkennt die anmeldende Person dort wieder.
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
                  </Box>
                  {/* Warum er nicht erreichbar ist - die Antwort des Anbieters, wörtlich. „Nicht
                      erreichbar" allein sagt nicht, ob die Adresse falsch ist oder der Dienst
                      steht. */}
                  {state === 'unreachable' && provider.registryMessage && (
                    <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.25 }}>
                      {provider.registryMessage}
                    </Typography>
                  )}
                  {provider.isDefault && (
                    <Chip
                      label="Standard"
                      color="primary"
                      size="small"
                      icon={<StarRoundedIcon />}
                      aria-label="Standardanbieter"
                      sx={{ mt: 0.5 }}
                    />
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
