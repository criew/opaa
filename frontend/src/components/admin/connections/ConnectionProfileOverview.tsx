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
import type { ConnectionProfileResponse } from '../../../types/api'
import MetaBadge from '../../MetaBadge'
import StateDot from '../../StateDot'
import AccessBadge from '../capabilities/AccessBadge'
import { accessBadgeLabel, type Access } from '../capabilities/capabilityAccess'
import { adminTableSx, definitionListSx, listCardSx } from '../list/adminListStyles'
import { hostOf, type ProfileAttention } from './profileState'

export interface ProfileEntry {
  profile: ConnectionProfileResponse
  sourceTypeName: string
  typeLocked: boolean
  /** null while the release is unknown. */
  access: Access | null
  attention: ProfileAttention | null
}

interface OverviewProps {
  entries: ProfileEntry[]
  onOpen: (profileId: string) => void
}

const secondarySx = { fontSize: 12.5, color: 'text.secondary', mt: 0.25, pl: 1.75 } as const

function ProfileCell({ entry }: { entry: ProfileEntry }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
        {entry.profile.name}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 0.75 }}>
        <MetaBadge>{entry.sourceTypeName}</MetaBadge>
        {entry.profile.locked && <MetaBadge>Gesperrt</MetaBadge>}
        {entry.typeLocked && <MetaBadge>Quellart gesperrt</MetaBadge>}
      </Box>
    </Box>
  )
}

function ServerCell({ profile }: { profile: ConnectionProfileResponse }) {
  return (
    <Typography
      component="span"
      title={profile.serverUrl ?? undefined}
      sx={{
        fontSize: 13,
        display: 'block',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
      }}
    >
      {hostOf(profile.serverUrl)}
    </Typography>
  )
}

function AccessCell({ access }: { access: Access | null }) {
  if (!access) {
    return (
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
        nicht geladen
      </Typography>
    )
  }
  return <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
}

function AttentionCell({ attention }: { attention: ProfileAttention | null }) {
  if (!attention) {
    return (
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
        <span aria-hidden="true">—</span>
        <span style={visuallyHidden}>nichts zu tun</span>
      </Typography>
    )
  }
  return (
    <>
      <StateDot tone={attention.tone} label={attention.label} strong />
      <Typography
        title={attention.detail}
        sx={{
          ...secondarySx,
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {attention.detail}
      </Typography>
    </>
  )
}

/** The one way into a profile; a profile with something to do says so on its button. */
function OpenButton({ entry, onOpen }: { entry: ProfileEntry; onOpen: OverviewProps['onOpen'] }) {
  const verb = entry.attention ? 'Beheben' : 'Details'
  return (
    <Button
      size="small"
      variant={entry.attention ? 'contained' : 'outlined'}
      aria-label={`${verb}: ${entry.profile.name}`}
      onClick={(event) => {
        event.stopPropagation()
        onOpen(entry.profile.id)
      }}
    >
      {verb}
    </Button>
  )
}

/** Narrow windows: the same statements one under the other, as a list entry - not a card. */
function OverviewList({ entries, onOpen }: OverviewProps) {
  return (
    <Box
      component="ul"
      aria-label="Zugänge"
      sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderBottom: 1, borderColor: 'divider' }}
    >
      {entries.map((entry) => (
        <Box component="li" key={entry.profile.id} sx={listCardSx}>
          <Box component="article" aria-label={entry.profile.name}>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <ProfileCell entry={entry} />
              </Box>
              <OpenButton entry={entry} onOpen={onOpen} />
            </Box>
            {entry.attention && (
              <Box sx={{ mt: 1.25 }}>
                <AttentionCell attention={entry.attention} />
              </Box>
            )}
            <Box component="dl" sx={definitionListSx}>
              <dt>Server</dt>
              <dd>
                <ServerCell profile={entry.profile} />
              </dd>
              <dt>Nutzbar für</dt>
              <dd>
                <AccessCell access={entry.access} />
              </dd>
              <dt>Verbindungen</dt>
              <dd>{entry.profile.connectionCount}</dd>
            </Box>
          </Box>
        </Box>
      ))}
    </Box>
  )
}

/**
 * The profiles of the installation on one screen: source type and state, host, who may use it,
 * its connections and what needs doing - one row each. Details and every change open in the side
 * panel. A table from the `md` breakpoint, a list below it, the same words in both.
 */
export default function ConnectionProfileOverview({ entries, onOpen }: OverviewProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  if (!isDesktop) return <OverviewList entries={entries} onOpen={onOpen} />

  return (
    <TableContainer>
      <Table size="small" aria-label="Zugänge" sx={{ ...adminTableSx, minWidth: 920 }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: '23%' }}>Zugang</TableCell>
            <TableCell sx={{ width: '16%' }}>Server</TableCell>
            <TableCell sx={{ width: 200 }}>Nutzbar für</TableCell>
            <TableCell align="right" sx={{ width: 112 }}>
              Verbindungen
            </TableCell>
            <TableCell>Handlungsbedarf</TableCell>
            <TableCell align="right" sx={{ width: 112 }}>
              <span style={visuallyHidden}>Öffnen</span>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow
              key={entry.profile.id}
              hover
              onClick={() => onOpen(entry.profile.id)}
              sx={{
                cursor: 'pointer',
                ...(entry.attention && {
                  boxShadow: (t) => `inset 3px 0 0 ${t.palette[entry.attention!.tone].main}`,
                }),
              }}
            >
              <TableCell>
                <ProfileCell entry={entry} />
              </TableCell>
              <TableCell>
                <ServerCell profile={entry.profile} />
              </TableCell>
              <TableCell>
                <AccessCell access={entry.access} />
              </TableCell>
              <TableCell align="right">{entry.profile.connectionCount}</TableCell>
              <TableCell>
                <AttentionCell attention={entry.attention} />
              </TableCell>
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
