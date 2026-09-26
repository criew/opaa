import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { GroupListResponse } from '../../../types/api'
import { useAuthStore } from '../../../stores/authStore'
import { useGroupStore } from '../../../stores/groupStore'
import { notify } from '../../../stores/notificationStore'
import FieldLabel from '../../wizard/FieldLabel'
import GroupStewardsSection from '../../groups/GroupStewardsSection'
import { groupStateReason } from './groupListLabels'

const RELEASE_HELP = 'Erst freigegeben ist die Gruppe für andere Rechtevergebende wählbar.'

const PROTECTION_HELP =
  'Für Gruppen der Personalvertretung, der Schwerbehindertenvertretung, der Gleichstellung und ' +
  'für Personalvorgänge. Eine geschützte Gruppe ist nicht über die Suche auffindbar, erscheint in ' +
  'fremden Listen ohne Namen, und wer ihr ein Recht gibt, sieht weder ihre Mitglieder noch ihre ' +
  'Größe. Über den Schutz entscheidet die Systemverwaltung.'

const SOURCE_NOTICE: Record<string, string> = {
  ORG_UNIT:
    'Organisationseinheiten werden aus dem Verzeichnis abgeglichen. Name und Mitglieder pflegt die Quelle; hier entscheiden Sie nur über den Schutz der Gruppe.',
  IDENTITY_PROVIDER:
    'Diese Gruppe stammt aus dem Identitätsanbieter. Name und Mitglieder pflegt der Anbieter; hier entscheiden Sie nur über den Schutz der Gruppe.',
}

interface GroupEditDialogProps {
  /** The group to edit; the dialog is closed while this is null. */
  group: GroupListResponse | null
  onClose: () => void
}

/**
 * Bearbeiten einer Gruppe (#1978), als Dialog wie beim Konto. Eine interne Gruppe hat Name,
 * Beschreibung, Freigabe und Schutz als Entwurf, den „Speichern" übernimmt, dazu ihre
 * Verantwortlichen, deren Ernennung sofort wirkt; eine Gruppe aus einem Anbieter oder Verzeichnis
 * nur den Schutz - den Rest pflegt die Quelle. Über den Schutz entscheidet allein die
 * Systemverwaltung (ADR-0036, Entscheidung 9).
 */
export default function GroupEditDialog({ group, onClose }: GroupEditDialogProps) {
  if (!group) return null
  // Keyed by the group: every opening starts from that group's stored values.
  return <GroupEditDialogContent key={group.id} group={group} onClose={onClose} />
}

function GroupEditDialogContent({
  group: opened,
  onClose,
}: {
  group: GroupListResponse
  onClose: () => void
}) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  // The steward section reloads the groups of `useGroupStore` after every act; the dialog reads
  // the group from there once it is present, so a new name shows at once.
  const group = useGroupStore((s) => s.groups.find((entry) => entry.id === opened.id)) ?? opened
  const details = useGroupStore((s) => s.groupDetails[opened.id])
  const renameGroup = useGroupStore((s) => s.renameGroup)
  const changeRelease = useGroupStore((s) => s.changeRelease)
  const changeProtection = useGroupStore((s) => s.changeProtection)
  const isInternal = group.kind === 'AD_HOC'

  const [name, setName] = useState(opened.name)
  const [description, setDescription] = useState(opened.description ?? '')
  const [released, setReleased] = useState(opened.releasedForUse)
  const [protectedGroup, setProtectedGroup] = useState(opened.protectedGroup)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const reason = groupStateReason(group)
  const nameChanged =
    isInternal && (name.trim() !== opened.name || description.trim() !== (opened.description ?? ''))
  const releaseChanged = isInternal && released !== opened.releasedForUse
  const protectionChanged = protectedGroup !== opened.protectedGroup
  const canSave =
    (!isInternal || name.trim() !== '') && (nameChanged || releaseChanged || protectionChanged)

  async function save() {
    setError(null)
    setSaving(true)
    try {
      if (nameChanged) await renameGroup(group.id, name.trim(), description.trim())
      if (releaseChanged) await changeRelease(group.id, released)
      if (protectionChanged) await changeProtection(group.id, protectedGroup)
      notify(`„${isInternal ? name.trim() : group.name}“ wurde gespeichert.`, 'success')
      onClose()
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Änderungen konnten nicht gespeichert werden.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="group-edit-title">
      <DialogTitle id="group-edit-title">„{group.name}“ bearbeiten</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
          {group.provider ? group.provider.displayName : 'Interne Gruppe'}
          {group.sourcePath ? ` · ${group.sourcePath}` : ''}
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {reason && group.state !== 'NOT_RELEASED' && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {reason}
          </Alert>
        )}
        {!isInternal && SOURCE_NOTICE[group.kind] && (
          <Alert severity="info" sx={{ mb: 2 }}>
            {SOURCE_NOTICE[group.kind]}
          </Alert>
        )}

        <Stack spacing={2} sx={{ mb: isInternal ? 2 : 0 }}>
          {isInternal && (
            <>
              <Box>
                <FieldLabel htmlFor="group-edit-name">Name der Gruppe</FieldLabel>
                <TextField
                  id="group-edit-name"
                  fullWidth
                  size="small"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </Box>
              <Box>
                <FieldLabel htmlFor="group-edit-description">Beschreibung</FieldLabel>
                <TextField
                  id="group-edit-description"
                  fullWidth
                  size="small"
                  multiline
                  minRows={2}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </Box>
              <Box>
                <FormControlLabel
                  control={
                    <Switch checked={released} onChange={(e) => setReleased(e.target.checked)} />
                  }
                  label="Zur Verwendung freigegeben"
                />
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {RELEASE_HELP}
                </Typography>
              </Box>
            </>
          )}
          <Box>
            <FormControlLabel
              control={
                <Switch
                  checked={protectedGroup}
                  onChange={(e) => setProtectedGroup(e.target.checked)}
                />
              }
              label="Geschützte Gruppe"
            />
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              {PROTECTION_HELP}
            </Typography>
          </Box>
        </Stack>

        {isInternal && (
          <>
            <Divider sx={{ mb: 2 }} />
            <GroupStewardsSection
              groupId={group.id}
              groupName={group.name}
              stewards={details?.stewards ?? group.stewards}
              currentUserId={currentUserId}
            />
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>
          Abbrechen
        </Button>
        <Button variant="contained" onClick={() => void save()} disabled={!canSave || saving}>
          Speichern
        </Button>
      </DialogActions>
    </Dialog>
  )
}
