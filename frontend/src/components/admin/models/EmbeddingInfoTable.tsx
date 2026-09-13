import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import type { EmbeddingInfoResponse } from '../../../types/api'
import { MACHINE_VALUE_SX, MODEL_TABLE_SX } from './modelTableStyles'

interface EmbeddingInfoTableProps {
  info: EmbeddingInfoResponse
}

/**
 * Das Einbettungsmodell in derselben Tabellenform wie die Chat-Modelle (#1623) — **ohne jede
 * Handlung**: keine Aktionsspalte, kein Zeilenmenü, kein Bedienelement.
 *
 * Eine Tabelle für eine einzige Zeile ist typografisch die schwächere Wahl; hier wiegt die
 * Wiedererkennung schwerer. Wer zwischen den Reitern wechselt, sieht dieselbe Art von Ding — ein
 * Modell mit seinen Angaben — und dass der Unterschied nicht in der Sache liegt, sondern darin,
 * dass an diesem nichts zu ändern ist.
 *
 * Mehr als diese drei Angaben gibt es nicht: `EmbeddingInfoResponse` trägt weder einen Endpunkt
 * noch einen Schlüsselstatus.
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
