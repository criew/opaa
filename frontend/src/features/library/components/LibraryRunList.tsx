import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
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
import { isErrorEvent, runTone, runWord } from './libraryState'
import { plainMessage } from './sourceKit/plainMessage'

/** Fundorte a group names before „alle n anzeigen“. */
const SHOWN = 5

/** The columns of a run row: Zustand · Beginn · Dauer · Auslöser · Ergebnis · chevron. */
const COLUMNS = {
  display: 'grid',
  gridTemplateColumns: {
    xs: 'minmax(0, 1fr) 20px',
    sm: '184px 124px 76px 96px minmax(0, 1fr) 20px',
  },
  columnGap: 1.5,
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

function formatShortStart(value: string): string {
  const date = new Date(value)
  return `${date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' })} ${date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
}

function formatStart(value: string): string {
  const date = new Date(value)
  return `${date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })} ${date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
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
  return 'manuell'
}

/** What the run yielded, in one form for every source: documents and errors, never both words. */
function result(run: IndexingRunResponse, running: RunningProgress | null): string {
  if (running) return `${count(running.done)} von ${count(running.total)}`
  if (run.status === 'FAILED') return '–'
  const documents = count(run.documentsIndexedTotal)
  if (run.documentsFailed > 0) return `${documents} · ${count(run.documentsFailed)} fehlgeschlagen`
  const unreadable = run.unreadableScopeCount ?? 0
  if (unreadable > 0) return `${documents} · ${unreadable} nicht lesbar`
  if (run.incomplete) return `${documents} · unvollständig`
  return `${documents} Dokumente`
}

/** Events of one reason, in the order the run met them. */
function groupByReason(events: RunEvent[]): Array<{ label: string; events: RunEvent[] }> {
  const groups = new Map<string, RunEvent[]>()
  for (const event of events) {
    const label = indexingRunEventCategoryLabel(event.category)
    groups.set(label, [...(groups.get(label) ?? []), event])
  }
  return [...groups].map(([label, entries]) => ({ label, events: entries }))
}

function technicalLines(run: IndexingRunResponse, showMode: boolean): string[] {
  const lines = [
    `Auslöser: ${indexingTriggerSourceLabel(run.triggeredBy)}${showMode ? ` · ${indexingRunModeLabel(run.runMode)}` : ''}`,
    `Verarbeitet: ${count(run.documentCount)} · übersprungen: ${count(run.documentsSkipped)}`,
  ]
  const metrics = run.metrics
  if (metrics) {
    const source = [`${count(metrics.requestsSent)} Anfragen an die Quelle`]
    if (metrics.throttleCount > 0)
      source.push(`${metrics.throttleCount}-mal gedrosselt (${metrics.throttleWaitSeconds} s)`)
    if (metrics.bytesDownloaded) source.push(`${formatFileSize(metrics.bytesDownloaded)} geladen`)
    lines.push(source.join(' · '))
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
}: {
  label: string
  events: RunEvent[]
  onShowAll: () => void
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
            title={plainMessage(event.message)}
            sx={{ ...LINE, color: 'text.secondary', overflowWrap: 'anywhere' }}
          >
            {event.reference ?? plainMessage(event.message)}
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
  showMode,
  quotaRemedy,
}: {
  run: IndexingRunResponse
  showMode: boolean
  quotaRemedy: string
}) {
  const [allOf, setAllOf] = useState<{ label: string; events: RunEvent[] } | null>(null)
  const errors = groupByReason(run.events.filter(isErrorEvent))
  const notes = run.events.filter((event) => !isErrorEvent(event))
  // only what the row cannot say: the reason of a failed run, the remedy of a full quota
  const reason =
    run.failureCategory === 'QUOTA_EXHAUSTED'
      ? quotaRemedy
      : run.status === 'FAILED' && run.message
        ? plainMessage(run.message)
        : null
  return (
    <Box sx={{ pl: { xs: 0, sm: 2.25 }, pt: 1, pb: 2 }}>
      {reason && <Typography sx={{ ...LINE, mb: 1 }}>{reason}</Typography>}
      {errors.length > 0 && (
        <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {errors.map((group) => (
            <EventGroup
              key={group.label}
              label={group.label}
              events={group.events}
              onShowAll={() => setAllOf(group)}
            />
          ))}
        </Box>
      )}
      {run.eventsTruncatedCount > 0 && (
        <Typography sx={{ ...LINE, color: 'text.secondary' }}>
          {count(run.eventsTruncatedCount)} weitere Ereignisse nicht gespeichert
        </Typography>
      )}
      {notes.length > 0 && (
        <Disclosure id={`run-notes-${run.id}`} label={`Hinweise · ${count(notes.length)}`}>
          <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {groupByReason(notes).map((group) => (
              <EventGroup
                key={group.label}
                label={group.label}
                events={group.events}
                onShowAll={() => setAllOf(group)}
              />
            ))}
          </Box>
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
        <DialogTitle id={`run-events-title-${run.id}`}>
          {allOf?.label} · {count(allOf?.events.length ?? 0)}
        </DialogTitle>
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
}: {
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
  if (runs.length === 0) {
    return <Typography sx={{ ...LINE, color: 'text.secondary' }}>Noch keine Läufe.</Typography>
  }
  return (
    <Box>
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
        {runs.map((run) => {
          const isOpen = Boolean(open[run.id])
          const word = runWord(run)
          const progress = run.status === 'RUNNING' ? running : null
          const label = progress ? `${word} · ${progress.percent} %` : word
          const detailId = `run-detail-${run.id}`
          return (
            <Box component="li" key={run.id} sx={{ borderBottom: 1, borderColor: 'divider' }}>
              <ButtonBase
                data-run-row
                aria-expanded={isOpen}
                aria-controls={detailId}
                onClick={() => setOpen((prev) => ({ ...prev, [run.id]: !prev[run.id] }))}
                sx={{
                  ...COLUMNS,
                  width: '100%',
                  textAlign: 'left',
                  fontFamily: 'inherit',
                  color: 'text.primary',
                  py: 1,
                  ...LINE,
                  '&:hover': { bgcolor: 'action.hover' },
                  '&.Mui-focusVisible': { outline: 2, outlineColor: 'primary.main' },
                }}
              >
                <Box sx={{ minWidth: 0 }}>
                  <StateDot tone={runTone(word)} label={label} />
                  <Box
                    component="span"
                    sx={{
                      display: { xs: 'block', sm: 'none' },
                      color: 'text.secondary',
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {formatShortStart(run.startedAt)} · {result(run, progress)}
                  </Box>
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
                >
                  {result(run, progress)}
                </Box>
                <ExpandMoreIcon
                  aria-hidden
                  sx={{
                    fontSize: 20,
                    alignSelf: 'center',
                    color: 'text.secondary',
                    transform: isOpen ? 'rotate(180deg)' : 'none',
                  }}
                />
              </ButtonBase>
              {isOpen && (
                <Box id={detailId}>
                  <RunDetail run={run} showMode={showMode} quotaRemedy={quotaRemedy} />
                </Box>
              )}
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}
