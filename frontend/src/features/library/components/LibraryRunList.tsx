import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import Link from '@mui/material/Link'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { IndexingRunResponse } from '@/shared/api/types'
import StateDot from '@/shared/ui/StateDot'
import { TextAction } from '@/shared/ui/DetailSection'
import { formatFileSize } from '@/shared/utils/labels'
import {
  indexingRunEventCategoryLabel,
  indexingRunModeLabel,
  indexingTriggerSourceLabel,
} from './indexingLabels'
import { formatDay, isErrorEvent, runTone, runWord, stateTargetHref } from './libraryState'
import { plainMessage } from './sourceKit/plainMessage'
import { PATH_SEPARATOR } from './setup/paths'

/** The run list offers „Nur mit Fehlern“ from this many runs on. */
const FILTER_FROM = 5

/** Fundorte a group names before „alle n anzeigen“. */
const SHOWN = 5

/** The columns of a run row: Zustand · Beginn · Dauer · Auslöser · Ergebnis · chevron. */
const COLUMNS = {
  display: 'grid',
  gridTemplateColumns: {
    xs: 'minmax(0, 1fr) 20px',
    sm: '164px 104px 60px 78px minmax(0, 1fr) 20px',
  },
  columnGap: 1,
  alignItems: 'baseline',
} as const

type RunEvent = IndexingRunResponse['events'][number]

export interface RunningProgress {
  percent: number
  done: number
  total: number
}

function count(value: number): string {
  return value.toLocaleString('de-DE')
}

/** „23.10.2026, 06:00“ - the one date-and-time form of the page. */
function formatStart(value: string): string {
  return new Date(value).toLocaleString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatDuration(run: IndexingRunResponse): string {
  if (!run.completedAt) return '–'
  const seconds = Math.max(
    0,
    Math.round((new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) / 1000),
  )
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 0) return `${hours} h ${minutes} min`
  return minutes > 0 ? `${minutes} min` : `${seconds} s`
}

/** Who started the run; a full reconciliation names its mode instead (D11). */
function trigger(run: IndexingRunResponse, showMode: boolean): string {
  if (showMode && run.runMode === 'FULL') return 'Vollabgleich'
  if (run.triggeredBy === 'SCHEDULED') return 'Zeitplan'
  if (run.triggeredBy === 'WEBHOOK') return 'Webhook'
  return 'Manuell'
}

/** What the run yielded, in one form for every source: documents and errors, never both words. */
function result(run: IndexingRunResponse, running: RunningProgress | null): string {
  if (running) return `${count(running.done)} von ${count(running.total)}`
  if (run.status === 'FAILED') return '–'
  const parts = [`${count(run.documentsIndexedTotal)} Dokumente`]
  if (run.documentsFailed > 0) parts.push(`${count(run.documentsFailed)} fehlgeschlagen`)
  else if ((run.unreadableScopeCount ?? 0) > 0)
    parts.push(`${run.unreadableScopeCount} nicht lesbar`)
  else if (run.incomplete) parts.push('unvollständig')
  return parts.join(' · ')
}

/** A reason's name in the run detail; the catch-all category is named as such and goes last. */
function reasonLabel(category: RunEvent['category']): string {
  return category === 'ERROR' ? 'Sonstige Fehler' : indexingRunEventCategoryLabel(category)
}

/** Events of one reason, the largest group first, the catch-all at the end. */
function groupByReason(events: RunEvent[]): Array<{ label: string; events: RunEvent[] }> {
  const groups = new Map<string, RunEvent[]>()
  for (const event of events) {
    const label = reasonLabel(event.category)
    groups.set(label, [...(groups.get(label) ?? []), event])
  }
  return [...groups]
    .map(([label, entries]) => ({ label, events: entries }))
    .sort(
      (a, b) =>
        Number(a.label === 'Sonstige Fehler') - Number(b.label === 'Sonstige Fehler') ||
        b.events.length - a.events.length,
    )
}

