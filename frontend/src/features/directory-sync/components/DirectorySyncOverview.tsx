import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import MetaBadge from '@/shared/ui/MetaBadge'
import ProviderMonogram from '@/shared/ui/ProviderMonogram'
import type { StatusTone } from '@/shared/ui/StatusLine'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import {
  agoLabel,
  clockLabel,
  intervalLabel,
  nextRunOf,
  syncStateOf,
  type OverviewEntry,
} from './directorySyncState'
import { formatDateTime, outcomeLabel, outcomeTone } from './directorySyncLabels'
import { fontSize } from '@/shared/theme/tokens'

const TONE_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

const secondarySx = {
  fontSize: fontSize.meta,
  color: 'text.secondary',
  mt: 0.25,
  pl: 1.75,
} as const

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
      <Typography component="span" sx={{ fontSize: fontSize.text, fontWeight: strong ? 600 : 500 }}>
        {label}
      </Typography>
    </Box>
  )
}

/** An empty cell: a dash for the eye, the statement for the screen reader. */
function Nothing({ label }: { label: string }) {
  return (
    <Typography component="span" sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
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
        <ListText strong>{provider.displayName}</ListText>
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
  if (next.kind === 'DUE')
    return <Typography sx={{ fontSize: fontSize.text }}>jetzt fällig</Typography>
  return (
    <Typography sx={{ fontSize: fontSize.text }}>
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
      <ListText secondary>{attention.detail}</ListText>
    </>
  )
}

/**
 * The directory sync of every provider on one screen: mechanism, last and next run and
 * what needs doing, one row each. The row itself leads into the side panel with details and
 * changes; a waiting plan shows as „Handlungsbedarf" and is decided there.
 */
export default function DirectorySyncOverview({ entries, onOpen, now }: OverviewProps) {
  return (
    <AdminList
      label="Verzeichnisabgleich je Anbieter"
      rows={entries}
      rowKey={(entry) => entry.provider.id}
      rowLabel={(entry) => entry.provider.displayName}
      minWidth={880}
      rowTarget={(entry) => ({ onOpen: () => onOpen(entry.provider.id) })}
      rowTone={(entry) => entry.attention?.tone ?? null}
      columns={[
        {
          id: 'provider',
          head: 'Anbieter',
          width: '24%',
          cell: (entry) => <ProviderCell entry={entry} />,
        },
        {
          id: 'mechanism',
          head: 'Abgleich',
          width: '15%',
          cell: (entry) => <MechanismCell entry={entry} />,
        },
        {
          id: 'lastRun',
          head: 'Letzter Lauf',
          width: '18%',
          cell: (entry) => <LastRunCell entry={entry} now={now} />,
        },
        {
          id: 'nextRun',
          head: 'Nächster Lauf',
          width: '13%',
          cell: (entry) => <NextRunCell entry={entry} now={now} />,
        },
        {
          id: 'attention',
          head: 'Handlungsbedarf',
          cell: (entry) => <AttentionCell entry={entry} />,
        },
      ]}
    />
  )
}
