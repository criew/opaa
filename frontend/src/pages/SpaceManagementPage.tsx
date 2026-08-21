import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
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
import type { LibraryListResponse, SpaceRole, SpaceVisibility, UserInfo } from '../types/api'
import { getLibraries, getUsers } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import {
  spaceRoleLabel,
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'
import PageHeading from '../components/a11y/PageHeading'
import FieldLabel from '../components/wizard/FieldLabel'
import MetaBadge from '../components/MetaBadge'
import SectionHead from '../components/SectionHead'

const editableRoles: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

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
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [newMemberRole, setNewMemberRole] = useState<SpaceRole>('MEMBER')
  const [localError, setLocalError] = useState<string | null>(null)
  // #543: deleteSpace's 409 - "Der Space enthält noch Chats ... Archivieren Sie den Space
  // stattdessen." - is the one failure this page offers a direct way out of, instead of just
  // showing the message.
  const [deleteBlockedByChats, setDeleteBlockedByChats] = useState(false)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
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
    void getUsers()
      .then(setAllUsers)
      .catch(() => setAllUsers([]))
    // #203: a CURATOR may only associate a library they themselves can read - GET /v1/libraries
    // already returns exactly that set, and the backend re-checks the same rule.
    void getLibraries()
      .then(setReadableLibraries)
      .catch(() => setReadableLibraries([]))
  }, [])

  const canManage = useMemo(() => canManageMembers(space?.userRole), [space?.userRole])
  const availableUsers = useMemo(() => {
    const memberIds = new Set(members.map((m) => m.userId))
    return allUsers.filter((u) => !memberIds.has(u.id))
  }, [allUsers, members])
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
        <PageHeading title="Space einrichten" documentTitle={`${space.name} einrichten`} />
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
                    if (
                      !window.confirm(
                        'Diesen Space archivieren? Er nimmt danach keinen neuen Inhalt mehr an ' +
                          'und wird aus den regulären Listen ausgeblendet.',
                      )
                    ) {
                      return
                    }
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
                    if (
                      !window.confirm(
                        'Diesen Space löschen? Diese Aktion kann nicht rückgängig gemacht werden.',
                      )
                    ) {
                      return
                    }
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
          {space.isDefault && space.memberCount === 1 ? (
            <Alert severity="info">
              Dies ist Ihr Standard-Space. Sie arbeiten hier allein — Sie können jederzeit
              Mitglieder hinzufügen.
            </Alert>
          ) : isLoadingMembers ? (
            <Typography color="text.secondary">Mitgliederliste wird geladen …</Typography>
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
                const memberIsOwner = member.userId === space.ownerId
                return (
                  <Box
                    key={member.userId}
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
                    <Typography
                      sx={{
                        fontSize: 13.5,
                        ...(member.displayName ? {} : { fontFamily: 'monospace' }),
                      }}
                    >
                      {member.displayName ?? member.userId}
                      {memberIsOwner ? ' · Eigentümer' : ''}
                    </Typography>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      {canManage ? (
                        <Select
                          size="small"
                          value={member.role}
                          onChange={async (event) => {
                            const nextRole = event.target.value as SpaceRole
                            setLocalError(null)
                            try {
                              await updateMemberRole(spaceId, member.userId, nextRole)
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
                            if (
                              !window.confirm(
                                `${member.displayName ?? member.userId} aus diesem Space entfernen?`,
                              )
                            ) {
                              return
                            }
                            setLocalError(null)
                            try {
                              await removeMember(spaceId, member.userId)
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
                      {isOwner && !memberIsOwner && (
                        <Button
                          size="small"
                          onClick={async () => {
                            if (
                              !window.confirm(
                                `Verantwortung an ${member.displayName ?? member.userId} übertragen?`,
                              )
                            ) {
                              return
                            }
                            setLocalError(null)
                            try {
                              await transferOwnership(spaceId, member.userId)
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
                <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ pt: 2 }}>
                  <Autocomplete
                    options={availableUsers}
                    size="small"
                    getOptionLabel={(option) =>
                      option.displayName
                        ? `${option.displayName} (${option.email ?? option.id})`
                        : (option.email ?? option.id)
                    }
                    value={selectedUser}
                    onChange={(_event, value) => setSelectedUser(value)}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        placeholder="Benutzer suchen …"
                        slotProps={{
                          ...params.slotProps,
                          htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Benutzer' },
                        }}
                      />
                    )}
                    isOptionEqualToValue={(option, value) => option.id === value.id}
                    sx={{ minWidth: 280, flex: 1 }}
                  />
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
                    disabled={!selectedUser}
                    onClick={async () => {
                      if (!selectedUser) return
                      setLocalError(null)
                      try {
                        await addMember(spaceId, selectedUser.id, newMemberRole)
                        setSelectedUser(null)
                        setSuccessMessage('Mitglied hinzugefügt')
                      } catch (err) {
                        setLocalError(
                          err instanceof Error
                            ? err.message
                            : 'Mitglied konnte nicht hinzugefügt werden',
                        )
                      }
                    }}
                  >
                    Mitglied hinzufügen
                  </Button>
                </Stack>
              )}
            </Stack>
          )}
        </Box>

        <Box>
          <SectionHead>Datenquellen</SectionHead>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Eine Zuordnung stellt eine Bibliothek in diesem Space bereit, gewährt aber niemandem
            zusätzlichen Zugriff — nur Mitglieder mit eigenem Leserecht auf die Bibliothek sehen
            ihre Treffer.
          </Typography>
          {isLoadingLibraryAssociations ? (
            <Typography color="text.secondary">Datenquellen werden geladen …</Typography>
          ) : libraryAssociations.length === 0 ? (
            <Typography color="text.secondary" sx={{ mb: 2 }}>
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
