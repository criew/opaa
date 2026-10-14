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
import type { LlmModelResponse } from '@/shared/api/types'
import { fontFamily } from '@/shared/theme/tokens'
import { MODEL_TABLE_SX } from './modelTableStyles'
import ModelRowMenu from './ModelRowMenu'

interface ModelListProps {
  models: LlmModelResponse[]
  onEdit: (model: LlmModelResponse) => void
}

/** Dot plus word - the colour never carries alone, the same rule as in `StatusLine`. */
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
 * The chat models as a table — one row per model, the actions in the row menu.
 *
 * Temperature and maximum response length deliberately have no column: they are fine tuning
 * nobody skims a list for, and are in the dialog. An overview that shows everything shows
 * nothing.
 */
/** The same details stacked - on a narrow window a five-column table is
 *  not readable, and the page must not scroll horizontally for it (pattern of the account list). */
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
    // The endpoints can be any length; instead of bursting the row, the table scrolls horizontally.
    // `tabIndex` and `role` make the scrollable area reachable by keyboard - without them
    // the right columns could only be reached with the mouse (pattern of LibraryStatusTable).
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Chat-Modelle, horizontal scrollbar"
    >
      <Table size="small" aria-label="Chat-Modelle" sx={{ minWidth: 720, ...MODEL_TABLE_SX }}>
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
