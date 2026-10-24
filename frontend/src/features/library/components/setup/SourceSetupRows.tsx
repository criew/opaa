import { useImperativeHandle, useRef, useState, type ReactNode, type Ref } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import type { SourceIssue } from '../../utils/sourceIssue'
import {
  validateScheduleValues,
  type ConfluenceFullSyncRhythm,
  type LibraryScheduleValues,
} from '../../utils/librarySchedule'
import LibraryScheduleForm from '../LibraryScheduleForm'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'
import ConnectionCheckView from './ConnectionCheckView'
import { useConnectionCheck } from './connectionCheck'
import { plainMessage } from '../sourceKit/plainMessage'
import ErrorSummary, { type SummaryEntry } from './ErrorSummary'
import { scheduleSummary } from './scheduleSummary'
import type { SetupRowId, SourceSetupSpec } from './setupTypes'
import SummaryRow from './SummaryRow'

const ROW_TITLES: Record<SetupRowId, string> = {
  connection: 'Verbindung',
  scope: 'Umfang',
  schedule: 'Zeitplan',
}

const SCHEDULE_FIELD = 'schedule.fullSyncDays'

export interface SourceSetupHandle {
  /** Checks every row at once and, where the source has one, runs the connection test. */
  submit: () => Promise<boolean>
  openRow: (row: SetupRowId) => void
}

interface SourceSetupRowsProps<V> {
  mode: 'create' | 'edit'
  spec: SourceSetupSpec<V>
  values: V
  onChange: (patch: Partial<V>) => void
  context: SourceFormContext
  schedule: LibraryScheduleValues
  onScheduleChange: (patch: Partial<LibraryScheduleValues>) => void
  rhythm?: ConfluenceFullSyncRhythm
  /** Create mode: the immediate first run under the schedule. */
  firstRun?: { value: boolean; onChange: (next: boolean) => void; hint: string }
  /** Above the connection fields: the choice of Zugang, the consent at the provider. */
  connectionPrelude?: ReactNode
  /** Leads the summary of „Verbindung“, e.g. the Zugang. */
  connectionLead?: string | null
  /** The rows shown; a reader sees only what every reader may. */
  rows?: SetupRowId[]
  /** Absent for a reader. */
  canEdit?: boolean
  headingLevel?: 'h2' | 'h3'
  /** The row open at first (create mode). */
  initialRow?: SetupRowId | null
  /** Edit mode: saves the row; rejects with the message to show at the row. */
  onSave?: (row: SetupRowId) => Promise<void>
  /** Edit mode: puts the row back to what is stored. */
  onDiscard?: (row: SetupRowId) => void
  /** Edit mode: whether the row differs from what is stored. */
  isDirty?: (row: SetupRowId) => boolean
  ref?: Ref<SourceSetupHandle>
}

/**
 * The setup of a source as quiet summary rows - Verbindung, Umfang, Zeitplan - of which at most one
 * is open. The same rows serve the wizard (with „Fertig“ and a check of all rows on „Weiter“) and
 * the Reiter „Quelle“ (with „Speichern“ per row). Validation shows every issue at once: at its
 * field in the open row, as a line under a closed row's summary, and gathered above the rows.
 */
