import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import type { OidcProviderResponse } from '@/shared/api/types'
import { fontFamily, fontSize } from '@/shared/theme/tokens'
import { apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import ProviderMonogram from '@/shared/ui/ProviderMonogram'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
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
  const roles = rolesClaim
    ? `Rollen aus dem Token: ${rollenwerte.length > 0 ? rollenwerte.join(', ') : 'keine Rollenwerte gesetzt'}`
    : 'Rollen in OPAA verwaltet'
  return (
    <>
      <ListText>{roles}</ListText>
      <ListText secondary>
        {groupsClaim ? `Gruppen aus ${groupsClaim}` : 'Keine Gruppen aus dem Token'}
      </ListText>
    </>
  )
}

function NameCell({ provider }: { provider: OidcProviderResponse }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25, minWidth: 0 }}>
      <ProviderMonogram
        name={provider.displayName}
        size={28}
        tone={provider.enabled ? 'accent' : 'muted'}
      />
      <Box sx={{ minWidth: 0 }}>
        <ListText strong>{provider.displayName}</ListText>
        <ListText mono secondary>
          {provider.issuerUri}
        </ListText>
      </Box>
    </Box>
  )
}

/**
 * State in one line, „Standard" beside it. Why a provider is unreachable - its answer, verbatim -
 * stands below: „Nicht erreichbar" alone does not say whether the address is wrong or the
 * service is down.
 */
function StateCell({ provider }: { provider: OidcProviderResponse }) {
  const state = providerState(provider)
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}>
        <ProviderStateDot state={state} />
        <Box component="span">{PROVIDER_STATE_LABEL[state]}</Box>
        {isPerInstance(state) && (
          <Box component="span" sx={{ color: 'text.secondary', fontSize: fontSize.meta }}>
            · {PER_INSTANCE_NOTE}
          </Box>
        )}
        {provider.isDefault && <MetaBadge ariaLabel="Standardanbieter">Standard</MetaBadge>}
      </Box>
      {state === 'unreachable' && <ListText secondary>{provider.registryMessage}</ListText>}
    </Box>
  )
}

interface ProviderRowProps {
  provider: OidcProviderResponse
  isFirst: boolean
  isLast: boolean
  onMove: (provider: OidcProviderResponse, direction: 'up' | 'down') => void
}

/** The order buttons; the same names in both presentations. */
function OrderButtons({ provider, isFirst, isLast, onMove }: ProviderRowProps) {
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

  return (
    <AdminList
      label="Identitätsanbieter"
      rows={providers}
      rowKey={(provider) => provider.id}
      rowLabel={(provider) => provider.displayName}
      minWidth={860}
      columns={[
        {
          id: 'position',
          head: 'Nr.',
          headNote: 'auf der Anmeldeseite',
          width: 52,
          card: 'hidden',
          cell: (_provider, index) => (
            <Box component="span" sx={{ color: 'text.secondary', fontFamily: fontFamily.mono }}>
              {index + 1}
            </Box>
          ),
        },
        {
          id: 'provider',
          head: 'Anbieter',
          card: 'title',
          cell: (provider) => <NameCell provider={provider} />,
        },
        {
          id: 'state',
          head: 'Zustand',
          width: '21%',
          cell: (provider) => <StateCell provider={provider} />,
        },
        {
          id: 'clientId',
          head: 'Client-ID',
          width: '15%',
          cell: (provider) => <ListText mono>{provider.clientId}</ListText>,
        },
        {
          id: 'claims',
          head: 'Aus dem Token',
          width: '22%',
          cell: (provider) => <ClaimCell provider={provider} />,
        },
      ]}
      actionsWidth={128}
      rowActions={(provider, index) => (
        <>
          <OrderButtons
            provider={provider}
            isFirst={index === 0}
            isLast={index === providers.length - 1}
            onMove={(p, richtung) => void move(p, richtung)}
          />
          <ProviderRowMenu
            provider={provider}
            isLastEnabled={provider.enabled && enabledCount === 1}
            canDisable={!provider.isDefault || enabledCount <= 1}
            canDelete={!provider.isDefault || providers.length === 1}
            onEdit={onEdit}
          />
        </>
      )}
    />
  )
}
