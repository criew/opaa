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
import MetaBadge from '@/shared/ui/MetaBadge'
import ProviderMonogram from '@/shared/ui/ProviderMonogram'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { adminTableSx } from '@/shared/ui/list/adminListStyles'
import {
  agoLabel,
  clockLabel,
  intervalLabel,
  nextRunOf,
  syncStateOf,
  type OverviewEntry,
} from './directorySyncState'
import { formatDateTime, outcomeLabel, outcomeTone } from './directorySyncLabels'

const TONE_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

const secondarySx = { fontSize: 12.5, color: 'text.secondary', mt: 0.25, pl: 1.75 } as const

/**
 * A state as a dot before its word, the dot on the first line when the word wraps - the column
 * widths are fixed, the outcome words are not. Colour carries no meaning on its own.
 */
function StateText({
  tone,
  label,
  strong = false,
}: {
  tone: StatusTone
  label: string
  strong?: boolean
}) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.75 }}>
      <Box
        component="span"
        aria-hidden="true"
        sx={{
          width: 8,
          height: 8,
          mt: '6px',
          borderRadius: '50%',
          flex: 'none',
          bgcolor: TONE_COLOR[tone],
        }}
      />
      <Typography component="span" sx={{ fontSize: 13, fontWeight: strong ? 600 : 500 }}>
        {label}
      </Typography>
    </Box>
  )
}

/** An empty cell: a dash for the eye, the statement for the screen reader. */
function Nothing({ label }: { label: string }) {
  return (
    <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
      <span aria-hidden="true">—</span>
      <span style={visuallyHidden}>{label}</span>
    </Typography>
  )
}

interface OverviewProps {
  entries: OverviewEntry[]
  onOpen: (providerId: string) => void
  now: Date
}

function ProviderCell({ entry }: { entry: OverviewEntry }) {
  const { provider } = entry
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25, minWidth: 0 }}>
      <ProviderMonogram
        name={provider.displayName}
        size={28}
        tone={provider.enabled ? 'accent' : 'muted'}
      />
      <Box sx={{ minWidth: 0 }}>
        <Typography
          sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'break-word', hyphens: 'auto' }}
        >
          {provider.displayName}
        </Typography>
        {!provider.enabled && (
          <Box sx={{ mt: 0.5 }}>
            <MetaBadge>Anbieter deaktiviert</MetaBadge>
          </Box>
        )}
      </Box>
    </Box>
  )
}

function MechanismCell({ entry }: { entry: OverviewEntry }) {
  const state = syncStateOf(entry.provider)
  const interval = entry.provider.directorySyncIntervalMinutes
  return (
    <>
      <StateText tone={state.tone} label={state.label} />
      {(state.kind === 'ON' || state.kind === 'PAUSED') && interval && (
        <Typography sx={secondarySx}>{intervalLabel(interval)}</Typography>
      )}
    </>
  )
}

function LastRunCell({ entry, now }: { entry: OverviewEntry; now: Date }) {
  const outcome = entry.status?.lastOutcome
  const lastRunAt = entry.status?.lastRunAt
  // A sync that is off and never ran has no last run worth a word.
  if (!outcome && !entry.provider.directorySyncEnabled) return <Nothing label="kein Lauf" />
  return (
    <>
      <StateText tone={outcomeTone(outcome)} label={outcomeLabel(outcome)} />
      {lastRunAt && (
        <Typography sx={secondarySx}>
          <time dateTime={lastRunAt} title={formatDateTime(lastRunAt)}>
            {agoLabel(lastRunAt, now)}
          </time>
        </Typography>
      )}
    </>
  )
}

function NextRunCell({ entry, now }: { entry: OverviewEntry; now: Date }) {
  const next = nextRunOf(entry.provider, entry.status, now)
  if (next.kind === 'NONE') return <Nothing label="kein Lauf geplant" />
  if (next.kind === 'DUE') return <Typography sx={{ fontSize: 13 }}>jetzt fällig</Typography>
  return (
    <Typography sx={{ fontSize: 13 }}>
      <time dateTime={next.at.toISOString()}>{clockLabel(next.at, now)}</time>
    </Typography>
  )
}

