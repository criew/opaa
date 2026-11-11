import { useNavigate } from 'react-router'
import Link from '@mui/material/Link'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { AgentDirectoryResponsibility } from '@/shared/api/types'
import { adminTableSx } from '@/shared/ui/list/adminListStyles'
import { fontSize } from '@/shared/theme/tokens'
import { formatDate, formatNumber, MISSING_VALUE } from '@/shared/utils/formatDate'
import { responsibilityLabel } from './agentDirectoryTexts'

/** Cells never wrap: a long value is cut off and named in full by its tooltip. */
const oneLineSx = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } as const

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
              <Link
                href={routeOf(row.id)}
                underline="none"
                title={row.name}
                onClick={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  void navigate(routeOf(row.id))
                }}
                sx={{
                  display: 'block',
                  fontSize: fontSize.text,
                  fontWeight: 600,
                  color: 'text.primary',
                  ...oneLineSx,
                }}
              >
                {row.name}
              </Link>
            </TableCell>
            <TableCell sx={oneLineSx} title={row.description ?? undefined}>
              {row.description ?? MISSING_VALUE}
            </TableCell>
            <TableCell sx={oneLineSx} title={responsibilityLabel(row.responsibility)}>
              {responsibilityLabel(row.responsibility)}
            </TableCell>
            <TableCell align="right">{formatNumber(row.revision)}</TableCell>
            <TableCell>{row.locked ? formatDate(row.lockedSince) : MISSING_VALUE}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
