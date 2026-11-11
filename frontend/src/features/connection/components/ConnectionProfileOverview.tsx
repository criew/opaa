import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { ConnectionProfileResponse } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'
import StateDot from '@/shared/ui/StateDot'
import { AccessBadge } from '@/features/capability'
import { accessBadgeLabel, type Access } from '@/features/capability'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { hostOf, type ProfileAttention } from './profileState'
import { fontSize } from '@/shared/theme/tokens'

export interface ProfileEntry {
  profile: ConnectionProfileResponse
  sourceTypeName: string
  typeLocked: boolean
  /** null while the release is unknown. */
  access: Access | null
  attention: ProfileAttention | null
}

interface OverviewProps {
  entries: ProfileEntry[]
  onOpen: (profileId: string) => void
}

/** Source type and at most one lock badge: a cell carries no more than two (guidelines 5.3). */
function ProfileCell({ entry }: { entry: ProfileEntry }) {
  const lock =
    entry.profile.locked && entry.typeLocked
      ? 'Zugang und Quellart gesperrt'
      : entry.profile.locked
        ? 'Gesperrt'
        : entry.typeLocked
          ? 'Quellart gesperrt'
          : null
  return (
    <Box sx={{ minWidth: 0 }}>
      <ListText strong>{entry.profile.name}</ListText>
      <Box sx={{ display: 'flex', gap: 0.75, mt: 0.5, overflow: 'hidden' }}>
        <MetaBadge>{entry.sourceTypeName}</MetaBadge>
        {lock && <MetaBadge>{lock}</MetaBadge>}
      </Box>
    </Box>
  )
}

function AccessCell({ access }: { access: Access | null }) {
  if (!access) {
    return (
      <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
        nicht geladen
      </Typography>
    )
  }
  return <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
}

function AttentionCell({ attention }: { attention: ProfileAttention | null }) {
  if (!attention) {
    return (
      <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
        <span aria-hidden="true">—</span>
        <span style={visuallyHidden}>nichts zu tun</span>
      </Typography>
    )
  }
  return (
    <Box sx={{ minWidth: 0 }}>
      <StateDot tone={attention.tone} label={attention.label} strong />
      <ListText secondary>{attention.detail}</ListText>
    </Box>
  )
}

/**
 * The profiles of the installation on one screen: source type and state, host, who may use it,
 * its connections and what needs doing - one row each. The row itself leads into the side panel
 * with details and every change; a row with something to do carries a bar in the tone of it.
 */
export default function ConnectionProfileOverview({ entries, onOpen }: OverviewProps) {
  return (
    <AdminList
      label="Zugänge"
      rows={entries}
      rowKey={(entry) => entry.profile.id}
      rowLabel={(entry) => entry.profile.name}
      minWidth={880}
      rowTarget={(entry) => ({ onOpen: () => onOpen(entry.profile.id) })}
      rowTone={(entry) => entry.attention?.tone ?? null}
      columns={[
        {
          id: 'profile',
          head: 'Zugang',
          width: '26%',
          cell: (entry) => <ProfileCell entry={entry} />,
        },
        {
          id: 'server',
          head: 'Server',
          width: '18%',
          cell: (entry) => (
            <Box component="span" title={entry.profile.serverUrl ?? undefined}>
              {hostOf(entry.profile.serverUrl)}
            </Box>
          ),
        },
        {
          id: 'access',
          head: 'Nutzbar für',
          width: 200,
          cell: (entry) => <AccessCell access={entry.access} />,
        },
        {
          id: 'connections',
          head: 'Verbindungen',
          width: 128,
          align: 'right',
          cell: (entry) => entry.profile.connectionCount,
        },
        {
          id: 'attention',
          head: 'Handlungsbedarf',
          cell: (entry) => <AttentionCell attention={entry.attention} />,
        },
      ]}
    />
  )
}
