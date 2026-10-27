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
 * The installed embedding model in the same table form as the chat models, without a row menu:
 * a change goes through the guided change below the table, never through an edit in place.
 *
 * A table for a single row is typographically the weaker choice; here recognition weighs more.
 * Whoever switches between the tabs sees the same kind of thing — a model with its details.
 */
export default function EmbeddingInfoTable({ info }: EmbeddingInfoTableProps) {
  return (
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Einbettungsmodell, horizontal scrollbar"
    >
      <Table size="small" aria-label="Einbettungsmodell" sx={{ minWidth: 640, ...MODEL_TABLE_SX }}>
        <TableHead>
          <TableRow>
            <TableCell>Modell</TableCell>
            <TableCell>Endpunkt</TableCell>
            <TableCell sx={{ width: '14%' }}>Dimensionen</TableCell>
            <TableCell sx={{ width: '16%' }}>Eingabegrenze</TableCell>
            <TableCell sx={{ width: '14%' }}>Schlüssel</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          <TableRow>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.model}
              </Box>
            </TableCell>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.baseUrl ?? '—'}
              </Box>
            </TableCell>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.dimensions}
              </Box>
            </TableCell>
            <TableCell>
              <Box component="span" sx={MACHINE_VALUE_SX}>
                {info.maxInputTokens ?? '—'}
              </Box>
            </TableCell>
            <TableCell>{info.apiKeySet ? 'gesetzt' : 'keiner'}</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </TableContainer>
  )
}