function AttentionCell({ entry }: { entry: OverviewEntry }) {
  const { attention } = entry
  if (!attention) return <Nothing label="nichts zu tun" />
  return (
    <>
      <StateText tone={attention.tone} label={attention.label} strong />
      <Typography
        title={attention.detail}
        sx={{
          ...secondarySx,
          display: '-webkit-box',
          WebkitLineClamp: 3,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {attention.detail}
      </Typography>
    </>
  )
}

/** The one way into a provider; a pending plan names what is waiting there. */
function OpenButton({ entry, onOpen }: { entry: OverviewEntry; onOpen: OverviewProps['onOpen'] }) {
  const decide = Boolean(entry.status?.pendingPlan)
  const verb = decide ? 'Entscheiden' : 'Details'
  return (
    <Button
      size="small"
      variant={decide ? 'contained' : 'outlined'}
      aria-label={`${verb}: ${entry.provider.displayName}`}
      onClick={(event) => {
        event.stopPropagation()
        onOpen(entry.provider.id)
      }}
    >
      {verb}
    </Button>
  )
}

/** Narrow windows: the same statements one under the other, as a list entry - not a card. */
function OverviewList({ entries, onOpen, now }: OverviewProps) {
  return (
    <Box
      component="ul"
      sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderBottom: 1, borderColor: 'divider' }}
    >
      {entries.map((entry) => (
        <Box
          component="li"
          key={entry.provider.id}
          sx={{ py: 1.75, '& + &': { borderTop: 1, borderColor: 'divider' } }}
        >
          <Box component="article" aria-label={entry.provider.displayName}>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <ProviderCell entry={entry} />
              </Box>
              <OpenButton entry={entry} onOpen={onOpen} />
            </Box>
            {entry.attention && (
              <Box sx={{ mt: 1.25 }}>
                <AttentionCell entry={entry} />
              </Box>
            )}
            <Box
              component="dl"
              sx={{
                display: 'grid',
                gridTemplateColumns: 'max-content 1fr',
                columnGap: 1.5,
                rowGap: 0.75,
                m: 0,
                mt: 1.25,
                '& dt': { fontSize: 12, color: 'text.secondary', pt: '1px' },
                '& dd': { m: 0 },
              }}
            >
              <dt>Abgleich</dt>
              <dd>
                <MechanismCell entry={entry} />
              </dd>
              <dt>Letzter Lauf</dt>
              <dd>
                <LastRunCell entry={entry} now={now} />
              </dd>
              <dt>Nächster Lauf</dt>
              <dd>
                <NextRunCell entry={entry} now={now} />
              </dd>
            </Box>
          </Box>
        </Box>
      ))}
    </Box>
  )
}

/**
 * The directory sync of every provider on one screen: mechanism, last and next run and
 * what needs doing, one row each - details and changes open in the side panel. A table from the
 * `md` breakpoint, a list below it, the same words in both.
 */
export default function DirectorySyncOverview({ entries, onOpen, now }: OverviewProps) {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))

  if (!isDesktop) return <OverviewList entries={entries} onOpen={onOpen} now={now} />

  return (
    <TableContainer
      tabIndex={0}
      role="region"
      aria-label="Tabelle Verzeichnisabgleich, horizontal scrollbar"
    >
      <Table
        size="small"
        aria-label="Verzeichnisabgleich je Anbieter"
        sx={{ ...adminTableSx, minWidth: 880 }}
      >
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: '20%' }}>Anbieter</TableCell>
            <TableCell sx={{ width: '14%' }}>Abgleich</TableCell>
            <TableCell sx={{ width: '17%' }}>Letzter Lauf</TableCell>
            <TableCell sx={{ width: '11%' }}>Nächster Lauf</TableCell>
            <TableCell>Handlungsbedarf</TableCell>
            <TableCell align="right" sx={{ width: 136 }}>
              <span style={visuallyHidden}>Öffnen</span>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow
              key={entry.provider.id}
              hover
              onClick={() => onOpen(entry.provider.id)}
              sx={{
                cursor: 'pointer',
                ...(entry.attention && {
                  boxShadow: (t) => `inset 3px 0 0 ${t.palette[entry.attention!.tone].main}`,
                }),
              }}
            >
              <TableCell>
                <ProviderCell entry={entry} />
              </TableCell>
              <TableCell>
                <MechanismCell entry={entry} />
              </TableCell>
              <TableCell>
                <LastRunCell entry={entry} now={now} />
              </TableCell>
              <TableCell>
                <NextRunCell entry={entry} now={now} />
              </TableCell>
              <TableCell>
                <AttentionCell entry={entry} />
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
