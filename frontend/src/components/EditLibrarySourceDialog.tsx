import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SourceTypeKey } from '../types/api'
import {
  PROFILES_FORBIDDEN_NOTICE,
  useConnectionProfileOptions,
} from '../hooks/useConnectionProfileOptions'
import { useLibraryStore } from '../stores/libraryStore'
import { sourceRegistration } from './library/sources/registry'
import {
  connectionFields,
  payloadUnder,
  sourceConnectionOf,
  withConnection,
  withoutFixed,
} from './library/sources/sourceConnection'
import type { SourceFormContext } from './library/sources/types'

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
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
  // Optional/nullable to tolerate a LibraryResponse fixture that predates #542 finding 3 -
  // treated as "nothing stored" (false) rather than crashing or silently claiming otherwise.
  sourceCredentialsSet?: boolean | null
  sourceSettings?: Record<string, unknown> | null
  /** The profile the library is connected through; absent for its own address. */
  connectionProfile?: { id: string; name: string } | null
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
 * on a profile is edited under that profile; until its details are in, nothing can be saved - the
 * form would otherwise offer fields the profile fixes.
 */
export default function EditLibrarySourceDialog({
  open,
  onClose,
  libraryId,
  library,
}: EditLibrarySourceDialogProps) {
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const profileId = library.connectionProfile?.id ?? null
  const profileOptions = useConnectionProfileOptions(open && profileId ? library.sourceType : null)
  const profile = profileOptions.options.find((option) => option.id === profileId)
  const connection = profile ? sourceConnectionOf(profile) : undefined
  const connectionMissing = profileId !== null && connection === undefined
  const context: SourceFormContext = {
    mode: 'edit',
    sourceType: library.sourceType,
    idPrefix: 'edit-source',
    libraryId,
    credentialsStored: Boolean(library.sourceCredentialsSet),
    originalSourceUrl: library.sourceUrl,
    connection,
  }

  // Prefilled once from the library's current, non-secret configuration: the caller remounts this
  // component via a key tied to `open`, so a fresh instance starts from the stored values. The
  // credentials stay blank (write-only, ADR-0018) - there is nothing to prefill them with.
  const [storedValues, setValues] = useState<unknown>(() => configuration?.fromLibrary(library))
  const values = withConnection(storedValues, connection)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    if (!configuration || connectionMissing) return
    const validationError = configuration.validate(values, context)
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        // name/description are resent unchanged - KnowledgeLibraryService#updateLibrary
        // overwrites both unconditionally, so omitting them here would wipe the description even
        // though this dialog only touches the source configuration.
        name: library.name,
        description: library.description ?? undefined,
        // Blank credentials stay undefined: the backend keeps the stored ones, but only while the
        // address still names the same origin (#516/#542).
        ...payloadUnder(configuration.toPayload(values), connectionFields(context)),
      })
      onClose()
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Quellkonfiguration konnte nicht gespeichert werden',
      )
    } finally {
      setSubmitting(false)
    }
  }

  const Form = configuration?.Form

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Quellkonfiguration bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="info">
            Diese Änderung wirkt erst mit dem nächsten Indizierungslauf dieser Bibliothek.
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}
          {connectionMissing && !profileOptions.loaded && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              Die Angaben des Zugangs „{library.connectionProfile?.name}“ werden geladen …
            </Typography>
          )}
          {connectionMissing && profileOptions.loaded && (
            <Alert severity="warning" data-testid="edit-source-connection-missing">
              Die Angaben des Zugangs „{library.connectionProfile?.name}“ liegen nicht vor. Ohne sie
              lässt sich die Quelle nicht speichern, weil der Zugang Felder vorgeben kann.{' '}
              {profileOptions.forbidden
                ? PROFILES_FORBIDDEN_NOTICE
                : `${profileOptions.error ? `(${profileOptions.error}) ` : ''}Bitte später erneut versuchen; besteht das Problem weiter, hilft die Systemverwaltung.`}
            </Alert>
          )}
          {Form && !connectionMissing && (
            <Form
              values={values}
              context={context}
              onChange={(patch: object) => {
                setValues((prev: unknown) => ({
                  ...(withConnection(prev, connection) as object),
                  ...withoutFixed(patch, connection),
                }))
                setError(null)
              }}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          onClick={() => void handleSave()}
          variant="contained"
          disabled={submitting || !configuration || connectionMissing}
        >
          {submitting ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
