import type { AgentDirectoryResponsibility } from '@/shared/api/types'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { formatDate, formatNumber, MISSING_VALUE } from '@/shared/utils/formatDate'
import { responsibilityLabel } from './agentDirectoryTexts'

export interface DirectoryRow {
  id: string
  name: string
  description?: string | null
  responsibility: AgentDirectoryResponsibility
  revision: number
  locked: boolean
  lockedSince?: string | null
}

/**
 * One list of the agent directory - agents or templates alike: name, purpose, responsible unit,
 * revision and since when locked. The whole row leads to the read-only detail.
 */
export default function AgentDirectoryTable({
  label,
  rows,
  routeOf,
}: {
  label: string
  rows: DirectoryRow[]
  routeOf: (id: string) => string
}) {
  return (
    <AdminList<DirectoryRow>
      label={label}
      rows={rows}
      rowKey={(row) => row.id}
      rowLabel={(row) => row.name}
      rowTarget={(row) => ({ to: routeOf(row.id) })}
      minWidth={640}
      columns={[
        {
          id: 'name',
          head: 'Name',
          width: '28%',
          card: 'title',
          cell: (row) => <ListText strong>{row.name}</ListText>,
        },
        {
          id: 'purpose',
          head: 'Zweck',
          cell: (row) => <ListText>{row.description ?? MISSING_VALUE}</ListText>,
        },
        {
          id: 'responsibility',
          head: 'Verantwortlich',
          width: '22%',
          cell: (row) => <ListText>{responsibilityLabel(row.responsibility)}</ListText>,
        },
        {
          id: 'revision',
          head: 'Fassung',
          width: 88,
          align: 'right',
          cell: (row) => formatNumber(row.revision),
        },
        {
          id: 'lockedSince',
          head: 'Gesperrt seit',
          width: 132,
          cell: (row) => (row.locked ? formatDate(row.lockedSince) : MISSING_VALUE),
        },
      ]}
    />
  )
}
