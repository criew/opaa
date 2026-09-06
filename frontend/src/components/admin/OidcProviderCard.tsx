import { useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import BadgeOutlinedIcon from '@mui/icons-material/BadgeOutlined'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import PowerSettingsNewOutlinedIcon from '@mui/icons-material/PowerSettingsNewOutlined'
import StarOutlineRoundedIcon from '@mui/icons-material/StarOutlineRounded'
import StarRoundedIcon from '@mui/icons-material/StarRounded'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'
import type { OidcProviderResponse } from '../../types/api'
import { notify } from '../../stores/notificationStore'
import { useOidcProviderStore } from '../../stores/oidcProviderStore'
import { fontFamily, radius } from '../../theme/tokens'
import MetaBadge from '../MetaBadge'
import ProviderMonogram from '../ProviderMonogram'
import {
  PROVIDER_STATE_DETAIL,
  PROVIDER_STATE_LABEL,
  providerState,
  type ProviderState,
} from './oidcProviderState'

export const DISABLE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich ab sofort nicht mehr anmelden; laufende Sitzungen enden ' +
  'mit der nächsten Anfrage. Die Konten und ihre Rechte bleiben erhalten.'
export const DELETE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich nicht mehr anmelden. Die Konten bleiben erhalten und ' +
  'werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert.'
export const DEFAULT_CONSEQUENCE =
  'Der Standardanbieter ist der einzige, der weder deaktiviert noch gelöscht werden kann; die ' +
  'Erstadministrator-Regel und der Verzeichnisabgleich gelten nur für seine Konten.'

/** Meaning-only colour (guidelines 1.2): a dot next to the word, never a coloured chip (5.5). */
export function StateDot({ state }: { state: ProviderState }) {
  return (
    <Box
      component="span"
      aria-hidden="true"
      sx={{
        width: 8,
        height: 8,
        borderRadius: '50%',
        flex: 'none',
        bgcolor:
          state === 'reachable'
            ? 'success.main'
            : state === 'unreachable'
              ? 'error.main'
              : 'text.disabled',
      }}
    />
  )
}

function StateLine({ provider }: { provider: OidcProviderResponse }) {
  const state = providerState(provider)
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mt: 0.5, flexWrap: 'wrap' }}>
      <StateDot state={state} />
      <Typography component="span" sx={{ fontSize: 12.5, fontWeight: 500 }}>
        {PROVIDER_STATE_LABEL[state]}
      </Typography>
      <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
        · {PROVIDER_STATE_DETAIL[state]}
      </Typography>
    </Stack>
  )
}

interface MetaItemProps {
  icon: ReactNode
  label: string
  span?: boolean
  children: ReactNode
}

/** One term of the card's key-data list: eyebrow label with its glyph, value below. */
function MetaItem({ icon, label, span = false, children }: MetaItemProps) {
  return (
    <Box sx={{ minWidth: 0, gridColumn: span ? { xs: 'auto', sm: 'span 2' } : 'auto' }}>
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', color: 'text.secondary', '& svg': { fontSize: 14 } }}
      >
        {icon}
        <Typography
          component="dt"
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: 10,
            fontWeight: 500,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
          }}
        >
          {label}
        </Typography>
      </Stack>
      <Typography
        component="dd"
        sx={{ m: 0, mt: 0.5, fontSize: 13, lineHeight: 1.45, overflowWrap: 'anywhere' }}
      >
        {children}
      </Typography>
    </Box>
  )
}

function Mono({ children }: { children: string }) {
  return (
    <Box component="span" sx={{ fontFamily: fontFamily.mono, fontSize: 12.5 }}>
      {children}
    </Box>
  )
}

function claimSummary(provider: OidcProviderResponse): ReactNode {
  const { emailClaim, displayNameClaim, systemAdminRole, auditorRole } = provider.claimMapping
  const rolesClaim = provider.claimMapping.rolesClaim ?? null
  const roleValues = [
    systemAdminRole ? `SYSTEM_ADMIN = ${systemAdminRole}` : null,
    auditorRole ? `AUDITOR = ${auditorRole}` : null,
  ].filter((v): v is string => v !== null)
  return (
    <>
      E-Mail aus <Mono>{emailClaim ?? 'email'}</Mono>, Name aus{' '}
      <Mono>{displayNameClaim ?? 'name'}</Mono>.{' '}
      {rolesClaim === null ? (
        'Rollen werden in OPAA verwaltet.'
      ) : (
        <>
          Rollen aus <Mono>{rolesClaim}</Mono>
          {roleValues.length > 0 ? ` (${roleValues.join(', ')})` : ' (keine Rollenwerte gesetzt)'}.
        </>
      )}
    </>
  )
}

