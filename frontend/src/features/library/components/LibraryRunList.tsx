import { useEffect, useState, type ReactNode } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { IndexingRunResponse, SourceTypeKey } from '@/shared/api/types'
import StateDot from '@/shared/ui/StateDot'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { formatFileSize } from '@/shared/utils/labels'
import { useIndexingStore } from '../stores/indexingStore'
import { indexingRunEventCategoryLabel } from './indexingLabels'
import { LIBRARY_QUOTA_EXHAUSTED_REMEDY, QUOTA_EXHAUSTED_REMEDY } from './privateLibrary'
import { sourceRegistration } from './sources/registry'
import { plainMessage } from './sourceKit/plainMessage'
import { TEXT_LINK } from './setup/rowStyles'
import { runPartlyFailed, runPercent } from './steckbrief/libraryState'

/** Examples a group names before „alle n anzeigen“ (pruefliste-detail D13). */
const EXAMPLES = 5

/** Categories that report what happened besides the run's errors; they never count as failed. */
const HINT_CATEGORIES = new Set(['REMOVED', 'RATE_LIMITED', 'SUMMARY', 'FORMAT_MISMATCH'])

const REJECTED_SIGN_IN = new Set(['AUTH', 'CREDENTIALS_REJECTED'])

/** Columns of the run table: Zustand · Beginn · Dauer · Auslöser · Ergebnis (D12). */
const RUN_GRID = {
  display: 'grid',
  gridTemplateColumns: {
    xs: 'minmax(0, 1fr) auto',
    sm: '176px 96px 80px 136px minmax(0, 1fr)',
  },
  columnGap: 1.5,
  alignItems: 'baseline',
} as const

// A stable reference: a Zustand selector must not return a fresh array for an unchanged slice.
const EMPTY_RUN_HISTORY: IndexingRunResponse[] = []

function runState(run: IndexingRunResponse, signsIn: boolean): { tone: StatusTone; label: string } {
  if (run.status === 'RUNNING') return { tone: 'neutral', label: 'Läuft' }
  if (run.status === 'FAILED') {
    // a source without a sign-in (path, open web directory) was not reached, not refused
    if (run.failureCategory && REJECTED_SIGN_IN.has(run.failureCategory)) {
      return { tone: 'error', label: signsIn ? 'Anmeldung abgelehnt' : 'Nicht erreichbar' }
    }
    return { tone: 'error', label: 'Fehlgeschlagen' }
  }
  if (run.failureCategory === 'QUOTA_EXHAUSTED') return { tone: 'error', label: 'Kontingent voll' }
  if (runPartlyFailed(run)) return { tone: 'warning', label: 'Teilweise fehlgeschlagen' }
  return { tone: 'success', label: 'Abgeschlossen' }
}

function formatStart(value: string): string {
  const date = new Date(value)
  return `${date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' })} ${date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}`
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
  if (minutes > 0) return `${minutes} min`
  return `${seconds} s`
}

function triggerLabel(run: IndexingRunResponse, showMode: boolean): string {
  const trigger =
    run.triggeredBy === 'SCHEDULED'
      ? 'Zeitplan'
      : run.triggeredBy === 'WEBHOOK'
        ? 'Live'
        : 'manuell'
  if (showMode && run.runMode === 'FULL') return `${trigger} · Vollabgleich`
  if (run.runMode === 'EVENT') return `${trigger} · Ereignis`
  return trigger
}

function resultLabel(run: IndexingRunResponse): string {
  if (run.status === 'RUNNING') {
    const percent = runPercent(run)
    return percent == null ? 'wird ermittelt' : `${percent} %`
  }
  if (run.status === 'FAILED') return 'abgebrochen'
  const parts = [
    `${run.documentCount.toLocaleString('de-DE')} ${run.documentCount === 1 ? 'Dokument' : 'Dokumente'}`,
  ]
  if (run.documentsFailed > 0)
    parts.push(`${run.documentsFailed.toLocaleString('de-DE')} fehlgeschlagen`)
  if ((run.unreadableScopeCount ?? 0) > 0) parts.push(`${run.unreadableScopeCount} nicht lesbar`)
  if (run.incomplete) parts.push('unvollständig')
  return parts.join(' · ')
}

