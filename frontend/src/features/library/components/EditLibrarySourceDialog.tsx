import { useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import type { ConnectionProfileRef, SourceTypeKey } from '@/shared/api/types'
import { useLibraryStore } from '../stores/libraryStore'
import { sourceRegistration } from './sources/registry'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOfRef,
  withConnection,
  withoutFixed,
} from './sources/sourceConnection'
import { fieldDomId, SourceIssueContext } from './sources/sourceIssueContext'
import { allSourceIssues, sourceTestRequest } from './sources/sourceFrame'
import type { SourceFormContext } from './sources/types'
import type { SourceIssue } from '../utils/sourceIssue'
import ConnectionCheck from './sourceKit/ConnectionCheck'
import { useConnectionCheck } from './sourceKit/useConnectionCheck'
import ErrorSummary, { type SummaryIssue } from './sourceKit/ErrorSummary'

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
  library: EditableLibrarySource
}

/**
 * The Bearbeiten-Weg of the Reiter „Quelle": the form the library's source type registered, filled
 * from the stored configuration. Callers open it only for a type with a registered form. A library
 * on a profile is edited under what the library names of that profile; without it nothing can be
 * saved - the form would otherwise offer fields the profile sets.
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

  // Prefilled once from the library's current, non-secret configuration: the caller remounts this
  // component via a key tied to `open`, so a fresh instance starts from the stored values. The
  // credentials stay blank (write-only, ADR-0018) - there is nothing to prefill them with.
  const [initialValues] = useState<unknown>(() => configuration?.fromLibrary(library))
  const [storedValues, setValues] = useState<unknown>(initialValues)
  const values = withConnection(storedValues, connection)
  const [issues, setIssues] = useState<SourceIssue[]>([])
  const [otherIssues, setOtherIssues] = useState<SummaryIssue[]>([])
  const [submitting, setSubmitting] = useState(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const narrow = useMediaQuery(useTheme().breakpoints.down('sm'))
  const dirty = JSON.stringify(storedValues) !== JSON.stringify(initialValues)
  const check = useConnectionCheck(
    configuration && !connectionMissing ? sourceTestRequest(configuration, values, context) : null,
  )
  const checkId = 'edit-source-check'

  // an issue at a field goes once the field is valid (Prüfliste #29)
  const current =
    configuration && issues.length > 0 ? allSourceIssues(configuration, values, context) : []
  const shownIssues = issues
    .map((shown) => current.find((issue) => issue.field === shown.field && shown.field))
    .filter((issue): issue is SourceIssue => issue !== undefined)
  const summary: SummaryIssue[] = [
    ...shownIssues.map((issue) => ({
      message: issue.message,
      targetId: issue.field ? fieldDomId(context.idPrefix, issue.field) : undefined,
    })),
    ...otherIssues,
  ]

  function showIssues(source: SourceIssue[], other: SummaryIssue[]) {
    flushSync(() => {
      setIssues(source)
      setOtherIssues(other)
    })
    summaryRef.current?.focus()
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
    if (!configuration || connectionMissing || submitting || check.running) return
    const found = allSourceIssues(configuration, values, context)
    if (found.length > 0) {
      showIssues(found, [])
      return
    }
    // tested before saving, unless it passed or the person chose to go on (Prüfliste #33)
    if (check.available && !check.passed && !check.waived) {
      const outcome = await check.run()
      if (!outcome.ok) {
        showIssues(
          [],
          [
            {
              message:
                'Der Verbindungstest ist fehlgeschlagen. Angaben prüfen oder „Trotzdem fortfahren“ wählen.',
              targetId: checkId,
            },
          ],
        )
        return
      }
    }
    setIssues([])
    setOtherIssues([])
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        // name/description are resent unchanged - KnowledgeLibraryService#updateLibrary
        // overwrites both unconditionally, so omitting them here would wipe the description even
        // though this dialog only touches the source configuration.
        name: library.name,
        description: library.description ?? undefined,
        // Blank credentials stay undefined: the backend keeps the stored ones, but only while the
        // address still names the same origin.
        ...payloadUnder(configuration.toPayload(values), connectionFields(context)),
      })
      notify('Quelle gespeichert. Die Änderung wirkt ab dem nächsten Lauf.', 'success')
      onClose()
    } catch (err) {
      showIssues(
        [],
        [
          {
            message: err instanceof Error ? err.message : 'Die Quelle ließ sich nicht speichern.',
          },
        ],
      )
    } finally {
      setSubmitting(false)
    }
  }

  const Form = configuration?.Form

  return (
    <Dialog
      open={open}
      onClose={() => void handleClose()}
      maxWidth="sm"
      fullWidth
      fullScreen={narrow}
      aria-labelledby="edit-source-title"
    >
      <DialogTitle id="edit-source-title">Quelle bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ mt: 0.5 }}>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Änderungen wirken ab dem nächsten Lauf.
          </Typography>
          <ErrorSummary ref={summaryRef} issues={summary} />
          {connectionMissing && (
            <Typography data-testid="edit-source-connection-missing" sx={{ fontSize: 14 }}>
              Die Angaben des Zugangs „{profile?.name}“ liegen nicht vor. Bitte die Seite neu laden;
              besteht das Problem weiter, hilft die Systemverwaltung.
            </Typography>
          )}
          {Form && !connectionMissing && (
            <Box>
              <SourceIssueContext value={shownIssues}>
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
            </Box>
          )}
          {Form && !connectionMissing && check.available && (
            <ConnectionCheck check={check} id={checkId} />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 1.5, borderTop: 1, borderColor: 'divider' }}>
        <Button onClick={() => void handleClose()}>Abbrechen</Button>
        <BusyButton
          onClick={() => void handleSave()}
          variant="contained"
          busy={submitting || check.running}
          busyAnnouncement={check.running ? 'Verbindung wird getestet' : 'Quelle wird gespeichert'}
        >
          {check.running
            ? 'Verbindung wird getestet …'
            : submitting
              ? 'Wird gespeichert …'
              : 'Speichern'}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}
