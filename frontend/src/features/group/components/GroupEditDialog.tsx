import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Divider from '@mui/material/Divider'
import Typography from '@mui/material/Typography'
import type { GroupListResponse } from '@/shared/api/types'
import { useAuthStore } from '@/shared/session/authStore'
import { useGroupStore } from '../stores/groupStore'
import { notify } from '@/shared/stores/notificationStore'
import GroupStewardsSection from './GroupStewardsSection'
import GroupFormFields, { type GroupFormValues } from './GroupFormFields'
import { groupStateReason } from './groupListLabels'
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
 * Editing a group, as a dialog as for an account. An internal group has name,
 * description, sharing and protection as a draft that „Speichern" adopts, plus its
 * stewards, whose appointment takes effect immediately; a group from a provider or directory
 * only the protection - the source maintains the rest. Only the system administration decides
 * about the protection (ADR-0036, Entscheidung 9).
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

  const [values, setValues] = useState<GroupFormValues>({
    name: opened.name,
    description: opened.description ?? '',
    released: opened.releasedForUse,
    protectedGroup: opened.protectedGroup,
  })
  const { name, description, released, protectedGroup } = values
  const [error, setError] = useState<string | null>(null)
  const [nameMissing, setNameMissing] = useState(false)
  const [saving, setSaving] = useState(false)

  const reason = groupStateReason(group)
  const nameChanged =
    isInternal && (name.trim() !== opened.name || description.trim() !== (opened.description ?? ''))
  const releaseChanged = isInternal && released !== opened.releasedForUse
  const protectionChanged = protectedGroup !== opened.protectedGroup

  async function save() {
    if (isInternal && name.trim() === '') {
      setNameMissing(true)
      return
    }
    // nothing changed: there is nothing to save, the dialog just closes
    if (!nameChanged && !releaseChanged && !protectionChanged) {
      onClose()
      return
    }
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
    <FormDialog
      titleId="group-edit-title"
      title={`„${group.name}“ bearbeiten`}
      submitLabel="Speichern"
      onSubmit={() => void save()}
      onClose={onClose}
      busy={saving}
      busyAnnouncement="Wird gespeichert"
    >
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
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

      <GroupFormFields
        values={values}
        onChange={(next) => {
          setValues(next)
          setNameMissing(false)
        }}
        nameError={nameMissing ? 'Bitte einen Namen angeben.' : undefined}
        idPrefix="group-edit"
        canProtect
        isInternal={isInternal}
      />

      {isInternal && (
        <>
          <Divider sx={{ my: 2 }} />
          <GroupStewardsSection
            groupId={group.id}
            groupName={group.name}
            stewards={details?.stewards ?? group.stewards}
            currentUserId={currentUserId}
          />
        </>
      )}
    </FormDialog>
  )
}
