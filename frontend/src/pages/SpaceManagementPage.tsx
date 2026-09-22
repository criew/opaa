import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Link from '@mui/material/Link'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormHelperText from '@mui/material/FormHelperText'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNavigate, useParams } from 'react-router'
import type {
  LibraryListResponse,
  SpaceMemberResponse,
  SpaceRole,
  SpaceVisibility,
} from '../types/api'
import { getLibraries } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { useSpaceStore } from '../stores/spaceStore'
import {
  groupGrowthLabel,
  spaceRoleLabel,
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'
import AccessDerivation from '../components/permissions/AccessDerivation'
import SubjectPicker from '../components/permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  selectedSubjectId,
  type SubjectSelection,
} from '../components/permissions/subjectSelection'
import PageHeading from '../components/a11y/PageHeading'
import { successionAwareMessage } from '../components/succession/successionConflict'
import FieldLabel from '../components/wizard/FieldLabel'
import MetaBadge from '../components/MetaBadge'
import SectionHead from '../components/SectionHead'

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

function canManageMembers(role: SpaceRole | undefined): boolean {
  return role === 'ADMIN'
}

// #203: a CURATOR may associate and detach libraries, one level below ADMIN's member management -
// docs/features/spaces-and-assets.md#space-rollen ("CURATOR: zusätzlich Assets assoziieren und
// lösen").
function canManageLibraries(role: SpaceRole | undefined, isOwner: boolean): boolean {
  return role === 'CURATOR' || role === 'ADMIN' || isOwner
}

