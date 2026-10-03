import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SpaceResponse, SpaceRole } from '../../types/api'
import { notify } from '../../stores/notificationStore'
import { useSpaceStore } from '../../stores/spaceStore'
import { spaceRoleLabel } from '../../utils/labels'
import SubjectFormRow from '../permissions/SubjectFormRow'
import SubjectPicker from '../permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from '../permissions/subjectSelection'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'
import SpaceMemberList from './SpaceMemberList'
import { memberLabelOf, memberNoticeLabelOf } from './memberLabel'

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

function counted(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/** Who belongs to the space without a name, e.g. "10 Personen und 2 Gruppen". */
function subjectCountsLabel(space: SpaceResponse): string {
  const persons = counted(space.memberships.userCount, 'Person', 'Personen')
  const groups = space.memberships.groupCount
  return groups > 0 ? `${persons} und ${counted(groups, 'Gruppe', 'Gruppen')}` : persons
}

/**
 * The membership rows per role, e.g. "Rollen: 2 Administratoren, 1 Kurator, 9 Mitglieder"; a group
 * row counts once, like in the line above. Roles nobody holds are left out.
 */
function roleCountsLabel(space: SpaceResponse): string {
  const parts = [
    counted(space.roleCounts?.ADMIN ?? 0, 'Administrator', 'Administratoren'),
    counted(space.roleCounts?.CURATOR ?? 0, 'Kurator', 'Kuratoren'),
    counted(space.roleCounts?.MEMBER ?? 0, 'Mitglied', 'Mitglieder'),
  ].filter((part) => !part.startsWith('0 '))
  return `Rollen: ${parts.join(', ')}`
}

/** How the notice after adding names the chosen subject. */
function addedSubjectLabel(subject: SubjectSelection): string {
  if (subject.type === 'GROUP') {
    return subject.group?.protectedGroup
      ? 'Geschützte Gruppe'
      : `Gruppe ${subject.group?.name ?? ''}`.trim()
  }
  return subject.user?.displayName ?? subject.user?.email ?? 'Mitglied'
}

interface SpaceMembersSectionProps {
  spaceId: string
  space: SpaceResponse
  /** Mitglieder und Rollen ändert nur ein Administrator des Space. */
  canManage: boolean
  isOwner: boolean
}

/** Der Reiter „Mitglieder" der Space-Einstellungen (#1917): Liste, Rollen und Aufnahme. */
export default function SpaceMembersSection({
  spaceId,
  space,
  canManage,
  isOwner,
}: SpaceMembersSectionProps) {
  const storeError = useSpaceStore((s) => s.error)
  const members = useSpaceStore((s) => s.members)
  const isLoadingMembers = useSpaceStore((s) => s.isLoadingMembers)
  const loadMembers = useSpaceStore((s) => s.loadMembers)
  const addMember = useSpaceStore((s) => s.addMember)
  const updateMemberRole = useSpaceStore((s) => s.updateMemberRole)
  const removeMember = useSpaceStore((s) => s.removeMember)
  const transferOwnership = useSpaceStore((s) => s.transferOwnership)
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const [newMemberRole, setNewMemberRole] = useState<SpaceRole>('MEMBER')
  // A failure stays until it is closed or the next action starts; a success is a popup only.
  const [localError, setLocalError] = useState<string | null>(null)
  const navigate = useNavigate()

  function clearFailure() {
    setLocalError(null)
    useSpaceStore.setState({ error: null })
  }

  // #144: the service answers the list only for ADMIN, the owner and the system administration;
  // for everybody else it stays empty and the tab shows the counts instead.
  useEffect(() => {
    void loadMembers(spaceId)
  }, [loadMembers, spaceId])

  return (
    <Stack spacing={2}>
      {(localError || storeError) && (
        <Alert
          severity="error"
          onClose={() => {
            if (localError) setLocalError(null)
            else useSpaceStore.setState({ error: null })
          }}
        >
          {localError ?? storeError}
        </Alert>
      )}
      {space.isDefault && space.memberCount === 1 && (
        // #777: this hint used to replace the whole members section, including the
        // "Mitglied hinzufügen"-Formular it explicitly promises - the default space is "ein
        // Space wie jeder andere" (docs/features/spaces-and-assets.md), so members can be
        // added here just like on any other space.
        <Alert severity="info">
          Dies ist Ihr Standard-Space. Sie arbeiten hier allein — Sie können jederzeit Mitglieder
          hinzufügen.
        </Alert>
      )}
      {isLoadingMembers ? (
        <Typography sx={{ color: 'text.secondary' }}>Mitgliederliste wird geladen …</Typography>
      ) : members.length === 0 && !canManage && !isOwner ? (
        // A MEMBER or CURATOR gets no names (the service answers the list with an empty one),
        // only how large the space is per role.
        <Stack spacing={0.5}>
          <Typography sx={{ fontSize: 13.5 }}>{subjectCountsLabel(space)}</Typography>
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            {roleCountsLabel(space)}
          </Typography>
        </Stack>
      ) : (
        <Stack spacing={0}>
          <SpaceMemberList
            spaceId={spaceId}
            ownerId={space.ownerId}
            isDefaultSpace={space.isDefault}
            members={members}
            canManage={canManage}
            isOwner={isOwner}
            onRoleChange={async (member, role) => {
              clearFailure()
              try {
                await updateMemberRole(spaceId, member.id, role)
                notify(`Rolle von ${memberLabelOf(member)}: ${spaceRoleLabel(role)}`, 'success')
              } catch (err) {
                setLocalError(err instanceof Error ? err.message : 'Rollenänderung fehlgeschlagen')
              }
            }}
            onRemove={async (member) => {
              clearFailure()
              try {
                const refresh = await removeMember(spaceId, member.id)
                if (refresh === 'accessLost') {
                  // The removal took the caller's own access: this view no longer applies.
                  notify(
                    `${memberNoticeLabelOf(member)} entfernt. Sie haben keinen Zugang mehr zu „${space.name}“.`,
                    'success',
                  )
                  navigate('/spaces', { replace: true })
                  return
                }
                notify(`${memberNoticeLabelOf(member)} entfernt`, 'success')
              } catch (err) {
                setLocalError(
                  err instanceof Error ? err.message : 'Entfernen des Mitglieds fehlgeschlagen',
                )
              }
            }}
            onMakeOwner={async (member) => {
              clearFailure()
              try {
                await transferOwnership(spaceId, member.subjectId)
                notify(`Verantwortung an ${memberLabelOf(member)} übertragen`, 'success')
              } catch (err) {
                setLocalError(
                  err instanceof Error
                    ? err.message
                    : 'Übertragung der Verantwortung fehlgeschlagen',
                )
              }
            }}
          />

          {canManage && (
            <Stack spacing={1} sx={{ pt: 2 }}>
              {/* The tab already reads "Mitglieder": this form is the panel's h2. */}
              <SectionHead>Mitglied hinzufügen</SectionHead>
              <SubjectFormRow
                picker={
                  <SubjectPicker
                    value={subject}
                    onChange={setSubject}
                    excludedUserIds={members
                      .filter((member) => member.subjectType === 'USER')
                      .map((member) => member.subjectId)}
                    excludedGroupIds={members
                      .filter((member) => member.subjectType === 'GROUP')
                      .map((member) => member.subjectId)}
                  />
                }
                role={
                  <Select
                    size="small"
                    fullWidth
                    value={newMemberRole}
                    onChange={(event) => setNewMemberRole(event.target.value as SpaceRole)}
                    aria-label="Rolle des neuen Mitglieds"
                  >
                    {editableRoles.map((role) => (
                      <MenuItem key={role} value={role}>
                        {spaceRoleLabel(role)}
                      </MenuItem>
                    ))}
                  </Select>
                }
                action={
                  <Button
                    variant="contained"
                    disabled={!selectedSubjectId(subject)}
                    onClick={async () => {
                      const subjectId = selectedSubjectId(subject)
                      // Eine Space-Mitgliedschaft kennt kein Subjekt „Alle“ (ADR-0037,
                      // Entscheidung 3); die Auswahl bietet es hier nicht an.
                      if (!subjectId || subject.type === 'ALL_ACCOUNTS') return
                      if (!(await confirmExternalSubject(subject))) return
                      clearFailure()
                      try {
                        await addMember(spaceId, subject.type, subjectId, newMemberRole)
                        setSubject(emptySubjectSelection)
                        notify(`${addedSubjectLabel(subject)} hinzugefügt`, 'success')
                      } catch (err) {
                        setLocalError(
                          successionAwareMessage(
                            err,
                            'Das Mitglied konnte nicht hinzugefügt werden',
                          ),
                        )
                      }
                    }}
                  >
                    Hinzufügen
                  </Button>
                }
              />
            </Stack>
          )}
        </Stack>
      )}
    </Stack>
  )
}
