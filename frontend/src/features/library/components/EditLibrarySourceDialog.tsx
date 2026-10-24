import { useCallback, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import type { ConnectionProfileRef, LibrarySchedule, SourceTypeKey } from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import ErrorSummary from './setup/ErrorSummary'
import ScheduleSetup from './setup/ScheduleSetup'
import { SetupContext, type RegisteredCheck } from './setup/setupContext'
import { SetupSection } from './setup/SourceSetupLayout'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  validateScheduleValues,
  type ConfluenceFullSyncRhythm,
  type LibraryScheduleValues,
} from '../utils/librarySchedule'
import { useLibraryStore } from '../stores/libraryStore'
import { sourceRegistration } from './sources/registry'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOfRef,
  withConnection,
  withoutFixed,
} from './sources/sourceConnection'
import { SourceIssueContext } from './sources/sourceIssueContext'
import type { SourceFormContext } from './sources/types'
import { formIssue, issueAt, type SourceIssue } from '../utils/sourceIssue'

/**
 * Editable snapshot of a connector library's source configuration. Deliberately narrower than
 * LibraryResponse - only what this dialog needs to prefill fields and resend the parts of
 * LibraryUpdateRequest that are not source-specific (name/description), since
 * KnowledgeLibraryService#updateLibrary overwrites those unconditionally rather than leaving them
 * untouched when absent (unlike the source configuration fields themselves).
 */
export interface EditableLibrarySource {
  name: string
  description?: string | null
  sourceType: SourceTypeKey
  sourcePath?: string | null
  sourceUrl?: string | null
  // Optional/nullable to tolerate a LibraryResponse fixture without the field -
  // treated as "nothing stored" (false) rather than crashing or silently claiming otherwise.
  sourceCredentialsSet?: boolean | null
  sourceSettings?: Record<string, unknown> | null
  /** The profile the library is connected through, with what it sets; absent for its own address. */
  connectionProfile?: ConnectionProfileRef | null
  /** Signs in with its owner's connected account, never with a secret of its own. */
  privateLibrary?: boolean
}

interface EditLibrarySourceDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  library: EditableLibrarySource & {
    schedule?: LibrarySchedule | null
    fullSyncIntervalDefaultDays?: number | null
  }
}

/**
 * The Bearbeiten-Weg of the Reiter „Quelle": the registered form on the same frame as in the
 * wizard - Verbindung, Zugangsdaten, Umfang, Zeitplan - filled from the stored configuration.
 * „Speichern“ validates everything at once, runs the connection check and quits with a
 * notification; closing a changed dialog asks first.
 */
