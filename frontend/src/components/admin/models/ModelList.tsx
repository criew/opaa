import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import TableContainer from '@mui/material/TableContainer'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { LlmModelResponse } from '../../../types/api'
import { fontFamily } from '../../../theme/tokens'
import ModelRowMenu from './ModelRowMenu'

interface ModelListProps {
  models: LlmModelResponse[]
  onEdit: (model: LlmModelResponse) => void
}

/** Punkt plus Wort - die Farbe trägt nie allein, dieselbe Regel wie in `StatusLine`. */
function StateCell({ active }: { active: boolean }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
      <Box
        aria-hidden
        sx={{
          width: 7,
          height: 7,
          borderRadius: '50%',
          flex: 'none',
          bgcolor: active ? 'success.main' : 'text.disabled',
          transform: 'translateY(-1px)',
        }}
      />
      <Box component="span">{active ? 'Aktiv' : 'Nicht aktiv'}</Box>
    </Box>
  )
}

/**
 * Die Chat-Modelle als Tabelle (#1621) — eine Zeile je Modell, die Handlungen im Zeilenmenü.
 *
 * Temperatur und maximale Antwortlänge haben bewusst keine Spalte: Sie sind Feineinstellung, nach
 * der niemand eine Liste überfliegt, und stehen im Dialog. Eine Übersicht, die alles zeigt, zeigt
 * nichts.
 */
/** Dieselben Angaben untereinander - auf einem schmalen Fenster ist eine fünfspaltige Tabelle
 *  nicht lesbar, und die Seite dürfte dafür nicht waagerecht rollen (Muster der Kontenliste). */
function ModelCard({
  model,
  onEdit,
}: {
  model: LlmModelResponse
  onEdit: ModelListProps['onEdit']
}) {
  return (
    <Box sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{model.displayName}</Typography>
          <Typography
            sx={{
              fontSize: 12,
              color: 'text.secondary',
              fontFamily: fontFamily.mono,
              overflowWrap: 'anywhere',
            }}
          >
            {model.modelIdentifier}
          </Typography>
        </Box>
        <ModelRowMenu model={model} onEdit={onEdit} />
      </Box>
      <Box sx={{ mt: 0.75, fontSize: 12.5 }}>
        <StateCell active={model.active} />
      </Box>
      <Typography
        sx={{
          fontSize: 12,
          color: 'text.secondary',
          fontFamily: fontFamily.mono,
          overflowWrap: 'anywhere',
          mt: 0.5,
        }}
      >
        {model.baseUrl}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.25 }}>
        {model.apiKeySet ? 'Schlüssel hinterlegt' : 'Ohne Schlüssel'}
      </Typography>
    </Box>
  )
}

export default function ModelList({ models, onEdit }: ModelListProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  if (!isDesktop) {
    return (
      <Stack spacing={0}>
        {models.map((model) => (
          <ModelCard key={model.id} model={model} onEdit={onEdit} />
        ))}
      </Stack>
    )
  }

  return (
    // Die Endpunkte sind beliebig lang; statt die Zeile zu sprengen, rollt die Tabelle waagerecht.
    // `tabIndex` und `role` machen den rollbaren Bereich mit der Tastatur erreichbar - ohne sie
    // käme man an die rechten Spalten nur mit der Maus (Muster von LibraryStatusTable).
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Chat-Modelle, horizontal scrollbar"
    >
      <Table
        size="small"
        aria-label="Chat-Modelle"
        sx={{
          minWidth: 720,
          tableLayout: 'fixed',
          '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
          '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
        }}
      >
        <TableHead>
          <TableRow>
            <TableCell>Modell</TableCell>
            <TableCell sx={{ width: '32%' }}>Endpunkt</TableCell>
            <TableCell sx={{ width: '15%' }}>Zustand</TableCell>
            <TableCell sx={{ width: '15%' }}>Zugang</TableCell>
            <TableCell align="right" sx={{ width: 56 }}>
              <span style={visuallyHidden}>Aktionen</span>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {models.map((model) => (
            <TableRow key={model.id}>
              <TableCell>
                <Typography component="span" sx={{ fontSize: 13.5, fontWeight: 600 }}>
                  {model.displayName}
                </Typography>
                <Typography
                  sx={{
                    fontSize: 12,
                    color: 'text.secondary',
                    fontFamily: fontFamily.mono,
                    overflowWrap: 'anywhere',
                  }}
                >
                  {model.modelIdentifier}
                </Typography>
              </TableCell>
              <TableCell>
                <Tooltip title={model.baseUrl}>
                  <Box
                    component="span"
                    sx={{
                      fontFamily: fontFamily.mono,
                      fontSize: 12,
                      display: 'block',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {model.baseUrl}
                  </Box>
                </Tooltip>
              </TableCell>
              <TableCell>
                <StateCell active={model.active} />
              </TableCell>
              <TableCell>{model.apiKeySet ? 'Schlüssel hinterlegt' : 'Ohne Schlüssel'}</TableCell>
              <TableCell align="right">
                <ModelRowMenu model={model} onEdit={onEdit} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
