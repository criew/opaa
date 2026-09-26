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
import GroupContactsSection from '../../groups/GroupContactsSection'
import GroupStewardsSection from '../../groups/GroupStewardsSection'
import { GROUP_KIND_SHORT_LABEL, groupStateReason } from './groupListLabels'

const RELEASE_HELP =
  'Erst freigegeben ist die Gruppe für andere Rechtevergebende wählbar. Das Schutzkennzeichen ' +
  'setzen und lösen die Verantwortlichen der Gruppe selbst – die Systemverwaltung kann es nicht; ' +
  'bei einer geschützten Gruppe gilt das auch für die Freigabe.'

const SOURCE_NOTICE: Record<string, string> = {
  ORG_UNIT:
    'Organisationseinheiten werden aus dem Verzeichnis synchronisiert. Name und Mitglieder pflegt die Quelle; hier legen Sie nur die Ansprechpersonen fest.',
  IDENTITY_PROVIDER:
    'Diese Gruppe stammt aus dem Identitätsanbieter und wird bei jeder Anmeldung abgeglichen. Name und Mitglieder pflegt der Anbieter; hier legen Sie nur die Ansprechpersonen fest.',
}

interface GroupEditDialogProps {
  /** The group to edit; the dialog is closed while this is null. */
  group: GroupListResponse | null
  onClose: () => void
}

/**
 * Bearbeiten einer Gruppe (#1978), als Dialog wie beim Konto. Eine interne Gruppe hat Name,
 * Beschreibung und Freigabe als Entwurf, den „Speichern" übernimmt, dazu ihre Verantwortlichen;
 * eine Gruppe aus einem Anbieter oder Verzeichnis nur ihre Ansprechpersonen - den Rest pflegt die
 * Quelle. Verantwortliche und Ansprechpersonen wirken sofort, jede Ernennung ist ein eigener Akt.
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
  // The steward and contact sections reload the groups of `useGroupStore` after every act; the
  // dialog reads the group from there once it is present, so a new name shows at once.
  const group = useGroupStore((s) => s.groups.find((entry) => entry.id === opened.id)) ?? opened
  const details = useGroupStore((s) => s.groupDetails[opened.id])
  const renameGroup = useGroupStore((s) => s.renameGroup)
  const changeRelease = useGroupStore((s) => s.changeRelease)
  const loadGroupDetails = useGroupStore((s) => s.loadGroupDetails)
  const isInternal = group.kind === 'AD_HOC'

  const [name, setName] = useState(opened.name)
  const [description, setDescription] = useState(opened.description ?? '')
  const [released, setReleased] = useState(opened.releasedForUse)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const reason = groupStateReason(group)
  const nameChanged =
    name.trim() !== opened.name || description.trim() !== (opened.description ?? '')
  const releaseChanged = released !== opened.releasedForUse
  const canSave = isInternal && name.trim() !== '' && (nameChanged || releaseChanged)

  async function save() {
    setError(null)
    setSaving(true)
    try {
      if (nameChanged) await renameGroup(group.id, name.trim(), description.trim())
      if (releaseChanged) await changeRelease(group.id, released)
      notify(`„${name.trim()}“ wurde gespeichert.`, 'success')
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
          {GROUP_KIND_SHORT_LABEL[group.kind]}
          {group.provider ? ` · ${group.provider.displayName}` : ' · Intern'}
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

        {isInternal && (
          <Stack spacing={2} sx={{ mb: 2 }}>
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
          </Stack>
        )}

        <Divider sx={{ mb: 2 }} />
        {isInternal ? (
          <GroupStewardsSection
            groupId={group.id}
            groupName={group.name}
            stewards={details?.stewards ?? group.stewards}
            currentUserId={currentUserId}
          />
        ) : (
          <>
            <GroupContactsSection
              groupId={group.id}
              contacts={details?.contacts ?? group.contacts ?? []}
              members={details?.members}
              currentUserId={currentUserId}
            />
            {/* Benennbar ist nur ein Mitglied; die Liste lädt erst auf ausdrücklichen Wunsch,
                denn ihr Abruf ist ein Audit-Ereignis (ADR-0036, Entscheidungen 4 und 9). */}
            {!details?.members && (
              <Button
                variant="outlined"
                size="small"
                sx={{ mt: 1.5 }}
                onClick={() => void loadGroupDetails(group.id)}
              >
                Mitgliederliste abrufen
              </Button>
            )}
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>
          {isInternal ? 'Abbrechen' : 'Schließen'}
        </Button>
        {isInternal && (
          <Button variant="contained" onClick={() => void save()} disabled={!canSave || saving}>
            Speichern
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
