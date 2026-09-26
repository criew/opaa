import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SpaceMemberResponse, SpaceResponse, SpaceRole } from '../../types/api'
import { getSpaceGroupMembers } from '../../services/api'
import { confirmAction } from '../../stores/confirmStore'
import { useSpaceStore } from '../../stores/spaceStore'
import { groupGrowthLabel, spaceRoleLabel } from '../../utils/labels'
import AccessDerivation from '../permissions/AccessDerivation'
import GroupMembersDisclosure from '../permissions/GroupMembersDisclosure'
import SubjectPicker from '../permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from '../permissions/subjectSelection'
import { successionAwareMessage } from '../succession/successionConflict'
import MetaBadge from '../MetaBadge'
import SectionHead from '../SectionHead'

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

// #1815, ADR-0036 Entscheidung 9: the growth signal beside a group row - "23 bei Aufnahme, heute
// 41". Below the enforced minimum group size the backend withholds both figures and sets
// smallGroup; for a protected group the signal drops out entirely.
function groupSizeHint(member: SpaceMemberResponse): string | null {
  if (member.subjectType !== 'GROUP') return null
  return groupGrowthLabel(member, 'Aufnahme')
}

/**
 * #1820, ADR-0036 Entscheidung 9: eine geschützte Gruppe erscheint in fremden Listen ohne Namen -
 * der Dienst liefert keinen. Die Zeile bleibt, sonst könnte ein ADMIN eine Mitgliedschaft nicht
 * beenden, die er nicht sieht.
 */
