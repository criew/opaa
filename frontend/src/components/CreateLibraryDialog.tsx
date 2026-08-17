import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import TextField from '@mui/material/TextField'
import type { GroupListResponse, LibraryOwnerType } from '../types/api'
import { getMyGroups } from '../services/api'
import { useLibraryStore } from '../stores/libraryStore'

interface CreateLibraryDialogProps {
  open: boolean
  onClose: () => void
  onCreated: (libraryId: string) => void
}

export default function CreateLibraryDialog({
  open,
  onClose,
  onCreated,
}: CreateLibraryDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<LibraryOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [groups, setGroups] = useState<GroupListResponse[]>([])
  const [groupsError, setGroupsError] = useState<string | null>(null)
  const [groupsLoaded, setGroupsLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    void getMyGroups()
      .then((result) => {
        if (cancelled) return
        setGroups(result)
        setGroupsLoaded(true)
        setGroupsError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setGroups([])
        setGroupsLoaded(true)
        setGroupsError(err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden')
      })
    return () => {
      cancelled = true
    }
  }, [open])

  function handleClose() {
    if (submitting) return
    setName('')
    setDescription('')
    setOwnerType('USER')
    setSelectedGroup(null)
    setGroups([])
    setGroupsLoaded(false)
    setGroupsError(null)
    setError(null)
    onClose()
  }

  async function handleCreate() {
    const trimmedName = name.trim()
    if (!trimmedName) {
      setError('Name ist erforderlich')
      return
    }
    if (ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const libraryId = await createNewLibrary({
        name: trimmedName,
        description: description.trim() || undefined,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
      })
      setName('')
      setDescription('')
      setOwnerType('USER')
      setSelectedGroup(null)
      onCreated(libraryId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Wissensbibliothek erstellen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <TextField
          autoFocus
          label="Name"
          fullWidth
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 255 } }}
          sx={{ mt: 1 }}
        />
        <TextField
          label="Beschreibung"
          fullWidth
          multiline
          minRows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
          sx={{ mt: 2 }}
        />
        <FormControl sx={{ mt: 2 }}>
          <FormLabel id="library-owner-label">Eigentümer</FormLabel>
          <RadioGroup
            aria-labelledby="library-owner-label"
            value={ownerType}
            onChange={(e) => setOwnerType(e.target.value as LibraryOwnerType)}
          >
            <FormControlLabel value="USER" control={<Radio />} label="Mein Konto" />
            <FormControlLabel value="GROUP" control={<Radio />} label="Eine Gruppe" />
          </RadioGroup>
        </FormControl>
        {ownerType === 'GROUP' && (
          <>
            {groupsError && (
              <Alert severity="error" sx={{ mt: 2 }}>
                {groupsError}
              </Alert>
            )}
            {groupsLoaded && !groupsError && groups.length === 0 && (
              <Alert severity="info" sx={{ mt: 2 }}>
                Sie sind aktuell in keiner Gruppe Mitglied. Eine Bibliothek mit Gruppen-Eigentum
                lässt sich erst anlegen, sobald Sie einer Gruppe angehören.
              </Alert>
            )}
            <Autocomplete
              options={groups}
              getOptionLabel={(option) => option.name}
              value={selectedGroup}
              onChange={(_event, value) => setSelectedGroup(value)}
              disabled={groups.length === 0}
              renderInput={(params) => (
                <TextField {...params} label="Gruppe" placeholder="Gruppe auswählen …" />
              )}
              isOptionEqualToValue={(option, value) => option.id === value.id}
              sx={{ mt: 2 }}
            />
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button onClick={handleCreate} variant="contained" disabled={submitting || !name.trim()}>
          {submitting ? 'Wird erstellt …' : 'Erstellen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