/**
 * A Fundort on one line in the one path form of the page („Ordner / Akte / Datei.pdf“), cut in
 * the head: the last segment always stays. A prefix every Fundort of the library shares (the
 * address of a web directory, the path of a server directory) is left out.
 */
function Fundort({ reference, prefix }: { reference: string; prefix: string | null }) {
  const shown = prefix && reference.startsWith(prefix) ? reference.slice(prefix.length) : reference
  const segments = shown
    .split(/[/›]/)
    .map((segment) => segment.trim())
    .filter(Boolean)
  const last = segments.pop() ?? shown
  // the separator stays with the last segment, so a cut head still reads „…ilfe / Anlage-9.pdf“
  const head = segments.join(PATH_SEPARATOR)
  const tail = head ? `${PATH_SEPARATOR}${last}` : last
  const ellipsis = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'pre' } as const
  return (
    <Box component="span" sx={{ display: 'flex', minWidth: 0 }}>
      <Box component="span" sx={{ ...ellipsis, minWidth: 0 }}>
        {head}
      </Box>
      <Box component="span" sx={{ ...ellipsis, flex: 'none', maxWidth: '100%' }}>
        {tail}
      </Box>
    </Box>
  )
}

function technicalLines(run: IndexingRunResponse, showMode: boolean): string[] {
  const lines = [
    `Auslöser: ${indexingTriggerSourceLabel(run.triggeredBy)}${showMode ? ` · ${indexingRunModeLabel(run.runMode)}` : ''}`,
    `Verarbeitet: ${count(run.documentCount)} · übersprungen: ${count(run.documentsSkipped)}`,
  ]
  const metrics = run.metrics
  if (metrics) {
    // requests are only counted by sources that report them (Confluence)
    const source =
      metrics.requestsSent > 0 || metrics.throttleCount > 0
        ? [`${count(metrics.requestsSent)} Anfragen an die Quelle`]
        : []
    if (metrics.throttleCount > 0)
      source.push(`${metrics.throttleCount}-mal gedrosselt (${metrics.throttleWaitSeconds} s)`)
    if (metrics.bytesDownloaded) source.push(`${formatFileSize(metrics.bytesDownloaded)} geladen`)
    if (source.length > 0) lines.push(source.join(' · '))
    lines.push(
      `Anhänge: ${metrics.attachmentsProcessed} indiziert · ${metrics.attachmentsSkipped} übersprungen · ${metrics.attachmentsFailed} fehlgeschlagen`,
    )
  }
  return lines
}

