import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { EmbeddingInfoResponse } from '@/shared/api/types'
import { MACHINE_VALUE_SX, MODEL_TABLE_SX } from './modelTableStyles'

interface EmbeddingInfoTableProps {
  info: EmbeddingInfoResponse
}

/**
 * The embedding model in the same table form as the chat models — **without any
 * action**: no action column, no row menu, no control.
 *
 * A table for a single row is typographically the weaker choice; here recognition weighs
 * more. Whoever switches between the tabs sees the same kind of thing — a
 * model with its details — and that the difference lies not in the matter but in
 * the fact that nothing can be changed on this one.
 *
 * There are no more than these three details: `EmbeddingInfoResponse` carries neither an endpoint
 * nor a key status.
 */
export default function EmbeddingInfoTable({ info }: EmbeddingInfoTableProps) {
  return (
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Einbettungsmodell, horizontal scrollbar"
    >
      <Table size="small" aria-label="Einbettungsmodell" sx={{ minWidth: 420, ...MODEL_TABLE_SX }}>
        <TableHead>
          <TableRow>
            <TableCell>Modell</TableCell>
            <TableCell sx={{ width: '28%' }}>Anbieter</TableCell>
            <TableCell sx={{ width: '22%' }}>Dimensionen</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          <TableRow>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.model}
              </Box>
            </TableCell>
            <TableCell>{info.provider}</TableCell>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.dimensions}
              </Box>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </TableContainer>
  )
}