interface OidcProviderCardProps {
  provider: OidcProviderResponse
  /** 1-based position on the sign-in page. */
  position: number
  isFirst: boolean
  isLast: boolean
  onEdit: (provider: OidcProviderResponse) => void
}

/**
 * One identity provider in three zones: head (position, mark, name, state), key data (issuer,
 * client, keys, claim mapping) and actions. Consequence hints stay in the confirmation so nobody
 * disables or deletes a provider without reading what it means for its accounts (ADR-0025).
 */
export default function OidcProviderCard({
  provider,
  position,
  isFirst,
  isLast,
  onEdit,
}: OidcProviderCardProps) {
  const setProviderEnabled = useOidcProviderStore((s) => s.setProviderEnabled)
  const makeProviderDefault = useOidcProviderStore((s) => s.makeProviderDefault)
  const deleteExistingProvider = useOidcProviderStore((s) => s.deleteExistingProvider)
  const moveProvider = useOidcProviderStore((s) => s.moveProvider)
  const [localError, setLocalError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<unknown>, fallback: string) {
    setLocalError(null)
    setBusy(true)
    try {
      await action()
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : fallback)
    } finally {
      setBusy(false)
    }
  }

  function toggleEnabled() {
    if (
      provider.enabled &&
      !window.confirm(`„${provider.displayName}“ deaktivieren?\n\n${DISABLE_CONSEQUENCE}`)
    ) {
      return
    }
    void run(async () => {
      await setProviderEnabled(provider.id, !provider.enabled)
      notify(
        provider.enabled
          ? `„${provider.displayName}“ wurde deaktiviert.`
          : `„${provider.displayName}“ wurde aktiviert.`,
        'success',
      )
    }, 'Änderung fehlgeschlagen')
  }

  function makeDefault() {
    if (
      !window.confirm(
        `„${provider.displayName}“ zum Standardanbieter machen?\n\n${DEFAULT_CONSEQUENCE}`,
      )
    ) {
      return
    }
    void run(async () => {
      await makeProviderDefault(provider.id)
      notify(`„${provider.displayName}“ ist jetzt der Standardanbieter.`, 'success')
    }, 'Änderung fehlgeschlagen')
  }

  function remove() {
    if (!window.confirm(`„${provider.displayName}“ löschen?\n\n${DELETE_CONSEQUENCE}`)) {
      return
    }
    void run(async () => {
      await deleteExistingProvider(provider.id)
      notify(`„${provider.displayName}“ wurde gelöscht.`, 'success')
    }, 'Löschen fehlgeschlagen')
  }

  const rolesManaged = (provider.claimMapping.rolesClaim ?? null) !== null
  const groupsClaim = provider.claimMapping.groupsClaim
  const titleId = `oidc-provider-${provider.id}-title`

  return (
    <Box
      component="article"
      aria-labelledby={titleId}
      data-testid={`oidc-provider-card-${provider.id}`}
      sx={{
        border: 1,
        borderColor: 'divider',
        borderRadius: `${radius.md}px`,
        bgcolor: 'background.paper',
        p: { xs: 2, md: 2.5 },
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Box
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            flex: 'none',
            width: 22,
            height: 22,
            borderRadius: `${radius.xs}px`,
            bgcolor: (t) => alpha(t.palette.text.primary, 0.05),
            color: 'text.secondary',
            fontFamily: fontFamily.mono,
            fontSize: 11,
            fontWeight: 500,
          }}
        >
          <span aria-hidden="true">{position}</span>
          <span style={visuallyHidden}>Position {position} auf der Anmeldeseite</span>
        </Box>
        <ProviderMonogram
          name={provider.displayName}
          tone={provider.enabled ? 'accent' : 'muted'}
        />
        <Box sx={{ minWidth: 0, flex: '1 1 240px' }}>
          <Typography
            id={titleId}
            component="h2"
            sx={{ fontSize: 15, fontWeight: 600, lineHeight: 1.25, m: 0 }}
          >
            {provider.displayName}
          </Typography>
          <StateLine provider={provider} />
        </Box>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', ml: 'auto' }}>
          {provider.isDefault && (
            <Chip
              label="Standard"
              color="primary"
              size="small"
              icon={<StarRoundedIcon />}
              aria-label="Standardanbieter"
            />
          )}
          <Stack direction="row">
            <Tooltip title="Nach oben">
              <span>
                <IconButton
                  size="small"
                  aria-label={`„${provider.displayName}“ nach oben verschieben`}
                  disabled={isFirst || busy}
                  onClick={() =>
                    void run(() => moveProvider(provider.id, 'up'), 'Verschieben fehlgeschlagen')
                  }
                >
                  <ArrowUpwardIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="Nach unten">
              <span>
                <IconButton
                  size="small"
                  aria-label={`„${provider.displayName}“ nach unten verschieben`}
                  disabled={isLast || busy}
                  onClick={() =>
                    void run(() => moveProvider(provider.id, 'down'), 'Verschieben fehlgeschlagen')
                  }
                >
                  <ArrowDownwardIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        </Stack>
      </Stack>

      <Box
        component="dl"
        sx={{
          m: 0,
          mt: 2,
          pt: 2,
          borderTop: 1,
          borderColor: 'divider',
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr',
            sm: 'repeat(2, minmax(0, 1fr))',
            md: 'repeat(3, minmax(0, 1fr))',
          },
          columnGap: 3,
          rowGap: 2,
        }}
      >
        <MetaItem icon={<LinkOutlinedIcon />} label="Issuer-URI">
          <Mono>{provider.issuerUri}</Mono>
        </MetaItem>
        <MetaItem icon={<BadgeOutlinedIcon />} label="Client-ID">
          <Mono>{provider.clientId}</Mono>
        </MetaItem>
        <MetaItem icon={<KeyOutlinedIcon />} label="JWK-Set">
          {provider.jwkSetUri ? (
            <Mono>{provider.jwkSetUri}</Mono>
          ) : (
            'Über das Discovery-Dokument des Anbieters'
          )}
        </MetaItem>
        <MetaItem icon={<GroupsOutlinedIcon />} label="Gruppen">
          {groupsClaim ? (
            <>
              Aus <Mono>{groupsClaim}</Mono>, bei jeder Anmeldung übernommen
            </>
          ) : (
            'Keine Gruppen aus dem Token'
          )}
        </MetaItem>
        <MetaItem icon={<TuneOutlinedIcon />} label="Claim-Zuordnung" span>
          {claimSummary(provider)}
          {rolesManaged && (
            <Box component="span" sx={{ display: 'inline-block', ml: 1 }}>
              <MetaBadge accent>Rollen aus dem Token</MetaBadge>
            </Box>
          )}
        </MetaItem>
      </Box>

      {provider.enabled && provider.registryState !== 'READY' && provider.registryMessage && (
        <Alert severity="warning" sx={{ mt: 2 }}>
          {provider.registryMessage}
        </Alert>
      )}
      {localError && (
        <Alert severity="error" sx={{ mt: 2 }} onClose={() => setLocalError(null)}>
          {localError}
        </Alert>
      )}

      <Stack
        direction="row"
        spacing={1}
        sx={{
          mt: 2,
          pt: 2,
          borderTop: 1,
          borderColor: 'divider',
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Button
          size="small"
          variant="outlined"
          startIcon={<EditOutlinedIcon />}
          onClick={() => onEdit(provider)}
          disabled={busy}
        >
          Bearbeiten
        </Button>
        {!provider.isDefault && (
          <Button
            size="small"
            startIcon={<PowerSettingsNewOutlinedIcon />}
            onClick={toggleEnabled}
            disabled={busy}
          >
            {provider.enabled ? 'Deaktivieren' : 'Aktivieren'}
          </Button>
        )}
        {!provider.isDefault && provider.enabled && (
          <Button
            size="small"
            startIcon={<StarOutlineRoundedIcon />}
            onClick={makeDefault}
            disabled={busy}
          >
            Zum Standard machen
          </Button>
        )}
        {!provider.isDefault && (
          <Button
            size="small"
            color="error"
            startIcon={<DeleteOutlineIcon />}
            onClick={remove}
            disabled={busy}
            sx={{ ml: { sm: 'auto' } }}
          >
            Löschen
          </Button>
        )}
      </Stack>
      {provider.isDefault && (
        <Stack
          direction="row"
          spacing={1}
          sx={{ mt: 1.5, alignItems: 'flex-start', color: 'text.secondary' }}
        >
          <InfoOutlinedIcon aria-hidden="true" sx={{ fontSize: 16, mt: '1px', flex: 'none' }} />
          <Typography sx={{ fontSize: 12.5, lineHeight: 1.5 }}>
            Standardanbieter: Erstadministrator-Regel und Verzeichnisabgleich gelten für seine
            Konten. Er kann erst deaktiviert oder gelöscht werden, wenn ein anderer Anbieter zum
            Standard gemacht wurde.
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