/** A disclosure inside the run detail: one line with its count, its content only on request. */
function Disclosure({
  id,
  label,
  children,
}: {
  id: string
  label: string
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(false)
  return (
    <Box sx={{ mt: 1 }}>
      <TextAction onClick={() => setOpen((v) => !v)} aria-expanded={open} aria-controls={id}>
        {label}
      </TextAction>
      {open && (
        <Box id={id} sx={{ mt: 0.5 }}>
          {children}
        </Box>
      )}
    </Box>
  )
}

const LINE = { fontSize: 13, lineHeight: '22px' } as const

function EventGroup({
  label,
  events,
  onShowAll,
  prefix,
}: {
  label: string
  events: RunEvent[]
  onShowAll: () => void
  prefix: string | null
}) {
  return (
    <Box component="li" sx={{ mb: 1.25 }}>
      <Typography sx={{ ...LINE, fontWeight: 600 }}>
        {label} · {count(events.length)}
      </Typography>
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {events.slice(0, SHOWN).map((event, index) => (
          <Typography
            component="li"
            key={`${event.reference ?? ''}-${index}`}
            // one line per Fundort; the whole path and the message stand in the tooltip
            title={`${event.reference ?? ''}\n${plainMessage(event.message)}`.trim()}
            sx={{ ...LINE, color: 'text.secondary' }}
          >
            <Fundort reference={event.reference ?? plainMessage(event.message)} prefix={prefix} />
          </Typography>
        ))}
      </Box>
      {events.length > SHOWN && (
        <TextAction onClick={onShowAll}>alle {count(events.length)} anzeigen</TextAction>
      )}
    </Box>
  )
}

function RunDetail({
  run,
  word,
  showMode,
  quotaRemedy,
  prefix,
  unreadableNames,
}: {
  run: IndexingRunResponse
  word: string
  showMode: boolean
  quotaRemedy: string
  prefix: string | null
  /** The names of the unreadable scope entries, where the run is the one that found them. */
  unreadableNames: string[]
}) {
  const [allOf, setAllOf] = useState<{ label: string; events: RunEvent[] } | null>(null)
  const errorEvents = run.events.filter(isErrorEvent)
  const errors = groupByReason(errorEvents)
  const notes = run.events.filter((event) => !isErrorEvent(event))
  // the row counts failed documents; what the run kept no Fundort for is named, not dropped
  const withoutFundort = Math.max(0, run.documentsFailed - errorEvents.length)
  const notesNotKept = Math.max(0, run.eventsTruncatedCount - withoutFundort)
  const unreadable = run.unreadableScopeCount ?? 0
  // only what the row cannot say: the remedy of a full quota, the reason of an unnamed failure
  const reason =
    run.failureCategory === 'QUOTA_EXHAUSTED'
      ? quotaRemedy
      : word === 'Fehlgeschlagen' && run.message
        ? plainMessage(run.message)
        : null
  // a run that failed at the connection leads straight to its row; the word says the cause
  const connectionFailed =
    word === 'Anmeldung abgelehnt' ||
    word === 'Nicht erreichbar' ||
    word === 'Zustimmung abgelaufen'
  return (
    <Box sx={{ pl: { xs: 0, sm: 2.25 }, pt: 1, pb: 2 }}>
      {connectionFailed && (
        <Box sx={{ mb: 1 }}>
          <Link
            component={RouterLink}
            to={stateTargetHref({ tab: 'quelle', anchor: 'verbindung' })}
            replace
            underline="hover"
            sx={{ fontSize: 13.5, fontWeight: 500 }}
          >
            Verbindung ändern ›
          </Link>
        </Box>
      )}
      {reason && <Typography sx={{ ...LINE, mb: 1 }}>{reason}</Typography>}
      {unreadable > 0 && unreadableNames.length === 0 && (
        <Typography sx={{ ...LINE, mb: 1.25 }}>
          {unreadable === 1 ? 'Ein Bereich' : `${unreadable} Bereiche`} der Quelle nicht lesbar
        </Typography>
      )}
      {unreadable > 0 && unreadableNames.length > 0 && (
        <Box sx={{ mb: 1.25 }}>
          <Typography sx={{ ...LINE, fontWeight: 600 }}>
            Nicht lesbare Bereiche · {unreadable}
          </Typography>
          {unreadableNames.slice(0, SHOWN).map((name) => (
            <Typography key={name} noWrap sx={{ ...LINE, color: 'text.secondary' }}>
              {name}
            </Typography>
          ))}
        </Box>
      )}
      {errors.length > 0 && (
        <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {errors.map((group) => (
            <EventGroup
              key={group.label}
              label={group.label}
              events={group.events}
              onShowAll={() => setAllOf(group)}
              prefix={prefix}
            />
          ))}
        </Box>
      )}
      {withoutFundort > 0 && (
        <Typography sx={{ ...LINE, color: 'text.secondary' }}>
          {count(withoutFundort)} weitere fehlgeschlagen, ohne gespeicherten Fundort
        </Typography>
      )}
      {notes.length + notesNotKept > 0 && (
        <Disclosure
          id={`run-notes-${run.id}`}
          label={`Hinweise · ${count(notes.length + notesNotKept)}`}
        >
          <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {groupByReason(notes).map((group) => (
              <EventGroup
                key={group.label}
                label={group.label}
                events={group.events}
                onShowAll={() => setAllOf(group)}
                prefix={prefix}
              />
            ))}
          </Box>
          {notesNotKept > 0 && (
            <Typography sx={{ ...LINE, color: 'text.secondary' }}>
              {count(notesNotKept)} weitere ohne gespeicherten Fundort
            </Typography>
          )}
        </Disclosure>
      )}
      <Disclosure id={`run-technical-${run.id}`} label="Technische Angaben">
        {technicalLines(run, showMode).map((line) => (
          <Typography key={line} sx={{ ...LINE, color: 'text.secondary' }}>
            {line}
          </Typography>
        ))}
      </Disclosure>
      <Dialog
        open={allOf !== null}
        onClose={() => setAllOf(null)}
        fullWidth
        maxWidth="sm"
        aria-labelledby={`run-events-title-${run.id}`}
      >
        <DialogTitleWithClose id={`run-events-title-${run.id}`} onClose={() => setAllOf(null)}>
          {allOf?.label} · {count(allOf?.events.length ?? 0)}
        </DialogTitleWithClose>
        <DialogContent>
          <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {allOf?.events.map((event, index) => (
              <Box
                component="li"
                key={`${event.reference ?? ''}-${index}`}
                sx={{ py: 0.75, borderBottom: 1, borderColor: 'divider' }}
              >
                <Typography sx={{ ...LINE, overflowWrap: 'anywhere' }}>
                  {event.reference ?? '–'}
                </Typography>
                <Typography sx={{ ...LINE, color: 'text.secondary' }}>
                  {plainMessage(event.message)}
                </Typography>
              </Box>
            ))}
          </Box>
        </DialogContent>
      </Dialog>
    </Box>
  )
}

/**
 * The last ten runs as a table: one row per run with fixed columns, the row a button that opens
 * the run's detail - errors grouped by reason, notes and technical figures folded away. The newest
 * run carries the same word and numbers as the state in the page head.
 */
export default function LibraryRunList({
  runs,
  showMode,
  running,
  quotaRemedy,
  openNewest = false,
  signsIn = true,
  unreadableNames = [],
  fundortPrefix = null,
}: {
  /** Whether the source signs in; a directory, web or feed says „Nicht erreichbar“. */
  signsIn?: boolean
  /** Names of the scope entries the newest listing could not read. */
  unreadableNames?: string[]
  /** A prefix all Fundorte share (a web directory's address), left out of every line. */
  fundortPrefix?: string | null
  runs: IndexingRunResponse[]
  /** The source knows two modes (incremental and full); only then a run names its mode. */
  showMode: boolean
  /** The polled progress of the running run, the same count as the page head shows. */
  running: RunningProgress | null
  quotaRemedy: string
  /** Opens the newest run, e.g. when the head's state led here. */
  openNewest?: boolean
}) {
  const [open, setOpen] = useState<Record<string, boolean>>(() =>
    openNewest && runs[0] ? { [runs[0].id]: true } : {},
  )
  const [onlyProblems, setOnlyProblems] = useState(false)
  if (runs.length === 0) {
    return <Typography sx={{ ...LINE, color: 'text.secondary' }}>Noch keine Läufe.</Typography>
  }
  // a run with a problem: anything but a clean or a running one
  const problems = runs.filter((run) => {
    const word = runWord(run, signsIn)
    return word !== 'Abgeschlossen' && word !== 'Läuft'
  })
  const shown = onlyProblems ? problems : runs
  return (
    <Box>
      {/* the filter only on a long list, and only where it shortens it */}
      {runs.length >= FILTER_FROM && problems.length > 0 && problems.length < runs.length && (
        <ToggleButtonGroup
          size="small"
          exclusive
          value={onlyProblems ? 'problems' : 'all'}
          onChange={(_event, next: string | null) => next && setOnlyProblems(next === 'problems')}
          aria-label="Läufe filtern"
          sx={{ mb: 1.5 }}
        >
          <ToggleButton value="all">Alle</ToggleButton>
          <ToggleButton value="problems">Nur mit Fehlern ({problems.length})</ToggleButton>
        </ToggleButtonGroup>
      )}
      <Box
        aria-hidden
        sx={{
          ...COLUMNS,
          display: { xs: 'none', sm: 'grid' },
          pb: 0.75,
          borderBottom: 1,
          borderColor: 'divider',
          fontSize: 12,
          color: 'text.secondary',
        }}
      >
        <span>Zustand</span>
        <span>Beginn</span>
        <span>Dauer</span>
        <span>Auslöser</span>
        <span>Ergebnis</span>
        <span />
      </Box>
      <Box component="ul" aria-label="Läufe" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {shown.map((run) => {
          const isOpen = Boolean(open[run.id])
          const word = runWord(run, signsIn)
          const progress = run.status === 'RUNNING' ? running : null
          const label = progress ? `${word} · ${progress.percent} %` : word
          const detailId = `run-detail-${run.id}`
          // a failed run with nothing to open - no events, no metrics, no reason, no remedy - stays shut
          const expandable = !(
            run.status === 'FAILED' &&
            word !== 'Anmeldung abgelehnt' &&
            word !== 'Nicht erreichbar' &&
            word !== 'Zustimmung abgelaufen' &&
            run.events.length === 0 &&
            !run.metrics &&
            run.failureCategory !== 'QUOTA_EXHAUSTED' &&
            word !== 'Fehlgeschlagen'
          )
          const rowSx = {
            ...COLUMNS,
            width: '100%',
            textAlign: 'left',
            fontFamily: 'inherit',
            color: 'text.primary',
            py: 1,
            ...LINE,
          } as const
          const cells = (
            <>
              <Box sx={{ minWidth: 0 }}>
                <StateDot tone={runTone(word)} label={label} />
                <Box
                  component="span"
                  sx={{ display: { xs: 'inline', sm: 'none' }, color: 'text.secondary' }}
                >
                  {` · ${formatDay(run.startedAt)}`}
                </Box>
                {run.status !== 'FAILED' && (
                  <Box
                    component="span"
                    sx={{ display: { xs: 'block', sm: 'none' }, color: 'text.secondary' }}
                  >
                    {result(run, progress)}
                  </Box>
                )}
              </Box>
              <Box
                component="span"
                sx={{
                  display: { xs: 'none', sm: 'block' },
                  color: 'text.secondary',
                  whiteSpace: 'nowrap',
                }}
              >
                {formatStart(run.startedAt)}
              </Box>
              <Box
                component="span"
                sx={{ display: { xs: 'none', sm: 'block' }, color: 'text.secondary' }}
              >
                {formatDuration(run)}
              </Box>
              <Box
                component="span"
                sx={{ display: { xs: 'none', sm: 'block' }, color: 'text.secondary' }}
              >
                {trigger(run, showMode)}
              </Box>
              <Box
                component="span"
                sx={{
                  display: { xs: 'none', sm: 'block' },
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
                title={result(run, progress)}
              >
                {result(run, progress)}
              </Box>
              {expandable ? (
                <ExpandMoreIcon
                  aria-hidden
                  sx={{
                    fontSize: 20,
                    alignSelf: 'center',
                    color: 'text.secondary',
                    transform: isOpen ? 'rotate(180deg)' : 'none',
                  }}
                />
              ) : (
                <span />
              )}
            </>
          )
          return (
            <Box component="li" key={run.id} sx={{ borderBottom: 1, borderColor: 'divider' }}>
              {expandable ? (
                <ButtonBase
                  data-run-row
                  aria-expanded={isOpen}
                  aria-controls={detailId}
                  onClick={() => setOpen((prev) => ({ ...prev, [run.id]: !prev[run.id] }))}
                  sx={{
                    ...rowSx,
                    '&:hover': { bgcolor: 'action.hover' },
                    '&.Mui-focusVisible': { outline: 2, outlineColor: 'primary.main' },
                  }}
                >
                  {cells}
                </ButtonBase>
              ) : (
                <Box sx={rowSx}>{cells}</Box>
              )}
              {expandable && isOpen && (
                <Box id={detailId}>
                  <RunDetail
                    run={run}
                    word={word}
                    showMode={showMode}
                    quotaRemedy={quotaRemedy}
                    prefix={fundortPrefix}
                    unreadableNames={
                      run === runs.find((r) => r.status !== 'RUNNING') ? unreadableNames : []
                    }
                  />
                </Box>
              )}
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}
