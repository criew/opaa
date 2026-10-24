import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
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
import { SourceIssueContext } from './sources/sourceIssueContext'
import type { SourceFormContext } from './sources/types'
import { formIssue, issueAt, type SourceIssue } from '../utils/sourceIssue'
import IssueSummary from './setup/IssueSummary'
import { ConnectionGateContext, useConnectionGateHost } from './setup/connectionGate'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import Typography from '@mui/material/Typography'
import { notify } from '@/shared/stores/notificationStore'
import { confirmAction } from '@/shared/stores/confirmStore'

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
  const [submitting, setSubmitting] = useState(false)
  const formRef = useRef<HTMLDivElement>(null)
  const summaryRef = useRef<HTMLDivElement>(null)
  const { gate, ensure } = useConnectionGateHost()
  const dirty = JSON.stringify(storedValues) !== JSON.stringify(initialValues)

  function report(found: SourceIssue[]) {
    setIssues(found)
    requestAnimationFrame(() => summaryRef.current?.focus())
  }

  async function handleClose() {
    if (submitting) return
    if (
      dirty &&
      !(await confirmAction({
        question: 'Änderungen an der Quelle verwerfen?',
        confirmLabel: 'Verwerfen',
        tone: 'neutral',
      }))
    ) {
      return
    }
    onClose()
  }

  async function handleSave() {
    if (submitting || !configuration || connectionMissing) return
    const found =
      configuration.validateAll?.(values, context) ??
      [configuration.validate(values, context)].filter((i): i is SourceIssue => i !== null)
    if (found.length > 0) {
      report(found)
      return
    }
    setIssues([])
    setSubmitting(true)
    if (!(await ensure())) {
      setSubmitting(false)
      report([
        issueAt(
          'connection',
          'Die Verbindung steht nicht; das Ergebnis des Tests steht unter „Verbindung“.',
        ),
      ])
      return
    }
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
      report([
        formIssue(err instanceof Error ? err.message : 'Die Quelle ließ sich nicht speichern.'),
      ])
    } finally {
      setSubmitting(false)
    }
  }

  const Form = configuration?.Form

  return (
    <Dialog open={open} onClose={() => void handleClose()} maxWidth="md" fullWidth>
      <DialogTitle>Quelle bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Änderungen wirken ab dem nächsten Indizierungslauf.
          </Typography>
          <IssueSummary ref={summaryRef} issues={issues} scope={() => formRef.current} />
          {connectionMissing && (
            <Alert severity="warning" data-testid="edit-source-connection-missing">
              Die Angaben des Zugangs „{profile?.name}“ liegen nicht vor. Ohne sie lässt sich die
              Quelle nicht speichern, weil der Zugang Felder vorgibt. Bitte die Seite neu laden;
              besteht das Problem weiter, hilft die Systemverwaltung.
            </Alert>
          )}
          {Form && !connectionMissing && (
            <Box ref={formRef}>
              <ConnectionGateContext value={gate}>
                <SourceIssueContext value={issues}>
                  <Form
                    values={values}
                    context={context}
                    onChange={(patch: object) => {
                      setValues((prev: unknown) => ({
                        ...(withConnection(prev, connection) as object),
                        ...withoutFixed(patch, connection),
                      }))
                      setIssues((prev) =>
                        prev.filter((issue) => !issue.field || !(issue.field in (patch as object))),
                      )
                    }}
                  />
                </SourceIssueContext>
              </ConnectionGateContext>
            </Box>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void handleClose()} disabled={submitting}>
          Abbrechen
        </Button>
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