export default function SourceSetupRows<V>({
  mode,
  spec,
  values,
  onChange,
  context,
  schedule,
  onScheduleChange,
  rhythm,
  firstRun,
  connectionPrelude,
  connectionLead,
  rows = ['connection', 'scope', 'schedule'],
  canEdit = true,
  headingLevel = 'h3',
  initialRow = null,
  onSave,
  onDiscard,
  isDirty,
  ref,
}: SourceSetupRowsProps<V>) {
  const [openRow, setOpenRow] = useState<SetupRowId | null>(initialRow)
  // fields whose issues are shown; an issue disappears as soon as its field is valid
  const [shownFields, setShownFields] = useState<Set<string>>(new Set())
  const [showSummary, setShowSummary] = useState(false)
  const [checkBlocks, setCheckBlocks] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const summaryRef = useRef<HTMLDivElement>(null)

  const fields = connectionFields(context)
  const request = spec.probe ? spec.probe(values, fields, context) : null
  const check = useConnectionCheck(request, spec.signsIn ?? true)
  const fieldId = (field: string) =>
    field === SCHEDULE_FIELD
      ? `${context.idPrefix}-schedule-full-sync-days`
      : `${context.idPrefix}-${field.replace(/\./g, '-')}`

  const scheduleIssue = validateScheduleValues(schedule, rhythm)
  const liveIssues: SourceIssue[] = [
    ...spec.issues(values, context),
    ...(scheduleIssue ? [{ field: SCHEDULE_FIELD, message: scheduleIssue }] : []),
  ]
  const rowOf = (field: string | undefined): SetupRowId =>
    field === SCHEDULE_FIELD ? 'schedule' : spec.rowOf(field ?? '')
  const shown = liveIssues.filter((issue) => shownFields.has(issue.field ?? ''))
  const issueAt = (field: string) => shown.find((issue) => issue.field === field)?.message ?? null
  const issuesOfRow = (row: SetupRowId) => shown.filter((issue) => rowOf(issue.field) === row)
  const checkFailed = check.state === 'failed' && !check.accepted

  function jumpTo(row: SetupRowId, targetId: string) {
    setOpenRow(row)
    requestAnimationFrame(() => {
      const target = document.getElementById(targetId)
      const control = target?.matches('input, textarea, button, [tabindex]')
        ? target
        : target?.querySelector<HTMLElement>('input, textarea, button')
      ;(control ?? target)?.focus()
    })
  }

  const entries: SummaryEntry[] = showSummary
    ? [
        ...[...shown]
          .sort((a, b) => rows.indexOf(rowOf(a.field)) - rows.indexOf(rowOf(b.field)))
          .map((issue) => ({
            text: `${ROW_TITLES[rowOf(issue.field)]}: ${issue.message}`,
            onJump: () => jumpTo(rowOf(issue.field), fieldId(issue.field ?? '')),
          })),
        ...(checkBlocks && checkFailed && check.outcome
          ? [
              {
                text: `Verbindung: Test fehlgeschlagen (${check.outcome.stages.find((s) => s.state === 'failed')?.label ?? 'Adresse'})`,
                onJump: () => jumpTo('connection', `${context.idPrefix}-check`),
              },
            ]
          : []),
      ]
    : []

  /** Shows the issues of `scope` (one row or all) and reports whether there were any. */
  function reveal(scope: SetupRowId | 'all'): SourceIssue[] {
    const found = liveIssues.filter((issue) => scope === 'all' || rowOf(issue.field) === scope)
    setShownFields((prev) => new Set([...prev, ...found.map((issue) => issue.field ?? '')]))
    return found
  }

  /** Runs the check unless it already passed or the person chose to go on; true when it may go on. */
  async function checkConnection(): Promise<boolean> {
    if (!request) return true
    if (check.state === 'passed' || check.state === 'partial' || check.accepted) return true
    const reached = await check.run()
    if (reached === 'failed') {
      setCheckBlocks(true)
      return false
    }
    return true
  }

  async function confirmLeave(row: SetupRowId | null): Promise<boolean> {
    if (mode !== 'edit' || row === null || !isDirty?.(row)) return true
    const discard = await confirmAction({
      question: `Änderungen an „${ROW_TITLES[row]}“ verwerfen?`,
      consequence: 'Ihre Eingaben in dieser Zeile gehen verloren.',
      confirmLabel: 'Verwerfen',
      tone: 'neutral',
    })
    if (discard) onDiscard?.(row)
    return discard
  }

  async function open(row: SetupRowId) {
    if (!(await confirmLeave(openRow))) return
    setSaveError(null)
    setOpenRow(row)
  }

  /** Wizard: closes the row if its fields are fine and opens the next one that still needs input. */
  async function finishRow(row: SetupRowId) {
    if (reveal(row).length > 0) {
      setShowSummary(true)
      return
    }
    if (row === 'connection' && request && check.state === 'unchecked') void check.run()
    const next = rows
      .slice(rows.indexOf(row) + 1)
      .find((r) => r !== 'schedule' && summaryOf(r) === null)
    setOpenRow(next ?? null)
  }

  async function saveRow(row: SetupRowId) {
    if (saving) return
    const found =
      row === 'schedule' ? reveal('schedule') : [...reveal('connection'), ...reveal('scope')]
    if (found.length > 0) {
      setShowSummary(true)
      requestAnimationFrame(() => summaryRef.current?.focus())
      return
    }
    if (row === 'connection' && !(await checkConnection())) {
      setShowSummary(true)
      return
    }
    setSaving(true)
    setSaveError(null)
    try {
      await onSave?.(row)
      setOpenRow(null)
      setShowSummary(false)
      setShownFields(new Set())
    } catch (err) {
      setSaveError(
        err instanceof Error && err.message
          ? err.message
          : 'Die Änderung ließ sich nicht speichern.',
      )
    } finally {
      setSaving(false)
    }
  }

  async function cancelRow(row: SetupRowId) {
    if (!(await confirmLeave(row))) return
    setOpenRow(null)
    setShowSummary(false)
    setShownFields(new Set())
    setSaveError(null)
  }

  useImperativeHandle(ref, () => ({
    submit: async () => {
      const found = reveal('all')
      if (found.length > 0) {
        setShowSummary(true)
        setOpenRow(rowOf(found[0].field))
        requestAnimationFrame(() => summaryRef.current?.focus())
        return false
      }
      if (!(await checkConnection())) {
        setShowSummary(true)
        setOpenRow('connection')
        requestAnimationFrame(() => summaryRef.current?.focus())
        return false
      }
      setShowSummary(false)
      return true
    },
    openRow: (row) => setOpenRow(row),
  }))

  function summaryOf(row: SetupRowId): string | null {
    if (row === 'schedule') {
      const base = scheduleSummary(schedule, rhythm)
      return firstRun?.value ? `${base} · erste Indizierung sofort` : base
    }
    const section = spec.sections.find((s) => s.id === row)
    const text = section?.summary(values, context, check.state) ?? null
    if (row === 'connection') {
      const parts = [connectionLead, text].filter(Boolean)
      return parts.length ? parts.join(' · ') : null
    }
    return text
  }

  function rowFooter(row: SetupRowId) {
    if (mode === 'create') {
      return (
        <Box sx={{ mt: 3 }}>
          <Button variant="text" size="small" onClick={() => void finishRow(row)} sx={{ ml: -1 }}>
            Übernehmen
          </Button>
        </Box>
      )
    }
    return (
      <Box sx={{ mt: 3 }}>
        {saveError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {saveError}
          </Alert>
        )}
        <Stack direction="row" spacing={1.5}>
          <BusyButton
            variant="contained"
            size="small"
            busy={saving}
            busyAnnouncement="Wird gespeichert"
            onClick={() => void saveRow(row)}
          >
            {saving
              ? 'Wird gespeichert …'
              : row === 'connection' && request
                ? 'Testen und speichern'
                : 'Speichern'}
          </BusyButton>
          <Button variant="text" size="small" onClick={() => void cancelRow(row)} disabled={saving}>
            Abbrechen
          </Button>
        </Stack>
      </Box>
    )
  }

  const props = { values, onChange, context, fields, issueAt, fieldId }

  return (
    <Box>
      <ErrorSummary entries={entries} ref={summaryRef} />
      {rows.map((row) => {
        const section = row === 'schedule' ? null : spec.sections.find((s) => s.id === row)
        if (row !== 'schedule' && !section && !(row === 'connection' && connectionPrelude))
          return null
        const Fields = section?.Fields
        return (
          <SummaryRow
            key={row}
            id={`${context.idPrefix}-row-${row}`}
            title={ROW_TITLES[row]}
            summary={summaryOf(row)}
            issues={[
              ...issuesOfRow(row).map((issue) => issue.message),
              ...(row === 'connection' && checkBlocks && checkFailed && check.outcome
                ? [`Test fehlgeschlagen: ${plainMessage(check.outcome.message)}`]
                : []),
            ]}
            open={openRow === row}
            onOpen={canEdit ? () => void open(row) : undefined}
            headingLevel={headingLevel}
          >
            <Stack spacing={2.5}>
              {row === 'connection' && connectionPrelude}
              {Fields && <Fields {...props} />}
              {row === 'connection' && request && (
                <ConnectionCheckView
                  check={check}
                  id={`${context.idPrefix}-check`}
                  allowAccept
                  onRun={() => {
                    // a test of incomplete fields only repeats what the fields already say
                    if (reveal('connection').length > 0) return
                    void check.run()
                  }}
                />
              )}
              {row === 'schedule' && (
                <>
                  <LibraryScheduleForm
                    idPrefix={`${context.idPrefix}-schedule`}
                    values={schedule}
                    onChange={onScheduleChange}
                    confluence={rhythm}
                  />
                  {issueAt(SCHEDULE_FIELD) && (
                    <Typography sx={{ fontSize: 12, color: 'error.main' }}>
                      {issueAt(SCHEDULE_FIELD)}
                    </Typography>
                  )}
                  {firstRun && (
                    <Box>
                      <FormControlLabel
                        control={
                          <Switch
                            checked={firstRun.value}
                            onChange={(e) => firstRun.onChange(e.target.checked)}
                          />
                        }
                        label="Erste Indizierung sofort nach dem Anlegen"
                      />
                    </Box>
                  )}
                </>
              )}
            </Stack>
            {canEdit && rowFooter(row)}
          </SummaryRow>
        )
      })}
    </Box>
  )
}
