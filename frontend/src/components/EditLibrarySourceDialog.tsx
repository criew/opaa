import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import type { DocumentSourceType, LibraryVisibility } from '../types/api'
import { useLibraryStore } from '../stores/libraryStore'
import { documentSourceTypeConfigKind } from '../utils/labels'

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

  function handleClose() {
    if (submitting) return
    onClose()
  }

  async function handleSave() {
    const trimmedPath = sourcePath.trim()
    if (configKind === 'path' && !trimmedPath) {
      setError('Verzeichnispfad ist erforderlich')
      return
    }
    if (configKind === 'path' && !trimmedPath.startsWith('/')) {
      setError('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente')
      return
    }
    const trimmedUrl = sourceUrl.trim()
    if (configKind === 'url' && !trimmedUrl) {
      setError('Adresse (URL) ist erforderlich')
      return
    }
    if (configKind === 'url' && trimmedUrl && !/^https?:\/\//i.test(trimmedUrl)) {
      setError('Adresse (URL) muss mit http:// oder https:// beginnen')
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
        sourcePath: configKind === 'path' ? trimmedPath : undefined,
        sourceUrl: configKind === 'url' ? trimmedUrl : undefined,
        sourceProxy: configKind === 'url' && sourceProxy.trim() ? sourceProxy.trim() : undefined,
        // Left blank -> undefined -> backend keeps the currently stored credentials unchanged
        // (KnowledgeLibraryService#validateSourceConfigurationForUpdate, issue #516). Only a
        // non-empty value here replaces them.
        sourceCredentials:
          configKind === 'url' && sourceCredentials.trim() ? sourceCredentials.trim() : undefined,
        sourceInsecureSsl: configKind === 'url' ? sourceInsecureSsl : false,
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
            <TextField
              label="Verzeichnispfad"
              fullWidth
              required
              value={sourcePath}
              onChange={(e) => setSourcePath(e.target.value)}
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
                onChange={(e) => setSourceUrl(e.target.value)}
                placeholder="https://files.example.com/dokumente/"
                helperText="http oder https."
                slotProps={{ htmlInput: { maxLength: 2000 } }}
              />
              <TextField
                label="Proxy"
                fullWidth
                value={sourceProxy}
                onChange={(e) => setSourceProxy(e.target.value)}
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
                onChange={(e) => setSourceCredentials(e.target.value)}
                placeholder="benutzer:passwort"
                helperText="Leer lassen, um die bestehenden Zugangsdaten beizubehalten. Wird nie in einer API-Antwort ausgegeben."
                autoComplete="new-password"
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={sourceInsecureSsl}
                    onChange={(e) => setSourceInsecureSsl(e.target.checked)}
                  />
                }
                label="Zertifikatsprüfung aussetzen"
              />
            </>
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
