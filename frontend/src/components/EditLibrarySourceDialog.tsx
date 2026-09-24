import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import type {
  ConfluenceEdition,
  ConfluenceSpaceRef,
  DocumentSourceType,
  S3Settings,
} from '../types/api'
import ConfluenceSourceForm from './library/ConfluenceSourceForm'
import PathSourceForm from './library/PathSourceForm'
import S3SourceForm from './library/S3SourceForm'
import SourceConnectionTest from './library/SourceConnectionTest'
import UrlSourceForm from './library/UrlSourceForm'
import { s3ValuesFromSettings, type S3SourceValues } from '../utils/s3Source'
import { EMPTY_CONFLUENCE_VALUES, type ConfluenceSourceValues } from '../utils/confluenceSource'
import { useLibraryStore } from '../stores/libraryStore'
import { documentSourceTypeConfigKind } from '../utils/labels'
import {
  deriveLibrarySourceConfigPayload,
  sameLibrarySourceOrigin,
  validateLibrarySourceFields,
  type GenericSourceValues,
} from '../utils/librarySourceConfig'

/**
 * Editable snapshot of a connector library's source configuration. Deliberately narrower than
 * LibraryResponse - only what this dialog needs to prefill fields and resend the parts of
 * LibraryUpdateRequest that are not source-specific (name/description/visibility/listed), since
 * KnowledgeLibraryService#updateLibrary overwrites those unconditionally rather than leaving them
 * untouched when absent (unlike the source configuration fields themselves).
 */
export interface EditableLibrarySource {
  name: string
  description?: string | null
  listed: boolean
  sourceType: DocumentSourceType
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
  // Optional/nullable to tolerate a LibraryResponse fixture that predates #542 finding 3 -
  // treated as "nothing stored" (false) rather than crashing or silently claiming otherwise.
  sourceCredentialsSet?: boolean | null
  confluenceEdition?: ConfluenceEdition | null
  confluenceSpaces?: ConfluenceSpaceRef[] | null
  s3Settings?: S3Settings | null
}

interface EditLibrarySourceDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  library: EditableLibrarySource
}

export default function EditLibrarySourceDialog({
  open,
  onClose,
  libraryId,
  library,
}: EditLibrarySourceDialogProps) {
  const configKind = documentSourceTypeConfigKind[library.sourceType]
  const credentialsStored = Boolean(library.sourceCredentialsSet)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)

  // Prefilled once from the library's current, non-secret configuration via useState
  // initializers rather than an effect that calls setState on open (react-hooks/set-state-in-
  // effect - see LibraryDetailPage's LibraryDocumentsSection for the same pattern): the caller
  // remounts this component via a key tied to `open`, so a fresh instance starts with fresh
  // field values for free every time the dialog opens. sourceCredentials is deliberately left
  // blank (write-only, never returned by any API response, ADR-0018) - there is nothing to
  // prefill it with.
  const [generic, setGeneric] = useState<GenericSourceValues>(() => ({
    sourcePath: library.sourcePath ?? '',
    sourceUrl: library.sourceUrl ?? '',
    sourceProxy: library.sourceProxy ?? '',
    sourceCredentials: '',
    sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
  }))
  // ADR-0023: the edition is fixed, the stored credentials stand until new ones are typed, and the
  // current selection is the starting point.
  const [confluence, setConfluence] = useState<ConfluenceSourceValues>(() => ({
    ...EMPTY_CONFLUENCE_VALUES,
    sourceUrl: library.sourceUrl ?? '',
    sourceProxy: library.sourceProxy ?? '',
    sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
    edition: library.confluenceEdition ?? null,
    credentialsVerified: Boolean(library.sourceCredentialsSet),
    spaces: library.confluenceSpaces ?? [],
  }))
  // ADR-0027: endpoint, region, addressing style and scopes come back from the stored settings;
  // the stored key stands until a new one is typed.
  const [s3, setS3] = useState<S3SourceValues>(() =>
    s3ValuesFromSettings(
      library.sourceUrl,
      library.sourceProxy,
      library.sourceInsecureSsl,
      library.s3Settings,
    ),
  )
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    const validationError = validateLibrarySourceFields(library.sourceType, {
      ...generic,
      confluence,
      s3,
      // the stored key survives only on the same origin (KnowledgeLibraryService, #516/#542)
      s3CredentialsStored:
        credentialsStored && sameLibrarySourceOrigin(library.sourceUrl, s3.sourceUrl),
    })
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        // name/description/listed are resent unchanged - KnowledgeLibraryService#updateLibrary
        // overwrites all three unconditionally, so omitting them here would wipe the description
        // and reset listed to false even though this dialog only touches the source configuration.
        name: library.name,
        description: library.description ?? undefined,
        listed: library.listed,
        // Left blank -> sourceCredentials undefined -> backend keeps the currently stored
        // credentials unchanged, but only if sourceUrl still names the same origin as before;
        // otherwise it drops them regardless of what is sent here
        // (KnowledgeLibraryService#validateSourceConfigurationForUpdate, issue #516/#542 finding
        // 1). Only a non-empty value here ever replaces them outright.
        ...deriveLibrarySourceConfigPayload(library.sourceType, {
          ...generic,
          confluence,
          s3,
        }),
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

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Quellkonfiguration bearbeiten</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="info">
            Diese Änderung wirkt erst mit dem nächsten Indizierungslauf dieser Bibliothek.
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}

          {configKind === 'path' && (
            <PathSourceForm
              mode="edit"
              idPrefix="edit-source"
              values={generic}
              onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
            />
          )}

          {configKind === 'url' && (
            <UrlSourceForm
              mode="edit"
              sourceType={library.sourceType}
              idPrefix="edit-source"
              values={generic}
              onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
              credentialsStored={credentialsStored}
              originalSourceUrl={library.sourceUrl}
            />
          )}

          {configKind === 'confluence' && (
            <ConfluenceSourceForm
              mode="edit"
              idPrefix="edit-source-confluence"
              libraryId={libraryId}
              credentialsStored={credentialsStored}
              originalSourceUrl={library.sourceUrl}
              values={confluence}
              onChange={(patch) => {
                setConfluence((prev) => ({ ...prev, ...patch }))
                setError(null)
              }}
            />
          )}

          {configKind === 's3' && (
            <S3SourceForm
              mode="edit"
              idPrefix="edit-source-s3"
              libraryId={libraryId}
              credentialsStored={credentialsStored}
              originalSourceUrl={library.sourceUrl}
              values={s3}
              onChange={(patch) => {
                setS3((prev) => ({ ...prev, ...patch }))
                setError(null)
              }}
            />
          )}

          {(configKind === 'path' || configKind === 'url') && (
            // #1856 review: libraryId is always sent from here - without it the probe needs
            // CREATE_CONNECTOR_LIBRARY (ADR-0036, Entscheidung 5), a right a MANAGER on this
            // library need not hold. With it, the backend falls back to the stored credentials
            // only when the field is left blank and sourceUrl still names the same origin.
            <SourceConnectionTest
              sourceType={library.sourceType}
              values={generic}
              libraryId={libraryId}
              size="small"
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button onClick={() => void handleSave()} variant="contained" disabled={submitting}>
          {submitting ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
