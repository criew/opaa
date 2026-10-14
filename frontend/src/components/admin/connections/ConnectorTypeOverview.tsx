import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { ConnectorTypeStateResponse, SourceTypeKey } from '@/shared/api/types'
import StateDot from '@/shared/ui/StateDot'
import SourceTypeIcon from './sourceTypeIcon'
import { adminTableSx, definitionListSx, listCardSx } from '@/shared/ui/list/adminListStyles'
import { profileRuleLabel } from './connectorTypeState'

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
        <SourceTypeIcon sourceType={state.sourceType as SourceTypeKey} fontSize={18} />
      </Box>
      <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
        {state.displayName}
      </Typography>
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

function OpenButton({
  entry,
  onOpen,
}: {
  entry: ConnectorTypeEntry
  onOpen: OverviewProps['onOpen']
}) {
  return (
    <Button
      size="small"
      variant="outlined"
      aria-label={`Details: ${entry.state.displayName}`}
      onClick={(event) => {
        event.stopPropagation()
        onOpen(entry.state.sourceType)
      }}
    >
      Details
    </Button>
  )
}

/**
 * Every source type of the installation on one screen: whether it may be used, how it relates to
 * profiles and how many it has. Locks and „Nur über Zugänge" change in the side panel.
 */
export default function ConnectorTypeOverview({ entries, onOpen }: OverviewProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  if (!isDesktop) {
    return (
      <Box
        component="ul"
        aria-label="Quellarten"
        sx={{
          listStyle: 'none',
          p: 0,
          m: 0,
          borderTop: 1,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        {entries.map((entry) => (
          <Box component="li" key={entry.state.sourceType} sx={listCardSx}>
            <Box component="article" aria-label={entry.state.displayName}>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <NameCell state={entry.state} />
                </Box>
                <OpenButton entry={entry} onOpen={onOpen} />
              </Box>
              <Box component="dl" sx={definitionListSx}>
                <dt>Zustand</dt>
                <dd>
                  <LockCell state={entry.state} />
                </dd>
                <dt>Zugänge</dt>
                <dd>{profileRuleLabel(entry.state)}</dd>
                <dt>Angelegt</dt>
                <dd>{profileCountLabel(entry)}</dd>
              </Box>
            </Box>
          </Box>
        ))}
      </Box>
    )
  }

  return (
    <TableContainer>
      <Table size="small" aria-label="Quellarten" sx={{ ...adminTableSx, minWidth: 760 }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: '30%' }}>Quellart</TableCell>
            <TableCell sx={{ width: '17%' }}>Zustand</TableCell>
            <TableCell>Zugänge</TableCell>
            <TableCell align="right" sx={{ width: 120 }}>
              Angelegt
            </TableCell>
            <TableCell align="right" sx={{ width: 112 }}>
              <span style={visuallyHidden}>Öffnen</span>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow
              key={entry.state.sourceType}
              hover
              onClick={() => onOpen(entry.state.sourceType)}
              sx={{
                cursor: 'pointer',
                ...(entry.state.locked && {
                  boxShadow: (t) => `inset 3px 0 0 ${t.palette.error.main}`,
                }),
              }}
            >
              <TableCell>
                <NameCell state={entry.state} />
              </TableCell>
              <TableCell>
                <LockCell state={entry.state} />
              </TableCell>
              <TableCell>{profileRuleLabel(entry.state)}</TableCell>
              <TableCell align="right">{profileCountLabel(entry)}</TableCell>
              <TableCell align="right">
                <OpenButton entry={entry} onOpen={onOpen} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
