import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import type {
  DocumentSourceType,
  LibraryVisibility,
  SourceConnectionTestResponse,
} from '../types/api'
import { testLibrarySource } from '../services/api'
import { useLibraryStore } from '../stores/libraryStore'
import { documentSourceTypeConfigKind } from '../utils/labels'
import {
  deriveLibrarySourceConfigPayload,
  sameLibrarySourceOrigin,
  validateLibrarySourceFields,
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
  visibility: LibraryVisibility
  listed: boolean
  sourceType: DocumentSourceType
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
  // Optional/nullable to tolerate a LibraryResponse fixture that predates #542 finding 3 -
  // treated as "nothing stored" (false) rather than crashing or silently claiming otherwise.
  sourceCredentialsSet?: boolean | null
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
  const [sourcePath, setSourcePath] = useState(library.sourcePath ?? '')
  const [sourceUrl, setSourceUrl] = useState(library.sourceUrl ?? '')
  const [sourceProxy, setSourceProxy] = useState(library.sourceProxy ?? '')
  const [sourceCredentials, setSourceCredentials] = useState('')
  const [sourceInsecureSsl, setSourceInsecureSsl] = useState(Boolean(library.sourceInsecureSsl))
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // #544: mirrors CreateLibraryDialog's connection test - the result belongs to the currently
  // entered configuration only, so any edit to a field the test itself depends on invalidates a
  // previous result rather than leaving a stale "erreichbar" on screen for a since-changed
  // address.
  const [testResult, setTestResult] = useState<SourceConnectionTestResponse | null>(null)
  const [testErrorMessage, setTestErrorMessage] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)

  // #542 review finding 1: KnowledgeLibraryService only carries a stored credential forward when
  // the new sourceUrl still names the same origin as the stored one - a host change drops it,
  // requiring re-entry, so a caller without the credential cannot redirect it to a server they
  // control. This mirrors that check purely to phrase an accurate hint; the backend re-derives it
  // from the persisted value and remains the only authoritative check.
  const originChanged =
    credentialsStored &&
    configKind === 'url' &&
    sourceUrl.trim() !== '' &&
    !sameLibrarySourceOrigin(library.sourceUrl, sourceUrl)

  function handleClose() {
    if (submitting) return
    onClose()
  }

  // Shared by every onChange handler below (#544, mirroring CreateLibraryDialog's identical
  // helper) - the functional updater form bails out of re-rendering on every keystroke before a
  // test has ever run, by far the common case, rather than triggering an extra state update no
  // one can see.
  function clearTestResult() {
    setTestResult((prev) => (prev === null ? prev : null))
    setTestErrorMessage((prev) => (prev === null ? prev : null))
  }

  async function handleTest() {
    const validationError = validateLibrarySourceFields(library.sourceType, {
      sourcePath,
      sourceUrl,
    })
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setTestResult(null)
    setTestErrorMessage(null)
    setTesting(true)
    try {
      const result = await testLibrarySource({
        sourceType: library.sourceType,
        ...deriveLibrarySourceConfigPayload(library.sourceType, {
          sourcePath,
          sourceUrl,
          sourceProxy,
          sourceCredentials,
          sourceInsecureSsl,
        }),
        // #544: only sent when the credentials field is left blank - the backend then falls back
        // to this library's own stored credentials (but only if sourceUrl still names the same
        // origin as before, SourceConnectionTestService#withStoredCredentialsIfOmitted), so a
        // password-protected source can be tested without forcing the caller to re-type it. A
        // non-empty field always takes precedence, exactly like saving does.
        libraryId: sourceCredentials.trim() === '' ? libraryId : undefined,
      })
      setTestResult(result)
    } catch (err) {
      setTestErrorMessage(
        err instanceof Error ? err.message : 'Verbindung konnte nicht getestet werden',
      )
    } finally {
      setTesting(false)
    }
  }

  async function handleSave() {
    const validationError = validateLibrarySourceFields(library.sourceType, {
      sourcePath,
      sourceUrl,
    })
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await updateExistingLibrary(libraryId, {
        // name/description/visibility/listed are resent unchanged - KnowledgeLibraryService#
        // updateLibrary overwrites all four unconditionally, so omitting them here would wipe the
        // description and reset listed to false even though this dialog only touches the source
        // configuration.
        name: library.name,
        description: library.description ?? undefined,
        visibility: library.visibility,
        listed: library.listed,
        // Left blank -> sourceCredentials undefined -> backend keeps the currently stored
        // credentials unchanged, but only if sourceUrl still names the same origin as before;
        // otherwise it drops them regardless of what is sent here
        // (KnowledgeLibraryService#validateSourceConfigurationForUpdate, issue #516/#542 finding
        // 1). Only a non-empty value here ever replaces them outright.
        ...deriveLibrarySourceConfigPayload(library.sourceType, {
          sourcePath,
          sourceUrl,
          sourceProxy,
          sourceCredentials,
          sourceInsecureSsl,
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

  // #542 review, nit 3: must not claim a credential exists (or would be discarded by a host
  // change) when none is actually stored - blankToNull/hasSourceConfigurationFields do not
  // support removing a stored credential through this request, so credentialsStored can only
  // ever be widened here, never narrowed by anything the user types into this dialog.
  const credentialsHelperText = !credentialsStored
    ? 'Für diese Quelle sind aktuell keine Zugangsdaten hinterlegt. Nur ausfüllen, wenn die Quelle eine Anmeldung verlangt.'
    : originChanged
      ? 'Die Adresse zeigt auf einen anderen Server - die bestehenden Zugangsdaten werden dabei verworfen. Bitte bei Bedarf neu eingeben.'
      : 'Leer lassen, um die bestehenden Zugangsdaten beizubehalten. Wird nie in einer API-Antwort ausgegeben.'

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
            <TextField
              label="Verzeichnispfad"
              fullWidth
              required
              value={sourcePath}
              onChange={(e) => {
                setSourcePath(e.target.value)
                clearTestResult()
              }}
              placeholder="/data/dokumente"
              helperText="Absoluter Pfad auf dem Server, den OPAA regelmäßig einliest."
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
          )}

          {configKind === 'url' && (
            <>
              <TextField
                label="Adresse (URL)"
                fullWidth
                required
                value={sourceUrl}
                onChange={(e) => {
                  setSourceUrl(e.target.value)
                  clearTestResult()
                }}
                placeholder="https://files.example.com/dokumente/"
                helperText="http oder https."
                slotProps={{ htmlInput: { maxLength: 2000 } }}
              />
              <TextField
                label="Proxy"
                fullWidth
                value={sourceProxy}
                onChange={(e) => {
                  setSourceProxy(e.target.value)
                  clearTestResult()
                }}
                placeholder="proxy.example.com:8080"
                helperText="Optional."
                autoComplete="off"
                slotProps={{ htmlInput: { maxLength: 255 } }}
              />
              <TextField
                label="Neue Zugangsdaten"
                type="password"
                fullWidth
                value={sourceCredentials}
                onChange={(e) => {
                  setSourceCredentials(e.target.value)
                  clearTestResult()
                }}
                placeholder="benutzer:passwort"
                helperText={credentialsHelperText}
                autoComplete="new-password"
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={sourceInsecureSsl}
                    onChange={(e) => {
                      setSourceInsecureSsl(e.target.checked)
                      clearTestResult()
                    }}
                  />
                }
                label="Zertifikatsprüfung aussetzen"
              />
            </>
          )}

          <Box>
            <Button
              onClick={() => void handleTest()}
              disabled={testing}
              variant="outlined"
              size="small"
            >
              {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
            </Button>
            {testErrorMessage && (
              <Alert severity="error" sx={{ mt: 1 }}>
                {testErrorMessage}
              </Alert>
            )}
            {testResult && (
              <Alert severity={testResult.reachable ? 'success' : 'warning'} sx={{ mt: 1 }}>
                {testResult.message}
              </Alert>
            )}
          </Box>
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
