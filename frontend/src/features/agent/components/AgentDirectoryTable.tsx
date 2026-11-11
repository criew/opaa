import { useNavigate } from 'react-router'
import Button from '@mui/material/Button'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { AgentDirectoryResponsibility } from '@/shared/api/types'
import { adminTableSx } from '@/shared/ui/list/adminListStyles'
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
 * revision and since when locked. The whole row leads to the read-only detail; the name is the
 * keyboard's way there.
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
  const navigate = useNavigate()
  return (
    <Table size="small" aria-label={label} sx={adminTableSx}>
      <TableHead>
        <TableRow>
          <TableCell sx={{ width: '28%' }}>Name</TableCell>
          <TableCell>Zweck</TableCell>
          <TableCell sx={{ width: '22%' }}>Verantwortlich</TableCell>
          <TableCell sx={{ width: 72 }} align="right">
            Fassung
          </TableCell>
          <TableCell sx={{ width: 120 }}>Gesperrt seit</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row) => (
          <TableRow
            key={row.id}
            hover
            sx={{ cursor: 'pointer' }}
            onClick={() => void navigate(routeOf(row.id))}
          >
            <TableCell>
              <Button
                variant="text"
                size="small"
                sx={{ p: 0, minWidth: 0, textAlign: 'left', justifyContent: 'flex-start' }}
                onClick={(event) => {
                  event.stopPropagation()
                  void navigate(routeOf(row.id))
                }}
              >
                {row.name}
              </Button>
            </TableCell>
            <TableCell>{row.description ?? MISSING_VALUE}</TableCell>
            <TableCell>{responsibilityLabel(row.responsibility)}</TableCell>
            <TableCell align="right">{formatNumber(row.revision)}</TableCell>
            <TableCell>{row.locked ? formatDate(row.lockedSince) : MISSING_VALUE}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