interface EventGroup {
  key: string
  label: string
  events: IndexingRunResponse['events']
}

function groupEvents(events: IndexingRunResponse['events']): {
  errors: EventGroup[]
  hints: EventGroup[]
} {
  const groups = new Map<string, EventGroup>()
  for (const event of events) {
    // a plain „Fehler“ names no reason: those group by their message instead (D13)
    const key = event.category === 'ERROR' ? `ERROR:${event.message}` : event.category
    const group = groups.get(key) ?? {
      key,
      label:
        event.category === 'ERROR'
          ? plainMessage(event.message)
          : indexingRunEventCategoryLabel(event.category),
      events: [],
    }
    group.events.push(event)
    groups.set(key, group)
  }
  const all = [...groups.values()].sort((a, b) => b.events.length - a.events.length)
  return {
    errors: all.filter((group) => !HINT_CATEGORIES.has(group.key)),
    hints: all.filter((group) => HINT_CATEGORIES.has(group.key)),
  }
}

function technicalLines(run: IndexingRunResponse): string[] {
  const metrics = run.metrics
  if (!metrics) return []
  const lines: string[] = []
  if (metrics.requestsSent > 0)
    lines.push(`${metrics.requestsSent.toLocaleString('de-DE')} Anfragen an die Quelle`)
  if (metrics.throttleCount > 0) {
    lines.push(`${metrics.throttleCount}-mal gedrosselt, ${metrics.throttleWaitSeconds} s gewartet`)
  }
  if (metrics.bytesDownloaded) lines.push(`${formatFileSize(metrics.bytesDownloaded)} geladen`)
  lines.push(
    `Anhänge: ${metrics.attachmentsProcessed} indiziert, ${metrics.attachmentsSkipped} übersprungen, ${metrics.attachmentsFailed} fehlgeschlagen`,
  )
  if (run.eventsTruncatedCount > 0) {
    lines.push(
      `${run.eventsTruncatedCount.toLocaleString('de-DE')} Ereignisse über der Speichergrenze`,
    )
  }
  return lines
}

function EventGroupView({ group, onShowAll }: { group: EventGroup; onShowAll: () => void }) {
  const count = group.events.length
  return (
    <Box sx={{ '& + &': { mt: 1.5 } }}>
      <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
        {group.label} · {count.toLocaleString('de-DE')}
      </Typography>
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {group.events.slice(0, EXAMPLES).map((event, index) => (
          <Typography
            component="li"
            key={`${group.key}-${index}`}
            title={plainMessage(event.message)}
            noWrap
            sx={{ fontSize: 13, lineHeight: '22px', color: 'text.secondary' }}
          >
            {event.reference ?? plainMessage(event.message)}
          </Typography>
        ))}
      </Box>
      {count > EXAMPLES && (
        <Link component="button" type="button" underline="hover" onClick={onShowAll} sx={TEXT_LINK}>
          alle {count.toLocaleString('de-DE')} anzeigen
        </Link>
      )}
    </Box>
  )
}

function AllEventsDialog({
  group,
  open,
  onClose,
}: {
  group: EventGroup | null
  open: boolean
  onClose: () => void
}) {
  // the group stays while the dialog fades out, so it never closes as an empty frame
  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth scroll="paper">
      <DialogTitle>
        {group?.label} · {group?.events.length.toLocaleString('de-DE')}
      </DialogTitle>
      <DialogContent dividers>
        <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {group?.events.map((event, index) => (
            <Box
              component="li"
              key={index}
              sx={{ py: 0.5, borderBottom: 1, borderColor: 'divider' }}
            >
              <Typography sx={{ fontSize: 13.5, overflowWrap: 'anywhere' }}>
                {event.reference ?? '–'}
              </Typography>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {plainMessage(event.message)}
              </Typography>
            </Box>
          ))}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}

/** Whether unfolding the run shows anything its row does not (D14); otherwise no chevron. */
function hasDetail(run: IndexingRunResponse): boolean {
  const signIn = run.failureCategory != null && REJECTED_SIGN_IN.has(run.failureCategory)
  return (
    run.events.length > 0 ||
    Boolean(run.metrics) ||
    (run.unreadableScopeCount ?? 0) > 0 ||
    run.failureCategory === 'QUOTA_EXHAUSTED' ||
    (run.status === 'FAILED' && Boolean(run.message) && !signIn)
  )
}

