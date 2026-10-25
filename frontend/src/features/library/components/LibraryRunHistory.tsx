import { useId, useMemo, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Link from '@mui/material/Link'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { IndexingRunEvent, IndexingRunResponse } from '@/shared/api/types'
import StateDot from '@/shared/ui/StateDot'
import TabSection from '@/shared/ui/TabSection'
import { formatFileSize } from '@/shared/utils/labels'
import { indexingRunEventCategoryLabel } from './indexingLabels'
import { formatDateTime, runRowState, runState, unreadableCount } from './libraryState'
import { plainMessage } from './sourceKit/plainMessage'

/** Categories that are notes about a run, not failures: they never count as „fehlgeschlagen“. */
const NOTE_CATEGORIES = new Set([
  'FORMAT_MISMATCH',
  'SCHEDULE_SKIPPED',
  'REMOVED',
  'RATE_LIMITED',
  'SUMMARY',
])

/** Examples a group shows without a click. */
const EXAMPLES = 5

/** The fixed columns of a run row: Zustand · Beginn · Dauer · Auslöser · Ergebnis. */
const RUN_GRID = {
  display: 'grid',
  gridTemplateColumns: {
    xs: 'minmax(0, 1fr) auto',
    md: '190px 124px 64px 136px minmax(0, 1fr) 24px',
  },
  columnGap: 1.5,
  alignItems: 'center',
} as const

const TABLE_WIDTH = 880

export interface LiveProgress {
  percent: number
  processed: number
  total: number
}

function formatCount(n: number): string {
  return n.toLocaleString('de-DE')
}

function durationLabel(run: IndexingRunResponse): string {
  if (!run.completedAt) return 'läuft'
  const seconds = Math.max(
    0,
    Math.round((new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) / 1000),
  )
  if (seconds < 60) return '< 1 min'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`
}

function triggerLabel(run: IndexingRunResponse, showMode: boolean): string {
  const trigger =
    run.runMode === 'EVENT'
      ? 'Ereignis'
      : run.triggeredBy === 'SCHEDULED'
        ? 'Zeitplan'
        : run.triggeredBy === 'WEBHOOK'
          ? 'Webhook'
          : 'Hand'
  return showMode && run.runMode === 'FULL' ? `${trigger} · Vollabgleich` : trigger
}

/** Processed count plus the one problem that matters most; the detail names the rest. */
/** The processed count, then every problem the run had, in the words of band and Umfang. */
function resultLabel(run: IndexingRunResponse, live: LiveProgress | null): string {
  if (run.status === 'RUNNING') {
    return live
      ? `${live.percent} % · ${formatCount(live.processed)} von ${formatCount(live.total)}`
      : 'wird ermittelt'
  }
  // the column is headed „Dokumente“: the count stands without its unit
  const documents = formatCount(run.documentCount)
  const unreadable = unreadableCount(run)
  return [
    documents,
    run.documentsFailed > 0 && `${formatCount(run.documentsFailed)} fehlgeschlagen`,
    unreadable > 0 && `${unreadable} nicht lesbar`,
    run.incomplete && 'unvollständig',
  ]
    .filter(Boolean)
    .join(' · ')
}

/** A place with one separator between its levels, „ / “, whatever the source wrote. */
function normalizedPlace(place: string): string {
  const leading = place.trim().startsWith('/') ? '/' : ''
  const parts = place
    .split(/\s*›\s*|\s*\/\s*/)
    .map((part) => part.trim())
    .filter(Boolean)
  return parts.length > 1 ? leading + parts.join(' / ') : place.trim()
}

/**
 * A place on one line, cut in the middle: the leading levels give way, the file name at its end
 * stays readable at every width. The full place is in the tooltip.
 */
function PlaceLabel({ place, mark }: { place: string; mark?: ReactNode }) {
  const text = normalizedPlace(place)
  const at = text.lastIndexOf(' / ')
  const head = at > 0 ? text.slice(0, at + 3) : ''
  const tail = at > 0 ? text.slice(at + 3) : text
  const cut = { whiteSpace: 'pre', overflow: 'hidden', textOverflow: 'ellipsis' } as const
  return (
    <Box component="span" sx={{ display: 'flex', minWidth: 0, maxWidth: '100%' }}>
      {head && (
        <Box component="span" sx={{ ...cut, flex: '0 1 auto', minWidth: '1.5em' }}>
          {head}
        </Box>
      )}
      {/* the file name never gives way while a leading level can */}
      <Box
        component="span"
        sx={{ ...cut, flex: head ? '0 0 auto' : '0 1 auto', minWidth: 0, maxWidth: '100%' }}
      >
        {tail}
      </Box>
      {mark}
    </Box>
  )
}

function hasProblem(run: IndexingRunResponse): boolean {
  const word = runState(run).word
  return word !== 'Aktuell' && word !== 'Läuft'
}

/** Unreachable places are what band and Umfang call „nicht lesbar“: one word for one thing. */
function groupLabel(category: string): string {
  return category === 'UNREACHABLE' ? 'Nicht lesbar' : indexingRunEventCategoryLabel(category)
}

interface EventGroup {
  category: string
  label: string
  events: IndexingRunEvent[]
}

function groupEvents(events: IndexingRunEvent[]): { errors: EventGroup[]; notes: EventGroup[] } {
  const groups = new Map<string, EventGroup>()
  for (const event of events) {
    const group = groups.get(event.category) ?? {
      category: event.category,
      label: groupLabel(event.category),
      events: [],
    }
    group.events.push(event)
    groups.set(event.category, group)
  }
  const sorted = [...groups.values()].sort((a, b) => b.events.length - a.events.length)
  return {
    errors: sorted.filter((group) => !NOTE_CATEGORIES.has(group.category)),
    notes: sorted.filter((group) => NOTE_CATEGORIES.has(group.category)),
  }
}

/** All places of one group, searchable from eight on and shown fifty at a time. */
function EventGroupDialog({ group, onClose }: { group: EventGroup; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const [shown, setShown] = useState(50)
  const titleId = useId()
  const matching = group.events.filter((event) =>
    `${event.reference ?? ''} ${event.message}`.toLowerCase().includes(query.trim().toLowerCase()),
  )
  return (
    <Dialog open onClose={onClose} aria-labelledby={titleId} fullWidth maxWidth="sm">
      <DialogTitle id={titleId}>
        {group.label} · {formatCount(group.events.length)}
      </DialogTitle>
      <DialogContent>
        {group.events.length >= 8 && (
          <TextField
            size="small"
            fullWidth
            placeholder="Fundort suchen"
            slotProps={{ htmlInput: { 'aria-label': 'Fundort suchen' } }}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setShown(50)
            }}
            sx={{ mb: 1.5 }}
          />
        )}
        <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {matching.slice(0, shown).map((event, index) => (
            <EventLine key={`${event.reference}-${index}`} event={event} />
          ))}
        </Box>
        {matching.length === 0 && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>Keine Treffer.</Typography>
        )}
        {matching.length > shown && (
          <Link
            component="button"
            type="button"
            underline="hover"
            onClick={() => setShown((n) => n + 50)}
            sx={{ fontSize: 13.5, mt: 1 }}
          >
            Weitere {Math.min(50, matching.length - shown)} anzeigen
          </Link>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}

/** One place, the reason's raw detail in the tooltip only. */
function EventLine({ event }: { event: IndexingRunEvent }) {
  return (
    <Box component="li" sx={{ fontSize: 13, lineHeight: '20px', minWidth: 0 }}>
      <Tooltip title={plainMessage(event.message)} placement="bottom-start">
        <Box
          component="span"
          tabIndex={0}
          sx={{ display: 'block', minWidth: 0, color: 'text.primary' }}
        >
          {event.reference ? <PlaceLabel place={event.reference} /> : plainMessage(event.message)}
        </Box>
      </Tooltip>
    </Box>
  )
}

function GroupBlock({ group, onShowAll }: { group: EventGroup; onShowAll: () => void }) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Typography component="h2" sx={{ fontSize: 13.5, fontWeight: 600, m: 0, mb: 0.25 }}>
        {group.label} · {formatCount(group.events.length)}
      </Typography>
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {group.events.slice(0, EXAMPLES).map((event, index) => (
          <EventLine key={`${event.reference}-${index}`} event={event} />
        ))}
      </Box>
      {group.events.length > EXAMPLES && (
        <Link
          component="button"
          type="button"
          underline="hover"
          onClick={onShowAll}
          sx={{ fontSize: 13, mt: 0.25 }}
        >
          Alle {formatCount(group.events.length)} anzeigen
        </Link>
      )}
    </Box>
  )
}

function technicalLabel(run: IndexingRunResponse): string[] {
  const lines: string[] = []
  if (run.eventsTruncatedCount > 0) {
    lines.push(`${formatCount(run.eventsTruncatedCount)} weitere Ereignisse nicht einzeln erfasst`)
  }
  if (run.documentsSkipped > 0)
    lines.push(`${formatCount(run.documentsSkipped)} unverändert übersprungen`)
  const metrics = run.metrics
  if (metrics) {
    if (metrics.requestsSent > 0)
      lines.push(`${formatCount(metrics.requestsSent)} Anfragen an die Quelle`)
    if (metrics.throttleCount > 0) {
      lines.push(
        `${metrics.throttleCount}-mal gedrosselt, ${metrics.throttleWaitSeconds} s gewartet`,
      )
    }
    if (metrics.bytesDownloaded) lines.push(`${formatFileSize(metrics.bytesDownloaded)} geladen`)
    lines.push(
      `Anhänge: ${metrics.attachmentsProcessed} indiziert, ${metrics.attachmentsSkipped} übersprungen, ${metrics.attachmentsFailed} fehlgeschlagen`,
    )
  }
  return lines
}

/** What the row does not say: the groups by reason, notes apart, technical figures folded. */
function RunDetail({
  run,
  connectionTo,
  quotaRemedy,
  scopeName,
}: {
  run: IndexingRunResponse
  connectionTo: string
  quotaRemedy: string
  scopeName: (key: string) => string
}) {
  const grouped = useMemo(() => groupEvents(run.events), [run.events])
  // a source that reports unreadable areas as keys gets the same group as one that reports events
  const unlisted = run.unlistedScopeKeys ?? []
  const errors =
    unlisted.length > 0 && !grouped.errors.some((group) => group.category === 'UNREACHABLE')
      ? [
          {
            category: 'UNREACHABLE',
            label: groupLabel('UNREACHABLE'),
            events: unlisted.map((key) => ({
              category: 'UNREACHABLE' as const,
              message: 'Bereich nicht lesbar',
              reference: scopeName(key),
            })),
          },
          ...grouped.errors,
        ]
      : grouped.errors
  const notes = grouped.notes
  const [dialogGroup, setDialogGroup] = useState<EventGroup | null>(null)
  const [notesOpen, setNotesOpen] = useState(false)
  const [technicalOpen, setTechnicalOpen] = useState(false)
  const state = runState(run)
  const technical = technicalLabel(run)
  return (
    <Box sx={{ pl: { xs: 0, md: 2.5 }, pr: 1, pt: 0.5, pb: 2 }}>
      {/* the row names a refused sign-in already: the detail only leads to the remedy; any
          other failure adds the source's own reason */}
      {state.word === 'Anmeldung abgelehnt' || state.word === 'Zustimmung abgelaufen' ? (
        <Box sx={{ mb: 1.5 }}>
          <Link component={RouterLink} to={connectionTo} underline="hover" sx={{ fontSize: 13.5 }}>
            Verbindung ändern ›
          </Link>
        </Box>
      ) : (
        run.status === 'FAILED' &&
        run.message && (
          <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>{plainMessage(run.message)}</Typography>
        )
      )}
      {state.word === 'Kontingent voll' && (
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>{quotaRemedy}</Typography>
      )}
      {errors.map((group) => (
        <GroupBlock key={group.category} group={group} onShowAll={() => setDialogGroup(group)} />
      ))}
      <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
        {notes.length > 0 && (
          <Link
            component="button"
            type="button"
            underline="hover"
            aria-expanded={notesOpen}
            onClick={() => setNotesOpen((open) => !open)}
            sx={{ fontSize: 13 }}
          >
            Hinweise ({formatCount(notes.reduce((sum, group) => sum + group.events.length, 0))})
          </Link>
        )}
        {technical.length > 0 && (
          <Link
            component="button"
            type="button"
            underline="hover"
            aria-expanded={technicalOpen}
            onClick={() => setTechnicalOpen((open) => !open)}
            sx={{ fontSize: 13 }}
          >
            Technische Angaben
          </Link>
        )}
      </Box>
      {notesOpen && (
        <Box sx={{ mt: 1.5 }}>
          {notes.map((group) => (
            <GroupBlock
              key={group.category}
              group={group}
              onShowAll={() => setDialogGroup(group)}
            />
          ))}
        </Box>
      )}
      {technicalOpen && (
        <Box component="ul" sx={{ m: 0, mt: 1.5, p: 0, listStyle: 'none' }}>
          {technical.map((line) => (
            <Box component="li" key={line} sx={{ fontSize: 13, color: 'text.secondary' }}>
              {line}
            </Box>
          ))}
        </Box>
      )}
      {dialogGroup && <EventGroupDialog group={dialogGroup} onClose={() => setDialogGroup(null)} />}
    </Box>
  )
}

function RunRow({
  run,
  showMode,
  live,
  connectionTo,
  quotaRemedy,
  scopeName,
}: {
  run: IndexingRunResponse
  showMode: boolean
  live: LiveProgress | null
  connectionTo: string
  quotaRemedy: string
  scopeName: (key: string) => string
}) {
  const [open, setOpen] = useState(false)
  const detailId = useId()
  const state = runRowState(run)
  const cell = {
    fontSize: 13.5,
    color: 'text.secondary',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  } as const
  return (
    <Box component="li" sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Box
        component="button"
        type="button"
        aria-expanded={open}
        aria-controls={detailId}
        onClick={() => setOpen((value) => !value)}
        sx={{
          ...RUN_GRID,
          width: '100%',
          border: 0,
          bgcolor: 'transparent',
          font: 'inherit',
          textAlign: 'left',
          cursor: 'pointer',
          py: 1.25,
          px: 0,
          color: 'inherit',
          '&:hover': { bgcolor: 'action.hover' },
          '&:focus-visible': { outline: 'none', boxShadow: '0 0 0 3px rgba(18,144,239,0.32)' },
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          {/* below md: state and start on the first line, result and trigger below - wrapped,
              never cut */}
          <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
            <StateDot tone={state.tone} label={state.word} />
            <Typography
              component="span"
              sx={{
                display: { xs: 'inline', md: 'none' },
                fontSize: 13,
                color: 'text.secondary',
                ml: 'auto',
                whiteSpace: 'nowrap',
              }}
            >
              {formatDateTime(run.startedAt).slice(0, 6)} · {triggerLabel(run, showMode)}
            </Typography>
          </Box>
          <Typography
            sx={{ display: { xs: 'block', md: 'none' }, fontSize: 13, color: 'text.secondary' }}
          >
            {resultLabel(run, live)}
          </Typography>
        </Box>
        <Typography sx={{ ...cell, display: { xs: 'none', md: 'block' } }}>
          {formatDateTime(run.startedAt)}
        </Typography>
        <Typography sx={{ ...cell, display: { xs: 'none', md: 'block' } }}>
          {run.completedAt ? durationLabel(run) : '–'}
        </Typography>
        <Typography sx={{ ...cell, display: { xs: 'none', md: 'block' } }}>
          {triggerLabel(run, showMode)}
        </Typography>
        <Typography sx={{ ...cell, color: 'text.primary', display: { xs: 'none', md: 'block' } }}>
          {resultLabel(run, live)}
        </Typography>
        <ExpandMoreIcon
          aria-hidden
          sx={{
            fontSize: 20,
            color: 'text.secondary',
            justifySelf: 'end',
            transform: open ? 'rotate(180deg)' : 'none',
            transition: 'transform 120ms',
          }}
        />
      </Box>
      <Box id={detailId} hidden={!open}>
        {open && (
          <RunDetail
            run={run}
            connectionTo={connectionTo}
            quotaRemedy={quotaRemedy}
            scopeName={scopeName}
          />
        )}
      </Box>
    </Box>
  )
}

/**
 * The Reiter „Verlauf“: the last ten runs as one table with fixed columns, each row a button that
 * unfolds what the row does not say. The start buttons sit above the table; the head keeps
 * „Jetzt indizieren“ as its main action.
 */
export default function LibraryRunHistory({
  runs,
  showMode,
  live,
  actions,
  connectionTo,
  quotaRemedy,
  scopeName = (key) => key,
}: {
  runs: IndexingRunResponse[]
  /** Whether the source has two run modes; only then the mode is named. */
  showMode: boolean
  /** The running run's progress from the status poll - the same count as the head. */
  live: LiveProgress | null
  actions?: React.ReactNode
  connectionTo: string
  quotaRemedy: string
  /** The stored name of a scope key the run could not read. */
  scopeName?: (key: string) => string
}) {
  const [onlyProblems, setOnlyProblems] = useState(false)
  const shown = onlyProblems ? runs.filter(hasProblem) : runs
  const problemCount = runs.filter(hasProblem).length
  // the table's own tool row at the table's width: filter left, start buttons right
  return (
    <TabSection wide>
      <Box
        sx={{
          maxWidth: TABLE_WIDTH,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 1.5,
          mb: 1.5,
          minHeight: 32,
          '& .MuiButton-text': { px: 0, minWidth: 0 },
        }}
      >
        {/* the filter only where it shortens the list */}
        {problemCount > 0 && problemCount < runs.length ? (
          <Button
            size="small"
            variant={onlyProblems ? 'contained' : 'outlined'}
            aria-pressed={onlyProblems}
            onClick={() => setOnlyProblems((value) => !value)}
            sx={{ borderRadius: 999, textTransform: 'none' }}
          >
            Nur mit Fehlern ({problemCount})
          </Button>
        ) : (
          <span />
        )}
        <Box sx={{ ml: 'auto' }}>{actions}</Box>
      </Box>
      {runs.length === 0 ? (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>Noch keine Läufe.</Typography>
      ) : (
        <Box sx={{ maxWidth: TABLE_WIDTH }}>
          <Box
            aria-hidden
            sx={{
              ...RUN_GRID,
              display: { xs: 'none', md: 'grid' },
              borderBottom: 1,
              borderColor: 'divider',
              pb: 0.75,
              '& > span': { fontSize: 12, fontWeight: 500, color: 'text.secondary' },
            }}
          >
            <span>Zustand</span>
            <span>Beginn</span>
            <span>Dauer</span>
            <span>Auslöser</span>
            <span>Dokumente</span>
            <span />
          </Box>
          <Box component="ul" aria-label="Läufe" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {shown.map((run) => (
              <RunRow
                key={run.id}
                run={run}
                showMode={showMode}
                live={run.status === 'RUNNING' ? live : null}
                connectionTo={connectionTo}
                quotaRemedy={quotaRemedy}
                scopeName={scopeName}
              />
            ))}
          </Box>
        </Box>
      )}
    </TabSection>
  )
}