function memberLabelOf(member: SpaceMemberResponse): string {
  if (member.protectedGroup) return 'Geschützte Gruppe'
  return member.displayName ?? member.subjectId
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
  /** Die Mitgliedszeile, deren Herleitung gerade aufgeklappt ist (#1822). */
  const [derivationFor, setDerivationFor] = useState<string | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  // #144: der Dienst beantwortet die Liste nur für ADMIN, Eigentümer und Systemverwaltung; für
  // alle anderen bleibt sie leer, und der Hinweis unten nennt den Grund.
  useEffect(() => {
    void loadMembers(spaceId)
  }, [loadMembers, spaceId])

  return (
    <Stack spacing={2}>
      {/* Die h2 dieses Panels unter der h1 der Seite - „Mitglied hinzufügen" darunter ist h3. */}
      <SectionHead>Mitglieder</SectionHead>
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
          {members.map((member) => {
            const isGroup = member.subjectType === 'GROUP'
            const memberIsOwner = !isGroup && member.subjectId === space.ownerId
            const memberLabel = memberLabelOf(member)
            const sizeHint = groupSizeHint(member)
            const namelessRow = !member.displayName && !member.protectedGroup
            return (
              <Box
                key={member.id}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 1,
                  flexWrap: 'wrap',
                  py: 1.25,
                  '& + &': { borderTop: 1, borderColor: 'divider' },
                }}
              >
                <Stack spacing={0.25} sx={{ minWidth: 200, flexGrow: 1 }}>
                  <Typography
                    sx={{
                      fontSize: 13.5,
                      ...(namelessRow ? { fontFamily: 'monospace' } : {}),
                      ...(member.protectedGroup ? { fontStyle: 'italic' } : {}),
                    }}
                  >
                    {memberLabel}
                    {memberIsOwner ? ' · Eigentümer' : ''}
                    {isGroup && !member.protectedGroup ? ' · Gruppe' : ''}
                    {sizeHint ? ` · ${sizeHint}` : ''}
                  </Typography>
                  {!isGroup && (
                    // #1822: ob eine Rolle direkt oder über eine Gruppe kommt, steht in der
                    // Herleitung - hier für die Person, deren Mitgliedschaft man verwaltet.
                    <Link
                      component="button"
                      type="button"
                      sx={{ alignSelf: 'flex-start', fontSize: 12 }}
                      onClick={() =>
                        setDerivationFor((current) =>
                          current === member.subjectId ? null : member.subjectId,
                        )
                      }
                    >
                      {derivationFor === member.subjectId
                        ? 'Herleitung ausblenden'
                        : `Herleitung für ${memberLabel}`}
                    </Link>
                  )}
                  {derivationFor === member.subjectId && (
                    <AccessDerivation
                      target={{ kind: 'space', spaceId, userId: member.subjectId }}
                    />
                  )}
                  {/* #1880, ADR-0036 Entscheidung 9: Wer die Gruppe hier aufgenommen hat,
                      sieht ihre Mitglieder — erst auf ausdrücklichen Wunsch. Bei einer
                      geschützten Gruppe nennt die Antwort die Verantwortlichen statt der
                      Namen. */}
                  {isGroup && (canManage || isOwner) && (
                    <GroupMembersDisclosure
                      groupLabel={
                        member.protectedGroup
                          ? 'Geschützte Gruppe'
                          : (member.displayName ?? 'ohne Namen')
                      }
                      load={(offset, limit) =>
                        getSpaceGroupMembers(spaceId, member.subjectId, offset, limit)
                      }
                    />
                  )}
                </Stack>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  {memberIsOwner ? (
                    // #777: the owner's role can only change via "Zum Eigentümer machen" on
                    // another member's row (transferOwnership) - an editable dropdown here
                    // let an ADMIN pick a different role for the owner, which the backend
                    // always rejected with "Die Rolle des Eigentümers kann nicht geändert
                    // werden; übertragen Sie zuerst die Verantwortung".
                    <MetaBadge>{spaceRoleLabel(member.role)}</MetaBadge>
                  ) : canManage ? (
                    <Select
                      size="small"
                      value={member.role}
                      onChange={async (event) => {
                        const nextRole = event.target.value as SpaceRole
                        setLocalError(null)
                        try {
                          await updateMemberRole(spaceId, member.id, nextRole)
                        } catch (err) {
                          setLocalError(
                            err instanceof Error ? err.message : 'Rollenänderung fehlgeschlagen',
                          )
                        }
                      }}
                    >
                      {editableRoles.map((role) => (
                        <MenuItem key={role} value={role}>
                          {spaceRoleLabel(role)}
                        </MenuItem>
                      ))}
                    </Select>
                  ) : (
                    <MetaBadge>{spaceRoleLabel(member.role)}</MetaBadge>
                  )}
                  {canManage && !memberIsOwner && (
                    <Button
                      color="error"
                      size="small"
                      onClick={async () => {
                        const confirmed = await confirmAction({
                          question: `${memberLabel} aus diesem Space entfernen?`,
                          confirmLabel: 'Entfernen',
                          tone: 'caution',
                        })
                        if (!confirmed) return
                        setLocalError(null)
                        try {
                          await removeMember(spaceId, member.id)
                        } catch (err) {
                          setLocalError(
                            err instanceof Error
                              ? err.message
                              : 'Entfernen des Mitglieds fehlgeschlagen',
                          )
                        }
                      }}
                    >
                      Entfernen
                    </Button>
                  )}
                  {/* #1815: Die Verantwortung darf jedes handlungsfähige ADMIN-Mitglied
                      übertragen — an sich oder an ein anderes solches Mitglied (ADR-0036
                      Entscheidung 6). Eine Gruppe kommt dafür nicht in Betracht: Der
                      Eigentümer bleibt eine natürliche Person. */}
                  {(canManage || isOwner) && !memberIsOwner && !isGroup && (
                    <Button
                      size="small"
                      onClick={async () => {
                        const confirmed = await confirmAction({
                          question: `Verantwortung an ${memberLabel} übertragen?`,
                          confirmLabel: 'Übertragen',
                          tone: 'caution',
                        })
                        if (!confirmed) return
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
                    >
                      Zum Eigentümer machen
                    </Button>
                  )}
                </Stack>
              </Box>
            )
          })}

          {canManage && (
            <Stack spacing={1.5} sx={{ pt: 2 }}>
              <SectionHead component="h3">Mitglied hinzufügen</SectionHead>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Eine Gruppe als Mitglied gibt ihre Rolle an alle Mitglieder weiter — ohne eigene
                Zeile, und sie endet mit dem Austritt aus der Gruppe.
              </Typography>
              <SubjectPicker
                labelId="space-member-subject-label"
                value={subject}
                onChange={setSubject}
                excludedUserIds={members
                  .filter((member) => member.subjectType === 'USER')
                  .map((member) => member.subjectId)}
                excludedGroupIds={members
                  .filter((member) => member.subjectType === 'GROUP')
                  .map((member) => member.subjectId)}
              />
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
                <Select
                  size="small"
                  value={newMemberRole}
                  onChange={(event) => setNewMemberRole(event.target.value as SpaceRole)}
                  aria-label="Rolle des neuen Mitglieds"
                  sx={{ width: 180 }}
                >
                  {editableRoles.map((role) => (
                    <MenuItem key={role} value={role}>
                      {spaceRoleLabel(role)}
                    </MenuItem>
                  ))}
                </Select>
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
                        successionAwareMessage(err, 'Das Mitglied konnte nicht hinzugefügt werden'),
                      )
                    }
                  }}
                >
                  Hinzufügen
                </Button>
              </Stack>
            </Stack>
          )}
        </Stack>
      )}
    </Stack>
  )
}