/** The expanded run: only what its row does not say (D14). */
function RunDetail({
  run,
  quotaRemedy,
  connectionAction,
}: {
  run: IndexingRunResponse
  quotaRemedy: string
  connectionAction?: ReactNode
}) {
  const [allOf, setAllOf] = useState<EventGroup | null>(null)
  const [allOpen, setAllOpen] = useState(false)
  const [hintsOpen, setHintsOpen] = useState(false)
  const [technicalOpen, setTechnicalOpen] = useState(false)
  const { errors, hints } = groupEvents(run.events)
  const technical = technicalLines(run)
  const hintCount = hints.reduce((sum, group) => sum + group.events.length, 0)
  const signIn = run.failureCategory != null && REJECTED_SIGN_IN.has(run.failureCategory)
  return (
    <Box sx={{ pl: { sm: '192px' } }}>
      {/* a refused sign-in stands in the row and at the connection; others say why here */}
      {run.status === 'FAILED' && run.message && !signIn && (
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>{plainMessage(run.message)}</Typography>
      )}
      {run.failureCategory === 'QUOTA_EXHAUSTED' && (
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>{quotaRemedy}</Typography>
      )}
      {(run.unreadableScopeCount ?? 0) > 0 && (
        <Box sx={{ mb: 1.5 }}>
          <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>
            Bereiche nicht lesbar · {run.unreadableScopeCount}
          </Typography>
          {connectionAction && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Namen an der Zeile Umfang
            </Typography>
          )}
        </Box>
      )}
      {errors.map((group) => (
        <EventGroupView
          key={group.key}
          group={group}
          onShowAll={() => {
            setAllOf(group)
            setAllOpen(true)
          }}
        />
      ))}
      <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap', mt: errors.length > 0 ? 1.5 : 0 }}>
        {hintCount > 0 && (
          <Link
            component="button"
            type="button"
            underline="hover"
            aria-expanded={hintsOpen}
            onClick={() => setHintsOpen((open) => !open)}
            sx={TEXT_LINK}
          >
            Hinweise · {hintCount.toLocaleString('de-DE')}
          </Link>
        )}
        {technical.length > 0 && (
          <Link
            component="button"
            type="button"
            underline="hover"
            aria-expanded={technicalOpen}
            onClick={() => setTechnicalOpen((open) => !open)}
            sx={TEXT_LINK}
          >
            Technische Angaben
          </Link>
        )}
      </Box>
      {hintsOpen && (
        <Box sx={{ mt: 1.5 }}>
          {hints.map((group) => (
            <EventGroupView
              key={group.key}
              group={group}
              onShowAll={() => {
                setAllOf(group)
                setAllOpen(true)
              }}
            />
          ))}
        </Box>
      )}
      {technicalOpen && (
        <Box component="ul" sx={{ m: 0, mt: 1, p: 0, listStyle: 'none' }}>
          {technical.map((line) => (
            <Typography component="li" key={line} sx={{ fontSize: 13, color: 'text.secondary' }}>
              {line}
            </Typography>
          ))}
        </Box>
      )}
      <AllEventsDialog group={allOf} open={allOpen} onClose={() => setAllOpen(false)} />
    </Box>
  )
}

/**
 * The last ten runs as a table: one row per run with fixed columns, the row a button that unfolds
 * the run's events grouped by reason (D12–D14).
 */