export default function SpaceManagementPage() {
  const { spaceId } = useParams()
  const navigate = useNavigate()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const selectSpace = useSpaceStore((s) => s.selectSpace)
  const loadMembers = useSpaceStore((s) => s.loadMembers)
  const space = useSpaceStore((s) => s.selectedSpace)
  const members = useSpaceStore((s) => s.members)
  const isLoadingMembers = useSpaceStore((s) => s.isLoadingMembers)
  const error = useSpaceStore((s) => s.error)
  const addMember = useSpaceStore((s) => s.addMember)
  const updateMemberRole = useSpaceStore((s) => s.updateMemberRole)
  const removeMember = useSpaceStore((s) => s.removeMember)
  const transferOwnership = useSpaceStore((s) => s.transferOwnership)
  const updateDetails = useSpaceStore((s) => s.updateDetails)
  const deleteSelectedSpace = useSpaceStore((s) => s.deleteSelectedSpace)
  const archiveSelectedSpace = useSpaceStore((s) => s.archiveSelectedSpace)
  const libraryAssociations = useSpaceStore((s) => s.libraryAssociations)
  const isLoadingLibraryAssociations = useSpaceStore((s) => s.isLoadingLibraryAssociations)
  const loadLibraryAssociations = useSpaceStore((s) => s.loadLibraryAssociations)
  const associateLibrary = useSpaceStore((s) => s.associateLibrary)
  const detachLibrary = useSpaceStore((s) => s.detachLibrary)
  const [draft, setDraft] = useState<{
    spaceId: string | null
    name: string
    description: string
    visibility: SpaceVisibility
  }>({
    spaceId: null,
    name: '',
    description: '',
    visibility: 'PRIVATE',
  })
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const [newMemberRole, setNewMemberRole] = useState<SpaceRole>('MEMBER')
  /** Die Mitgliedszeile, deren Herleitung gerade aufgeklappt ist (#1822). */
  const [derivationFor, setDerivationFor] = useState<string | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  // #543: deleteSpace's 409 - "Der Space enthält noch Chats ... Archivieren Sie den Space
  // stattdessen." - is the one failure this page offers a direct way out of, instead of just
  // showing the message.
  const [deleteBlockedByChats, setDeleteBlockedByChats] = useState(false)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [readableLibraries, setReadableLibraries] = useState<LibraryListResponse[]>([])
  const [selectedLibrary, setSelectedLibrary] = useState<LibraryListResponse | null>(null)

  useEffect(() => {
    if (spaceId) {
      void loadSpaces()
      void selectSpace(spaceId)
    }
  }, [loadSpaces, selectSpace, spaceId])

  // #144: this page is only linked to for ADMIN (see SpacePage's "Space verwalten" button), but a
  // MEMBER or CURATOR reaching it directly via URL simply gets an empty list from the 403 below
  // instead of any identities or display names.
  useEffect(() => {
    if (spaceId) {
      void loadMembers(spaceId)
    }
  }, [loadMembers, spaceId])

  useEffect(() => {
    if (spaceId) {
      void loadLibraryAssociations(spaceId)
    }
  }, [loadLibraryAssociations, spaceId])

  useEffect(() => {
    // #203: a CURATOR may only associate a library they themselves can read - GET /v1/libraries
    // already returns exactly that set, and the backend re-checks the same rule.
    void getLibraries()
      .then(setReadableLibraries)
      .catch(() => setReadableLibraries([]))
  }, [])

  const canManage = useMemo(() => canManageMembers(space?.userRole), [space?.userRole])
  const isOwner = Boolean(currentUserId) && space?.ownerId === currentUserId
  const canManageAssociations = canManageLibraries(space?.userRole, isOwner)
  const associableLibraries = useMemo(() => {
    const associatedIds = new Set(libraryAssociations.map((a) => a.libraryId))
    return readableLibraries.filter((l) => !associatedIds.has(l.id))
  }, [readableLibraries, libraryAssociations])
  const activeSpaceId = space?.id ?? null
  const name = draft.spaceId === activeSpaceId ? draft.name : (space?.name ?? '')
  const description =
    draft.spaceId === activeSpaceId ? draft.description : (space?.description ?? '')
  const visibility =
    draft.spaceId === activeSpaceId ? draft.visibility : (space?.visibility ?? 'PRIVATE')

  if (!spaceId || !space) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        <PageHeading title="Space nicht geladen" />
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 2,
          mb: 3,
          maxWidth: 760,
          flexWrap: 'wrap',
        }}
      >
        <PageHeading
          title="Space-Einstellungen"
          documentTitle={`Space-Einstellungen – ${space.name}`}
        />
        <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
          {space.name}
        </Typography>
        {space.archived && <MetaBadge>Archiviert</MetaBadge>}
      </Box>
      {(error || localError) && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            deleteBlockedByChats ? (
              <Button
                color="inherit"
                size="small"
                onClick={async () => {
                  try {
                    await archiveSelectedSpace(spaceId)
                    setDeleteBlockedByChats(false)
                    setLocalError(null)
                    setSuccessMessage('Space archiviert')
                  } catch (err) {
                    setLocalError(err instanceof Error ? err.message : 'Archivieren fehlgeschlagen')
                  }
                }}
              >
                Space archivieren
              </Button>
            ) : undefined
          }
        >
          {localError ?? error}
        </Alert>
      )}
      {successMessage && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {successMessage}
        </Alert>
      )}
      {space.successionOpen && (
        // #1815, ADR-0036 Entscheidung 6: state and addressee, deliberately without a date,
        // without the previous owner and without a reason - those belong in the operational list
        // (#1819), not beside a colleague's name. The space stays fully usable.
        <Alert severity="warning" sx={{ mb: 2 }}>
          Nachfolge offen — zuständig: Systemverwaltung. Der Space bleibt nutzbar, bestehende Rechte
          bleiben bestehen.
        </Alert>
      )}

      <Stack spacing={5} sx={{ maxWidth: 760 }}>
        <Box>
          <SectionHead>Space-Einstellungen</SectionHead>
          {space.archived && (
            <Alert severity="info" sx={{ mb: 2 }}>
              Dieser Space ist archiviert und nimmt keinen neuen Inhalt mehr an. Private Chats
              bleiben für ihre Autoren weiterhin lesbar.
            </Alert>
          )}
          <Stack spacing={2.5}>
            <Box>
              <FieldLabel htmlFor="space-manage-name">Name des Space</FieldLabel>
              <TextField
                id="space-manage-name"
                size="small"
                fullWidth
                value={name}
                onChange={(event) =>
                  setDraft({
                    spaceId: activeSpaceId,
                    name: event.target.value,
                    description,
                    visibility,
                  })
                }
                disabled={!canManage}
              />
            </Box>
            <Box>
              <FieldLabel htmlFor="space-manage-description">Beschreibung</FieldLabel>
              <TextField
                id="space-manage-description"
                size="small"
                fullWidth
                value={description}
                onChange={(event) =>
                  setDraft({
                    spaceId: activeSpaceId,
                    name,
                    description: event.target.value,
                    visibility,
                  })
                }
                multiline
                minRows={2}
                disabled={!canManage}
              />
            </Box>
            <FormControl disabled={!canManage} fullWidth>
              <FieldLabel id="space-visibility-label">Sichtbarkeit</FieldLabel>
              <Select
                labelId="space-visibility-label"
                size="small"
                value={visibility}
                onChange={(event) =>
                  setDraft({
                    spaceId: activeSpaceId,
                    name,
                    description,
                    visibility: event.target.value as SpaceVisibility,
                  })
                }
                aria-describedby="space-visibility-helper"
              >
                {spaceVisibilities.map((option) => (
                  <MenuItem key={option} value={option}>
                    {spaceVisibilityLabel(option)}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText id="space-visibility-helper">
                {spaceVisibilityDescription(visibility)}
              </FormHelperText>
            </FormControl>
            <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
              {canManage && (
                <Button
                  variant="contained"
                  onClick={async () => {
                    setLocalError(null)
                    try {
                      await updateDetails(spaceId, name, description, visibility)
                      setSuccessMessage('Space aktualisiert')
                    } catch (err) {
                      setLocalError(
                        err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen',
                      )
                    }
                  }}
                >
                  Einstellungen speichern
                </Button>
              )}
              {isOwner && !space.isDefault && !space.archived && (
                <Button
                  variant="outlined"
                  onClick={async () => {
                    const confirmed = await confirmAction({
                      question: 'Diesen Space archivieren?',
                      consequence:
                        'Er nimmt danach keinen neuen Inhalt mehr an und wird aus den regulären Listen ausgeblendet.',
                      confirmLabel: 'Archivieren',
                      tone: 'caution',
                    })
                    if (!confirmed) return
                    setLocalError(null)
                    setDeleteBlockedByChats(false)
                    try {
                      await archiveSelectedSpace(spaceId)
                      setSuccessMessage('Space archiviert')
                    } catch (err) {
                      setLocalError(
                        err instanceof Error ? err.message : 'Archivieren fehlgeschlagen',
                      )
                    }
                  }}
                >
                  Space archivieren
                </Button>
              )}
              {isOwner && !space.isDefault && (
                <Button
                  color="error"
                  variant="outlined"
                  onClick={async () => {
                    const confirmed = await confirmAction({
                      question: 'Diesen Space löschen?',
                      consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
                      confirmLabel: 'Löschen',
                      tone: 'danger',
                    })
                    if (!confirmed) return
                    setLocalError(null)
                    setDeleteBlockedByChats(false)
                    try {
                      await deleteSelectedSpace(spaceId)
                      navigate('/spaces')
                    } catch (err) {
                      const message = err instanceof Error ? err.message : 'Löschen fehlgeschlagen'
                      setLocalError(message)
                      // #543: deleteSpace's own 409 message names archiving as the way out - offer
                      // it directly instead of leaving the user to figure out the next step.
                      setDeleteBlockedByChats(message.includes('Archivieren'))
                    }
                  }}
                >
                  Space löschen
                </Button>
              )}
            </Box>
          </Stack>
        </Box>

        <Box>
          <SectionHead>Mitglieder</SectionHead>
          {space.isDefault && space.memberCount === 1 && (
            // #777: this hint used to replace the whole members section, including the
            // "Mitglied hinzufügen"-Formular it explicitly promises - the default space is "ein
            // Space wie jeder andere" (docs/features/spaces-and-assets.md), so members can be
            // added here just like on any other space.
            <Alert severity="info" sx={{ mb: 2 }}>
              Dies ist Ihr Standard-Space. Sie arbeiten hier allein — Sie können jederzeit
              Mitglieder hinzufügen.
            </Alert>
          )}
          {isLoadingMembers ? (
            <Typography sx={{ color: 'text.secondary' }}>Mitgliederliste wird geladen …</Typography>
          ) : members.length === 0 && !canManage && !isOwner ? (
            // #674 review, nit c: a MEMBER or CURATOR reaching this page directly by URL gets a
            // silent empty list from listSpaceMembers's 403 handling (#144) - without this, that
            // renders as an unexplained blank block instead of naming why nothing is shown.
            <Alert severity="info">
              Sie haben nicht die erforderliche Rolle, um die Mitgliederliste dieses Space
              einzusehen. Nur Administratoren, der Eigentümer und Systemadministratoren können sie
              sehen.
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
                                err instanceof Error
                                  ? err.message
                                  : 'Rollenänderung fehlgeschlagen',
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
                        if (!subjectId) return
                        if (!(await confirmExternalSubject(subject))) return
                        setLocalError(null)
                        try {
                          await addMember(spaceId, subject.type, subjectId, newMemberRole)
                          setSubject(emptySubjectSelection)
                          setSuccessMessage(
                            subject.type === 'GROUP'
                              ? 'Gruppe hinzugefügt'
                              : 'Mitglied hinzugefügt',
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
                  </Stack>
                </Stack>
              )}
            </Stack>
          )}
        </Box>

        <Box>
          <SectionHead>Datenquellen</SectionHead>
          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
            Eine Zuordnung stellt eine Bibliothek in diesem Space bereit, gewährt aber niemandem
            zusätzlichen Zugriff — nur Mitglieder mit eigenem Leserecht auf die Bibliothek sehen
            ihre Treffer.
          </Typography>
          {isLoadingLibraryAssociations ? (
            <Typography sx={{ color: 'text.secondary' }}>Datenquellen werden geladen …</Typography>
          ) : libraryAssociations.length === 0 ? (
            <Typography sx={{ color: 'text.secondary', mb: 2 }}>
              Diesem Space sind keine Bibliotheken zugeordnet.
            </Typography>
          ) : (
            <Stack spacing={0} sx={{ mb: 2 }}>
              {libraryAssociations.map((association) => (
                <Box
                  key={association.libraryId}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    py: 1.25,
                    '& + &': { borderTop: 1, borderColor: 'divider' },
                  }}
                >
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography
                      sx={
                        association.readableByCaller
                          ? undefined
                          : { color: 'text.secondary', fontStyle: 'italic' }
                      }
                    >
                      {association.readableByCaller
                        ? association.libraryName
                        : 'Bibliothek ohne eigenen Zugriff'}
                    </Typography>
                  </Stack>
                  {canManageAssociations && (
                    <Button
                      color="error"
                      size="small"
                      onClick={async () => {
                        setLocalError(null)
                        try {
                          await detachLibrary(spaceId, association.libraryId)
                        } catch (err) {
                          setLocalError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
                        }
                      }}
                    >
                      Lösen
                    </Button>
                  )}
                </Box>
              ))}
            </Stack>
          )}
          {canManageAssociations && (
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
              <Autocomplete
                options={associableLibraries}
                getOptionLabel={(option) => option.name}
                noOptionsText="Keine Treffer"
                value={selectedLibrary}
                onChange={(_event, value) => setSelectedLibrary(value)}
                renderInput={(params) => (
                  <TextField {...params} label="Bibliothek" placeholder="Bibliothek suchen …" />
                )}
                isOptionEqualToValue={(option, value) => option.id === value.id}
                sx={{ minWidth: 280 }}
              />
              <Button
                variant="contained"
                disabled={!selectedLibrary}
                onClick={async () => {
                  if (!selectedLibrary) return
                  setLocalError(null)
                  try {
                    await associateLibrary(spaceId, selectedLibrary.id)
                    setSelectedLibrary(null)
                    setSuccessMessage('Bibliothek zugeordnet')
                  } catch (err) {
                    setLocalError(err instanceof Error ? err.message : 'Zuordnung fehlgeschlagen')
                  }
                }}
              >
                Zuordnen
              </Button>
            </Stack>
          )}
        </Box>
      </Stack>
    </Box>
  )
}