export default function EditLibrarySourceDialog({
  open,
  onClose,
  libraryId,
  library,
}: EditLibrarySourceDialogProps) {
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const profile = library.connectionProfile ?? null
  const connection = (profile && sourceConnectionOfRef(profile)) ?? undefined
  const connectionMissing = profile !== null && connection === undefined
  const context: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'edit-source',
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
    privateLibrary: library.privateLibrary,
  }
  const rhythm: ConfluenceFullSyncRhythm | undefined = configuration?.fullSyncRhythm
    ? {
        intervalDays:
          (library.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ?? null,
        defaultDays: library.fullSyncIntervalDefaultDays ?? null,
      }
    : undefined
  // Prefilled once from the stored, non-secret configuration; the caller remounts on open.
  const [initial] = useState(() => ({
    values: configuration?.fromLibrary(library) as unknown,
    schedule: scheduleValuesFrom(library.schedule, rhythm),
  }))
  const [storedValues, setValues] = useState<unknown>(initial.values)
  const [schedule, setSchedule] = useState<LibraryScheduleValues>(initial.schedule)
  const values = withConnection(storedValues, connection)
  const [issues, setIssues] = useState<SourceIssue[]>([])
  const [submitting, setSubmitting] = useState(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const checkRef = useRef<RegisteredCheck | null>(null)
  const registerCheck = useCallback((check: RegisteredCheck | null) => {
    checkRef.current = check
  }, [])
  const dirty =
    JSON.stringify(storedValues) !== JSON.stringify(initial.values) ||
    JSON.stringify(schedule) !== JSON.stringify(initial.schedule)

  function collect(): SourceIssue[] {
    if (!configuration) return []
    const found = configuration.validateAll
      ? configuration.validateAll(values, context)
      : [configuration.validate(values, context)].filter(
          (issue): issue is SourceIssue => issue !== null,
        )
    const scheduleError = validateScheduleValues(schedule, rhythm)
    return scheduleError ? [...found, issueAt('schedule', scheduleError)] : found
  }
  // once shown, issues follow the input: a fixed field loses its message at once
  const shownIssues =
    issues.length === 0
      ? issues
      : collect()
          .filter((issue) => issues.some((before) => before.field === issue.field))
          .concat(issues.filter((issue) => !issue.field))

  function show(next: SourceIssue[]) {
    setIssues(next)
    setTimeout(() => summaryRef.current?.focus(), 0)
  }

  async function handleClose() {
    if (submitting) return
    if (dirty) {
      const discard = await confirmAction({
        question: 'Änderungen an der Quelle verwerfen?',
        confirmLabel: 'Verwerfen',
        tone: 'neutral',
      })
      if (!discard) return
    }
    onClose()
  }

  async function handleSave() {
    if (submitting || !configuration || connectionMissing) return
    const found = collect()
    if (found.length > 0) {
      show(found)
      return
    }
    setSubmitting(true)
    if (checkRef.current) {
      const outcome = await checkRef.current.ensure()
      if (outcome === 'failed') {
        setSubmitting(false)
        show([
          formIssue(
            'Der Verbindungstest ist fehlgeschlagen. Prüfen Sie die Angaben oder wählen Sie „Trotzdem fortfahren“.',
          ),
        ])
        return
      }
    }
    setIssues([])
    try {
      const source = payloadUnder(configuration.toPayload(values), connectionFields(context))
      const scheduled = scheduleUpdateFrom(schedule, rhythm)
      const base = configuration.rhythmSettingsBase?.(library) ?? undefined
      const sourceSettings =
        source.sourceSettings || scheduled.sourceSettings
          ? { ...base, ...source.sourceSettings, ...scheduled.sourceSettings }
          : undefined
      await updateExistingLibrary(libraryId, {
        // name/description are resent unchanged - the backend overwrites both unconditionally
        name: library.name,
        description: library.description ?? undefined,
        ...source,
        ...scheduled,
        sourceSettings,
      })
      notify('Quelle gespeichert. Die Änderung gilt ab dem nächsten Lauf.', 'success')
      onClose()
    } catch (err) {
      show([
        formIssue(err instanceof Error ? err.message : 'Die Quelle ließ sich nicht speichern.'),
      ])
    } finally {
      setSubmitting(false)
    }
  }

  const Form = configuration?.Form
  const framed = Boolean(configuration?.checkStages)
  const scheduleSection = (
    <ScheduleSetup
      idPrefix="edit-source-schedule"
      values={schedule}
      onChange={(patch) => setSchedule((prev) => ({ ...prev, ...patch }))}
      rhythm={rhythm}
    />
  )
  return (
    <Dialog open={open} onClose={() => void handleClose()} maxWidth="sm" fullWidth scroll="paper">
      <DialogTitle>Quelle bearbeiten</DialogTitle>
      <DialogContent dividers>
        <ErrorSummary ref={summaryRef} issues={shownIssues} idPrefix="edit-source" />
        {connectionMissing && (
          <Alert severity="warning" data-testid="edit-source-connection-missing" sx={{ mb: 2 }}>
            Die Angaben des Zugangs „{profile?.name}“ liegen nicht vor. Bitte die Seite neu laden;
            besteht das Problem weiter, hilft die Systemverwaltung.
          </Alert>
        )}
        {Form && !connectionMissing && (
          <SetupContext
            value={{
              idPrefix: 'edit-source',
              issues: shownIssues,
              schedule: framed ? scheduleSection : undefined,
              registerCheck,
            }}
          >
            <SourceIssueContext value={shownIssues[0] ?? null}>
              <Form
                values={values}
                context={context}
                onChange={(patch: object) => {
                  setValues((prev: unknown) => ({
                    ...(withConnection(prev, connection) as object),
                    ...withoutFixed(patch, connection),
                  }))
                }}
              />
            </SourceIssueContext>
            {!framed && (
              <Box sx={{ mt: 4 }}>
                <SetupSection id="edit-source-section-schedule" title="Zeitplan">
                  {scheduleSection}
                </SetupSection>
              </Box>
            )}
          </SetupContext>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void handleClose()}>Abbrechen</Button>
        <BusyButton
          onClick={() => void handleSave()}
          variant="contained"
          busy={submitting}
          busyAnnouncement="Quelle wird gespeichert"
        >
          {submitting ? 'Wird gespeichert …' : 'Speichern'}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}