export default function LibraryRunList({
  libraryId,
  sourceType,
  privateLibrary,
  actions,
  connectionAction,
}: {
  libraryId: string
  sourceType: SourceTypeKey
  privateLibrary: boolean
  /** At the heading's right edge, e.g. „Vollabgleich starten“. */
  actions?: ReactNode
  /** The way to the connection from a refused sign-in. */
  connectionAction?: ReactNode
}) {
  const runs = useIndexingStore((s) => s.runHistoryByLibrary[libraryId] ?? EMPTY_RUN_HISTORY)
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)
  const showMode = Boolean(sourceRegistration(sourceType)?.configuration?.fullSyncRhythm)
  const signsIn = sourceRegistration(sourceType)?.configuration?.setup?.signsIn !== false
  const quotaRemedy = privateLibrary ? QUOTA_EXHAUSTED_REMEDY : LIBRARY_QUOTA_EXHAUSTED_REMEDY

  useEffect(() => {
    void loadRunHistory(libraryId)
  }, [libraryId, loadRunHistory])

  return (
    <Box component="section" aria-labelledby="library-runs-title">
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          gap: 2,
          mb: 1,
        }}
      >
        <Typography
          id="library-runs-title"
          component="h2"
          sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary' }}
        >
          Läufe
        </Typography>
        {actions}
      </Box>
      {runs.length === 0 ? (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>Noch keine Läufe.</Typography>
      ) : (
        <>
          <Box
            aria-hidden
            sx={{
              ...RUN_GRID,
              display: { xs: 'none', sm: 'grid' },
              px: 0,
              pr: 5,
              py: 0.5,
              borderBottom: 1,
              borderColor: 'divider',
              '& > span': {
                fontSize: 12,
                color: 'text.secondary',
              },
            }}
          >
            <span>Zustand</span>
            <span>Beginn</span>
            <span>Dauer</span>
            <span>Auslöser</span>
            <span>Ergebnis</span>
          </Box>
          <Box role="list" aria-label="Läufe">
            {runs.map((run) => {
              const state = runState(run, signsIn)
              const summaryRow = (
                <Box sx={{ ...RUN_GRID, width: '100%' }}>
                  <StateDot tone={state.tone} label={state.label} />
                  <Typography
                    component="span"
                    sx={{
                      fontSize: 13,
                      color: 'text.secondary',
                      fontVariantNumeric: 'tabular-nums',
                      textAlign: { xs: 'right', sm: 'left' },
                    }}
                  >
                    {formatStart(run.startedAt)}
                  </Typography>
                  <Typography
                    component="span"
                    sx={{
                      fontSize: 13,
                      color: 'text.secondary',
                      display: { xs: 'none', sm: 'inline' },
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {formatDuration(run)}
                  </Typography>
                  <Typography
                    component="span"
                    noWrap
                    sx={{
                      fontSize: 13,
                      color: 'text.secondary',
                      display: { xs: 'none', sm: 'block' },
                    }}
                  >
                    {triggerLabel(run, showMode)}
                  </Typography>
                  <Typography
                    component="span"
                    sx={{
                      fontSize: 13,
                      color: 'text.primary',
                      gridColumn: { xs: '1 / -1', sm: 'auto' },
                      pr: { sm: 1 },
                    }}
                  >
                    <Box component="span" sx={{ display: { sm: 'none' }, color: 'text.secondary' }}>
                      {`${formatDuration(run)} · ${triggerLabel(run, showMode)} · `}
                    </Box>
                    {resultLabel(run)}
                  </Typography>
                </Box>
              )
              if (!hasDetail(run)) {
                return (
                  <Box
                    key={run.id}
                    role="listitem"
                    sx={{ borderBottom: 1, borderColor: 'divider', py: 1, pr: 5, minHeight: 44 }}
                  >
                    {summaryRow}
                  </Box>
                )
              }
              return (
                <Accordion
                  key={run.id}
                  role="listitem"
                  disableGutters
                  square
                  elevation={0}
                  slotProps={{ heading: { component: 'div' } }}
                  sx={{
                    bgcolor: 'transparent',
                    borderBottom: 1,
                    borderColor: 'divider',
                    '&::before': { display: 'none' },
                  }}
                >
                  <AccordionSummary
                    expandIcon={<ExpandMoreIcon />}
                    sx={{
                      px: 0,
                      minHeight: 44,
                      '& .MuiAccordionSummary-content': { my: 1, minWidth: 0 },
                    }}
                  >
                    {summaryRow}
                  </AccordionSummary>
                  <AccordionDetails sx={{ px: 0, pt: 0, pb: 2 }}>
                    <RunDetail
                      run={run}
                      quotaRemedy={quotaRemedy}
                      connectionAction={connectionAction}
                    />
                  </AccordionDetails>
                </Accordion>
              )
            })}
          </Box>
        </>
      )}
    </Box>
  )
}
