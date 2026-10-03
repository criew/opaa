import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SpaceResponse, SpaceRole } from '../../types/api'
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

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

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
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  // #144: der Dienst beantwortet die Liste nur für ADMIN, Eigentümer und Systemverwaltung; für
  // alle anderen bleibt sie leer, und der Hinweis unten nennt den Grund.
  useEffect(() => {
    void loadMembers(spaceId)
  }, [loadMembers, spaceId])

  return (
    <Stack spacing={2}>
      {(localError || storeError) && <Alert severity="error">{localError ?? storeError}</Alert>}
      {successMessage && <Alert severity="success">{successMessage}</Alert>}
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
        // #674 review, nit c: a MEMBER or CURATOR reaching this page directly by URL gets a
        // silent empty list from listSpaceMembers's 403 handling (#144) - without this, that
        // renders as an unexplained blank block instead of naming why nothing is shown.
        <Alert severity="info">
          Sie haben nicht die erforderliche Rolle, um die Mitgliederliste dieses Space einzusehen.
          Nur Administratoren, der Eigentümer und Systemadministratoren können sie sehen.
        </Alert>
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
              setLocalError(null)
              try {
                await updateMemberRole(spaceId, member.id, role)
              } catch (err) {
                setLocalError(err instanceof Error ? err.message : 'Rollenänderung fehlgeschlagen')
              }
            }}
            onRemove={async (member) => {
              setLocalError(null)
              try {
                await removeMember(spaceId, member.id)
              } catch (err) {
                setLocalError(
                  err instanceof Error ? err.message : 'Entfernen des Mitglieds fehlgeschlagen',
                )
              }
            }}
            onMakeOwner={async (member) => {
              setLocalError(null)
              try {
                await transferOwnership(spaceId, member.subjectId)
                setSuccessMessage('Verantwortung übertragen')
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
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Gruppen geben ihre Rolle an alle ihre Mitglieder weiter.
              </Typography>
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
                      setLocalError(null)
                      try {
                        await addMember(spaceId, subject.type, subjectId, newMemberRole)
                        setSubject(emptySubjectSelection)
                        setSuccessMessage(
                          subject.type === 'GROUP' ? 'Gruppe hinzugefügt' : 'Mitglied hinzugefügt',
                        )
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
