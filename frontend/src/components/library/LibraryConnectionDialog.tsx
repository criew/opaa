import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SourceTypeDescriptor } from '../../types/api'
import { useConnectionProfileOptions } from '../../hooks/useConnectionProfileOptions'
import { useLibraryStore } from '../../stores/libraryStore'
import { notify } from '../../stores/notificationStore'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import { effectiveConnection, selectableConnections } from './connectionChoice'

interface LibraryConnectionDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  descriptor: SourceTypeDescriptor
  /** The profile the library is connected through now; `null` for its own address. */
  current: { id: string; name: string } | null
}

/**
 * Connects a stored library through a profile ("Zugang zuordnen") or moves it to another one
 * ("Zugang wechseln"), also out of "Zugang entfernt" and out of the lock of its own address. Only
 * profiles the person may use can be chosen; the others stay visible with their notice.
 */
export default function LibraryConnectionDialog({
  open,
  onClose,
  libraryId,
  descriptor,
  current,
}: LibraryConnectionDialogProps) {
  const connectLibraryToProfile = useLibraryStore((s) => s.connectLibraryToProfile)
  const state = useConnectionProfileOptions(open ? descriptor.type : null)
  const [chosen, setChosen] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const others = state.options.filter((option) => option.id !== current?.id)
  const choice = effectiveConnection(chosen, selectableConnections(descriptor, others, false))
  const chosenName = others.find((option) => option.id === choice)?.name

  function close() {
    if (!submitting) onClose()
  }

  async function handleConnect() {
    if (choice === null) return
    setSubmitting(true)
    setError(null)
    try {
      await connectLibraryToProfile(libraryId, choice)
      notify(`Die Bibliothek ist jetzt über den Zugang „${chosenName ?? ''}“ verbunden.`, 'success')
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Zugang ließ sich nicht zuordnen.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>{current ? 'Zugang wechseln' : 'Zugang zuordnen'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2">
            Die Adresse der Bibliothek muss unter der Server-Adresse des gewählten Zugangs liegen;
            eine Adresse unter dem bisherigen Zugang wandert mit. Wechselt dabei der Server, werden
            die hinterlegten Zugangsdaten verworfen und müssen neu eingetragen werden.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <ConnectionProfileSelect
            descriptor={descriptor}
            state={state}
            value={choice}
            onChange={(next) => {
              setChosen(next)
              setError(null)
            }}
            offerOwnAddress={false}
            excludeProfileId={current?.id}
            idPrefix="library-connection"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          variant="contained"
          onClick={() => void handleConnect()}
          disabled={submitting || choice === null}
        >
          {submitting ? 'Wird zugeordnet …' : 'Zuordnen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
