import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import type { UserSummary } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useGroupStore } from '../stores/groupStore'
import GroupFormFields, { type GroupFormValues } from './groups/GroupFormFields'
import InitialStewardsSection from './groups/InitialStewardsSection'

interface CreateGroupDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

const EMPTY_VALUES: GroupFormValues = {
  name: '',
  description: '',
  released: false,
  protectedGroup: false,
}

/**
 * „Gruppe anlegen“ (#1978), aufgebaut wie der Dialog „Bearbeiten“: dieselben Felder für Name,
 * Beschreibung, Freigabe und Schutz, darunter die Verantwortlichen - hier als Entwurf, den
 * „Anlegen“ in einem Schritt übernimmt.
 */
export default function CreateGroupDialog({ open, onClose, onCreated }: CreateGroupDialogProps) {
  // Mounted only while open: every opening starts from an empty draft.
  if (!open) return null
  return <CreateGroupDialogContent onClose={onClose} onCreated={onCreated} />
}

function CreateGroupDialogContent({ onClose, onCreated }: Omit<CreateGroupDialogProps, 'open'>) {
  const user = useAuthStore((s) => s.user)
  const isSystemAdmin = user?.systemRole === 'SYSTEM_ADMIN'
  const createNewGroup = useGroupStore((s) => s.createNewGroup)

  const [values, setValues] = useState<GroupFormValues>(EMPTY_VALUES)
  const [stewards, setStewards] = useState<UserSummary[]>(() =>
    user ? [{ id: user.id, displayName: user.displayName, email: user.email }] : [],
  )
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleClose() {
    if (!submitting) onClose()
  }

  async function handleCreate() {
    const name = values.name.trim()
    if (!name) {
      setError('Name ist erforderlich')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await createNewGroup(name, values.description.trim(), {
        releasedForUse: values.released,
        protectedGroup: values.protectedGroup,
        stewardIds: stewards.map((steward) => steward.id),
      })
      onCreated()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Gruppe konnte nicht angelegt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open onClose={handleClose} maxWidth="sm" fullWidth aria-labelledby="group-create-title">
      <DialogTitle id="group-create-title">Gruppe anlegen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        <GroupFormFields
          values={values}
          onChange={setValues}
          idPrefix="group-create"
          canProtect={isSystemAdmin}
          autoFocusName
        />
        <Divider sx={{ my: 2 }} />
        <InitialStewardsSection
          stewards={stewards}
          onChange={setStewards}
          currentUserId={user?.id}
          canRemoveSelf={isSystemAdmin}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button
          onClick={() => void handleCreate()}
          variant="contained"
          disabled={submitting || !values.name.trim()}
        >
          {submitting ? 'Wird angelegt …' : 'Anlegen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
