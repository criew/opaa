import Box from '@mui/material/Box'
import type { ConnectorTypeStateResponse, SourceTypeKey } from '@/shared/api/types'
import StateDot from '@/shared/ui/StateDot'
import SourceTypeIcon from './sourceTypeIcon'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { profileRuleLabel } from './connectorTypeState'
import { iconSize } from '@/shared/theme/tokens'

export interface ConnectorTypeEntry {
  state: ConnectorTypeStateResponse
  /** null while the profiles are unknown. */
  profileCount: number | null
}

interface OverviewProps {
  entries: ConnectorTypeEntry[]
  onOpen: (sourceType: string) => void
}

function NameCell({ state }: { state: ConnectorTypeStateResponse }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, minWidth: 0 }}>
      <Box aria-hidden="true" sx={{ display: 'flex', color: 'text.secondary' }}>
        <SourceTypeIcon sourceType={state.sourceType as SourceTypeKey} fontSize={iconSize.md} />
      </Box>
      <ListText strong>{state.displayName}</ListText>
    </Box>
  )
}

function LockCell({ state }: { state: ConnectorTypeStateResponse }) {
  return state.locked ? (
    <StateDot tone="error" label="Gesperrt" strong />
  ) : (
    <StateDot tone="success" label="Nutzbar" />
  )
}

function profileCountLabel(entry: ConnectorTypeEntry): string {
  if (entry.state.profileSupport === 'FORBIDDEN') return '—'
  if (entry.profileCount === null) return 'nicht geladen'
  return entry.profileCount === 1 ? '1 Zugang' : `${entry.profileCount} Zugänge`
}

/**
 * Every source type of the installation on one screen: whether it may be used, how it relates to
 * profiles and how many it has. The row itself leads into the side panel, where locks and „Nur
 * über Zugänge" change.
 */
export default function ConnectorTypeOverview({ entries, onOpen }: OverviewProps) {
  return (
    <AdminList
      label="Quellarten"
      rows={entries}
      rowKey={(entry) => entry.state.sourceType}
      rowLabel={(entry) => entry.state.displayName}
      minWidth={760}
      rowTarget={(entry) => ({ onOpen: () => onOpen(entry.state.sourceType) })}
      rowTone={(entry) => (entry.state.locked ? 'error' : null)}
      columns={[
        {
          id: 'type',
          head: 'Quellart',
          width: '32%',
          cell: (entry) => <NameCell state={entry.state} />,
        },
        {
          id: 'state',
          head: 'Zustand',
          width: '18%',
          cell: (entry) => <LockCell state={entry.state} />,
        },
        { id: 'rule', head: 'Zugänge', cell: (entry) => profileRuleLabel(entry.state) },
        {
          id: 'count',
          head: 'Angelegt',
          width: 128,
          align: 'right',
          cell: (entry) => profileCountLabel(entry),
        },
      ]}
    />
  )
}
